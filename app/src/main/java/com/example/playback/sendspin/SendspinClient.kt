package com.example.playback.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class SendspinState {
    data object Disconnected : SendspinState()
    data object Connecting : SendspinState()
    /** Handshake complete, waiting for the first audio stream. */
    data object Connected : SendspinState()
    /** Actively receiving and playing a synchronized audio stream. */
    data object Playing : SendspinState()
    /** Buffering / recovering from an underrun; audio is muted until re-synced. */
    data object Buffering : SendspinState()
    data class Error(val message: String) : SendspinState()
}

/**
 * A real Sendspin protocol client implementing the `player@v1` role, following the spec at
 * sendspin-audio.com/spec/ (github.com/Sendspin). This lets the phone register as a genuine
 * Music Assistant player that receives synchronized, timestamped PCM audio directly from the
 * server — rather than guessing at a generic "download this track" HTTP endpoint (which MA
 * doesn't actually expose; audio is always tied to an active player's queue/stream session).
 *
 * Scope notes (stated honestly rather than silently glossed over):
 *  - Requests uncompressed PCM16 from the server, so audio chunks can be written to AudioTrack
 *    directly with zero decoding (Android's PCM16 is already little-endian signed, matching the
 *    spec's PCM convention exactly).
 *  - Implements the documented 4-timestamp (T1-T4) clock sync exchange and a from-scratch 2D
 *    Kalman filter (offset+drift) per the spec's algorithm description (see
 *    SendspinTimeFilter.kt) — not a byte-for-byte port of the reference C++/Python filter, whose
 *    exact tuning constants aren't published, but a faithful implementation of the same
 *    predict/correct/adaptive-forgetting design.
 *  - Aligns precisely to the server timestamp for the FIRST chunk after a (re)start/clear/
 *    underrun-recovery (sleeping until the exact scheduled moment before calling
 *    AudioTrack.play()), then streams subsequent chunks continuously. It does NOT perform
 *    continuous sample-level interpolation to correct slow clock drift mid-stream (which the
 *    spec mentions as the fully-compliant approach) — for a phone listened to solo or synced
 *    within normal listening sessions this keeps drift imperceptible, but very long (hours)
 *    uninterrupted sessions could accumulate a small drift versus other speakers in the same
 *    group. Re-alignment happens automatically on every seek (stream/clear) and buffer underrun.
 *  - Does NOT implement the `controller` role — this app already controls playback (play/pause/
 *    volume/skip) through Music Assistant's main WebSocket API (player_queues/...), which works
 *    seamlessly against this Sendspin-registered player like any other real MA player once it
 *    shows up in players/all. Volume/mute *echoed from the server* (server/command) are handled
 *    here to keep the physical output level in sync with what MA thinks the player's volume is.
 *  - Does not implement mDNS discovery (server- or client-initiated) — connects directly to a
 *    configured host:port, which the spec explicitly allows for mobile-app-style clients
 *    ("custom connection methods are possible for specialized use cases... like mobile apps").
 */
class SendspinClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket; liveness via ping
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val timeFilter = SendspinTimeFilter()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timeSyncJob: Job? = null
    private var feederThread: Thread? = null
    private val running = AtomicBoolean(false)

    private val _state = MutableStateFlow<SendspinState>(SendspinState.Disconnected)
    val state: StateFlow<SendspinState> = _state

    private val _volume = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volume

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private var staticDelayMs = 0

    // --- Audio output state ---
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 44100
    private var currentChannels = 2
    private var currentBitDepth = 16
    private val chunkQueue = LinkedBlockingQueue<AudioChunk>()
    @Volatile private var needsRealign = true

    private data class AudioChunk(val targetServerTimeUs: Long, val pcm: ByteArray)

    /** Monotonic microsecond clock shared by both time-sync and playback scheduling. */
    private fun nowMicros(): Long = System.nanoTime() / 1000

    /**
     * Connects to the Sendspin server and performs the client/hello -> server/hello handshake.
     * [host] and [sendspinPort] point at the Music Assistant server's Sendspin endpoint
     * (ws://host:sendspinPort/sendspin).
     */
    fun connect(host: String, sendspinPort: String, clientId: String, playerName: String) {
        disconnect("restart") // clean up any prior connection first

        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
        if (cleanHost.isEmpty()) {
            _state.value = SendspinState.Error("Host inválido")
            return
        }
        val port = sendspinPort.trim().ifEmpty { "8927" }
        val wsUrl = "ws://$cleanHost:$port/sendspin"

        _state.value = SendspinState.Connecting
        needsRealign = true
        timeFilter.reset()

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("Sendspin", "WebSocket open, sending client/hello")
                sendClientHello(ws, clientId, playerName)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(ws, text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("Sendspin", "WebSocket failure: ${t.message}")
                _state.value = SendspinState.Error(t.localizedMessage ?: "Conexión perdida")
                teardownAudio()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d("Sendspin", "WebSocket closed: $reason")
                if (_state.value !is SendspinState.Error) _state.value = SendspinState.Disconnected
                teardownAudio()
            }
        })

        startFeederThread()
    }

    fun disconnect(reason: String = "user_request") {
        running.set(false)
        timeSyncJob?.cancel()
        timeSyncJob = null
        try {
            webSocket?.let { ws ->
                val goodbye = JSONObject().apply {
                    put("type", "client/goodbye")
                    put("payload", JSONObject().put("reason", reason))
                }
                ws.send(goodbye.toString())
                ws.close(1000, reason)
            }
        } catch (e: Exception) {
            Log.e("Sendspin", "Error sending goodbye: ${e.message}")
        }
        webSocket = null
        feederThread?.interrupt()
        feederThread = null
        teardownAudio()
        chunkQueue.clear()
        if (_state.value !is SendspinState.Error) _state.value = SendspinState.Disconnected
    }

    // -----------------------------------------------------------------------------------------
    // Handshake
    // -----------------------------------------------------------------------------------------

    private fun sendClientHello(ws: WebSocket, clientId: String, playerName: String) {
        val supportObj = JSONObject().apply {
            put("supported_formats", JSONArray().apply {
                // Priority order: PCM16 stereo at the two most common consumer sample rates.
                // Requesting PCM means zero decoding work on our end (matches Android's native format).
                put(JSONObject().apply {
                    put("codec", "pcm"); put("channels", 2); put("sample_rate", 44100); put("bit_depth", 16)
                })
                put(JSONObject().apply {
                    put("codec", "pcm"); put("channels", 2); put("sample_rate", 48000); put("bit_depth", 16)
                })
            })
            put("buffer_capacity", 2_097_152) // ~2MB headroom (~12s of PCM16 stereo @44.1kHz)
            put("supported_commands", JSONArray().apply { put("volume"); put("mute") })
        }

        val hello = JSONObject().apply {
            put("type", "client/hello")
            put("payload", JSONObject().apply {
                put("client_id", clientId)
                put("name", playerName)
                put("device_info", JSONObject().apply {
                    put("product_name", android.os.Build.MODEL)
                    put("manufacturer", android.os.Build.MANUFACTURER)
                    put("software_version", "1.0")
                })
                put("version", 1)
                put("supported_roles", JSONArray().put("player@v1"))
                put("player@v1_support", supportObj)
            })
        }
        ws.send(hello.toString())
    }

    private fun sendInitialClientState(ws: WebSocket) {
        val stateMsg = JSONObject().apply {
            put("type", "client/state")
            put("payload", JSONObject().apply {
                put("state", "synchronized")
                put("player", JSONObject().apply {
                    put("volume", _volume.value)
                    put("muted", _muted.value)
                    put("static_delay_ms", staticDelayMs)
                    put("supported_commands", JSONArray().put("set_static_delay"))
                })
            })
        }
        ws.send(stateMsg.toString())
    }

    /** Echoes current player state back to the server (required whenever anything changes). */
    private fun sendClientState(playbackState: String) {
        val ws = webSocket ?: return
        val stateMsg = JSONObject().apply {
            put("type", "client/state")
            put("payload", JSONObject().apply {
                put("state", playbackState)
                put("player", JSONObject().apply {
                    put("volume", _volume.value)
                    put("muted", _muted.value)
                    put("static_delay_ms", staticDelayMs)
                })
            })
        }
        ws.send(stateMsg.toString())
    }

    private fun startTimeSyncLoop(ws: WebSocket) {
        timeSyncJob?.cancel()
        timeSyncJob = scope.launch {
            var intervalMs = 300L // sync quickly at first
            while (isActive) {
                val t1 = nowMicros()
                val msg = JSONObject().apply {
                    put("type", "client/time")
                    put("payload", JSONObject().apply { put("client_transmitted", t1) })
                }
                try {
                    ws.send(msg.toString())
                } catch (e: Exception) {
                    Log.e("Sendspin", "Failed to send client/time: ${e.message}")
                }
                delay(intervalMs)
                // Back off to a slower steady-state cadence once we have enough samples.
                if (timeFilter.isSynced) intervalMs = 5000L
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Incoming text messages
    // -----------------------------------------------------------------------------------------

    private fun handleTextMessage(ws: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            val type = msg.optString("type")
            val payload = msg.optJSONObject("payload") ?: JSONObject()

            when (type) {
                "server/hello" -> {
                    Log.d("Sendspin", "Handshake complete: ${payload.optString("name")}")
                    _state.value = SendspinState.Connected
                    sendInitialClientState(ws)
                    startTimeSyncLoop(ws)
                }

                "server/time" -> {
                    val t4 = nowMicros() // client received, captured immediately
                    val t1 = payload.optLong("client_transmitted")
                    val t2 = payload.optLong("server_received")
                    val t3 = payload.optLong("server_transmitted")
                    val measurement = ((t2 - t1) + (t3 - t4)) / 2.0
                    val maxError = ((t4 - t1) - (t3 - t2)) / 2.0
                    timeFilter.update(measurement, maxError, t4)
                }

                "stream/start" -> {
                    val player = payload.optJSONObject("player") ?: return
                    configureAudioTrack(
                        sampleRate = player.optInt("sample_rate", 44100),
                        channels = player.optInt("channels", 2),
                        bitDepth = player.optInt("bit_depth", 16)
                    )
                }

                "stream/clear" -> {
                    val roles = payload.optJSONArray("roles")
                    val includesPlayer = roles == null || (0 until roles.length()).any { roles.optString(it) == "player" }
                    if (includesPlayer) {
                        chunkQueue.clear()
                        audioTrack?.pause()
                        audioTrack?.flush()
                        needsRealign = true
                    }
                }

                "stream/end" -> {
                    val roles = payload.optJSONArray("roles")
                    val includesPlayer = roles == null || (0 until roles.length()).any { roles.optString(it) == "player" }
                    if (includesPlayer) {
                        chunkQueue.clear()
                        audioTrack?.pause()
                        audioTrack?.flush()
                        needsRealign = true
                        _state.value = SendspinState.Connected
                    }
                }

                "server/command" -> {
                    val player = payload.optJSONObject("player") ?: return
                    when (player.optString("command")) {
                        "volume" -> {
                            val vol = player.optInt("volume", _volume.value)
                            _volume.value = vol
                            applyGainToTrack()
                            sendClientState(currentPlaybackStateLabel())
                        }
                        "mute" -> {
                            _muted.value = player.optBoolean("mute", _muted.value)
                            applyGainToTrack()
                            sendClientState(currentPlaybackStateLabel())
                        }
                        "set_static_delay" -> {
                            staticDelayMs = player.optInt("static_delay_ms", staticDelayMs)
                            sendClientState(currentPlaybackStateLabel())
                        }
                    }
                }

                "group/update" -> {
                    // Informational only; play/pause/volume for this app is driven through the
                    // main MA API instead, so we don't need to react to group state here.
                }

                else -> { /* ignore anything we don't implement (server/state, etc.) */ }
            }
        } catch (e: Exception) {
            Log.e("Sendspin", "Error handling message '$text': ${e.message}")
        }
    }

    private fun currentPlaybackStateLabel(): String = when (_state.value) {
        is SendspinState.Error -> "error"
        else -> "synchronized"
    }

    // -----------------------------------------------------------------------------------------
    // Audio output
    // -----------------------------------------------------------------------------------------

    private fun configureAudioTrack(sampleRate: Int, channels: Int, bitDepth: Int) {
        if (bitDepth != 16) {
            Log.e("Sendspin", "Server sent unsupported bit depth $bitDepth; only PCM16 was advertised")
        }
        if (audioTrack != null && sampleRate == currentSampleRate && channels == currentChannels) {
            return // no change needed
        }
        teardownAudio()

        currentSampleRate = sampleRate
        currentChannels = channels
        currentBitDepth = 16

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = (minBuf * 3).coerceAtLeast(minBuf)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        applyGainToTrack()
        needsRealign = true
        _state.value = SendspinState.Connected
    }

    private fun applyGainToTrack() {
        val perceived = if (_muted.value) 0f else (_volume.value / 100f).coerceIn(0f, 1f)
        // Perceived-loudness (0-100) -> linear amplitude, per spec's note that these aren't equal.
        // A simple, standard approximation: amplitude = perceived^2 (roughly matches how humans
        // perceive loudness vs. linear gain, without needing a full dB-based curve).
        val amplitude = perceived * perceived
        audioTrack?.setVolume(amplitude)
    }

    private fun teardownAudio() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore; track may already be in a bad state
        }
        audioTrack = null
    }

    private fun handleBinaryMessage(bytes: ByteArray) {
        if (bytes.size < 9) return
        val type = bytes[0].toInt() and 0xFF
        if (type != 4) return // only handling player audio chunks (type 4)

        val buffer = ByteBuffer.wrap(bytes, 1, 8).order(ByteOrder.BIG_ENDIAN)
        val timestampUs = buffer.long
        val pcm = bytes.copyOfRange(9, bytes.size)

        if (pcm.isNotEmpty()) {
            chunkQueue.offer(AudioChunk(timestampUs, pcm))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Feeder thread: schedules playback of each chunk against the server's timestamp, converted
    // to local time via the Kalman time filter.
    // -----------------------------------------------------------------------------------------

    private fun startFeederThread() {
        running.set(true)
        feederThread = Thread({
            var underrunWarned = false
            while (running.get()) {
                val chunk = try {
                    chunkQueue.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }

                if (chunk == null) {
                    // Nothing to play right now. If we were mid-stream, this is a buffer underrun.
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING && !underrunWarned) {
                        Log.w("Sendspin", "Buffer underrun; pausing and will realign on next chunk")
                        audioTrack?.pause()
                        needsRealign = true
                        underrunWarned = true
                        _state.value = SendspinState.Buffering
                        sendClientState("error")
                    }
                    continue
                }

                val track = audioTrack
                if (track == null) {
                    continue // stream/start hasn't arrived yet; drop (shouldn't normally happen)
                }

                val targetLocalTimeUs = timeFilter.computeClientTime(chunk.targetServerTimeUs) - staticDelayMs * 1000L
                val nowUs = nowMicros()
                val waitUs = targetLocalTimeUs - nowUs

                if (!needsRealign && waitUs < -150_000) {
                    // More than 150ms late and we're already mid-stream: drop to maintain sync.
                    continue
                }

                if (needsRealign) {
                    if (waitUs > 0) {
                        try {
                            TimeUnit.MICROSECONDS.sleep(waitUs.coerceAtMost(10_000_000L))
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    try {
                        track.play()
                    } catch (e: Exception) {
                        Log.e("Sendspin", "AudioTrack.play() failed: ${e.message}")
                    }
                    needsRealign = false
                    underrunWarned = false
                    _state.value = SendspinState.Playing
                    sendClientState("synchronized")
                }

                try {
                    track.write(chunk.pcm, 0, chunk.pcm.size)
                } catch (e: Exception) {
                    Log.e("Sendspin", "AudioTrack.write() failed: ${e.message}")
                }
            }
        }, "SendspinFeeder")
        feederThread?.isDaemon = true
        feederThread?.start()
    }
}
