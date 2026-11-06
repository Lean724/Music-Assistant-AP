package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.api.MusicAssistantClient
import com.example.data.*
import com.example.playback.LocalPlayerManager
import com.example.playback.PlaybackNotificationManager
import com.example.playback.sendspin.SendspinClient
import com.example.playback.sendspin.SendspinState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val context = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        application.applicationContext.createAttributionContext("default")
    } else {
        application.applicationContext
    }
    private val configManager = ServerConfigManager(context)
    private val client = MusicAssistantClient()
    private val playerManager = LocalPlayerManager(context)
    private val notificationManager = PlaybackNotificationManager(context)
    private val sendspinClient = SendspinClient()

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackNotificationManager.ACTION_PLAY_PAUSE -> {
                    togglePlayPause()
                }
                PlaybackNotificationManager.ACTION_NEXT -> {
                    skipNext()
                }
                PlaybackNotificationManager.ACTION_PREVIOUS -> {
                    skipPrevious()
                }
            }
        }
    }

    // Server Configuration state
    val host = MutableStateFlow(configManager.host)
    val port = MutableStateFlow(configManager.port)
    val username = MutableStateFlow(configManager.username)
    val password = MutableStateFlow(configManager.password)
    val token = MutableStateFlow(configManager.token)
    val useDemoMode = MutableStateFlow(configManager.useDemoMode)
    val accentColor = MutableStateFlow(configManager.accentColor)

    // Local player (Sendspin) settings/state
    val localPlayerEnabled = MutableStateFlow(configManager.localPlayerEnabled)
    val localPlayerName = MutableStateFlow(configManager.localPlayerName)
    val sendspinPort = MutableStateFlow(configManager.sendspinPort)
    val sendspinState: StateFlow<SendspinState> = sendspinClient.state
    
    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection

    private val _connectionTestResult = MutableStateFlow("")
    val connectionTestResult: StateFlow<String> = _connectionTestResult

    // Library State
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Playback State
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    // These now reflect EITHER local playback (demo mode / fallback) OR the live state of a
    // real Music Assistant player queue ("remote control" mode), depending on _isRemoteSession.
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionSec = MutableStateFlow(0)
    val currentPositionSec: StateFlow<Int> = _currentPositionSec

    private val _durationSec = MutableStateFlow(0)
    val durationSec: StateFlow<Int> = _durationSec

    // True once we have a live, authenticated WebSocket session against a real MA server,
    // meaning play/pause/next/etc. control the actual player instead of local playback.
    private val _isRemoteSession = MutableStateFlow(false)
    val isRemoteSession: StateFlow<Boolean> = _isRemoteSession

    // Last-known live queue state per player/queue id (queue_id == player_id in MA), kept up to
    // date by push events so switching the active player shows its state instantly.
    private val queueStateCache = java.util.concurrent.ConcurrentHashMap<String, QueueState>()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _repeatEnabled = MutableStateFlow(false)
    val repeatEnabled: StateFlow<Boolean> = _repeatEnabled

    private val _volume = MutableStateFlow(configManager.activePlayerId.hashCode().coerceIn(10, 100))
    val volume: StateFlow<Int> = _volume

    private val _availablePlayers = MutableStateFlow<List<Player>>(emptyList())
    val availablePlayers: StateFlow<List<Player>> = _availablePlayers

    private val _activePlayer = MutableStateFlow<Player?>(null)
    val activePlayer: StateFlow<Player?> = _activePlayer

    // Playback Queue
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private var currentQueueIndex = -1

    // Search query & results
    val searchQuery = MutableStateFlow("")
    
    // Derived Search Results flow
    val searchResults: StateFlow<List<Track>> = combine(searchQuery, _tracks) { query, allTracks ->
        if (query.isBlank()) {
            emptyList()
        } else {
            allTracks.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
            }
        }
    }.let { flow ->
        val state = MutableStateFlow<List<Track>>(emptyList())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state
    }

    // High Fidelity Mock Data
    private val mockArtists = listOf(
        Artist("a1", "Lo-Fi Dreamer", "https://images.unsplash.com/photo-1516280440614-37939bbacd6a?auto=format&fit=crop&w=300&q=80"),
        Artist("a2", "Sunrise Cafe", "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=300&q=80"),
        Artist("a3", "Cozy Keys", "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=300&q=80"),
        Artist("a4", "Deep Focus", "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?auto=format&fit=crop&w=300&q=80"),
        Artist("a5", "Sunny Days", "https://images.unsplash.com/photo-1487180142328-054b783fc471?auto=format&fit=crop&w=300&q=80")
    )

    private val mockAlbums = listOf(
        Album("al1", "Midnight Memories", "Lo-Fi Dreamer", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=300&q=80"),
        Album("al2", "Acoustic Sunsets", "Sunrise Cafe", "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=300&q=80"),
        Album("al3", "Rainy Coffee Shop", "Cozy Keys", "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=300&q=80"),
        Album("al4", "Study Session Vol. 1", "Deep Focus", "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?auto=format&fit=crop&w=300&q=80")
    )

    private val mockPlaylists = listOf(
        Playlist("p1", "Chill Lofi Study Beats", "https://images.unsplash.com/photo-1516280440614-37939bbacd6a?auto=format&fit=crop&w=300&q=80", 4),
        Playlist("p2", "Morning Acoustic Walk", "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=300&q=80", 2),
        Playlist("p3", "Rainy Night Moods", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=300&q=80", 3)
    )

    private val mockTracks = listOf(
        Track(
            id = "t1",
            name = "Midnight Chill",
            artist = "Lo-Fi Dreamer",
            album = "Midnight Memories",
            durationSec = 372,
            image = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        ),
        Track(
            id = "t2",
            name = "Golden Hour",
            artist = "Sunrise Cafe",
            album = "Acoustic Sunsets",
            durationSec = 423,
            image = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"
        ),
        Track(
            id = "t3",
            name = "Coffee Shop Jazz",
            artist = "Cozy Keys",
            album = "Rainy Coffee Shop",
            durationSec = 302,
            image = "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3"
        ),
        Track(
            id = "t4",
            name = "Rainy Night Study",
            artist = "Deep Focus",
            album = "Study Session Vol. 1",
            durationSec = 318,
            image = "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3"
        ),
        Track(
            id = "t5",
            name = "Afternoon Walk",
            artist = "Sunny Days",
            album = "Acoustic Sunsets",
            durationSec = 256,
            image = "https://images.unsplash.com/photo-1487180142328-054b783fc471?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-10.mp3"
        ),
        Track(
            id = "t6",
            name = "Summer Breeze",
            artist = "Sunny Days",
            album = "Midnight Memories",
            durationSec = 224,
            image = "https://images.unsplash.com/photo-1516280440614-37939bbacd6a?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-12.mp3"
        ),
        Track(
            id = "t7",
            name = "Moonlight Walk",
            artist = "Lo-Fi Dreamer",
            album = "Midnight Memories",
            durationSec = 211,
            image = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=300&q=80",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-15.mp3"
        )
    )

    private val mockPlayers = listOf(
        Player("p_local", "Smart Speaker Salon", "idle", 65, null),
        Player("p_chromecast", "TV Android Bedroom", "idle", 40, null),
        Player("p_phone", "Dispositivo Local (Celular)", "idle", 80, null)
    )

    init {
        // Register BroadcastReceiver for notification controls
        val filter = IntentFilter().apply {
            addAction(PlaybackNotificationManager.ACTION_PLAY_PAUSE)
            addAction(PlaybackNotificationManager.ACTION_NEXT)
            addAction(PlaybackNotificationManager.ACTION_PREVIOUS)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(playbackReceiver, filter)
        }

        // Collect current track and playback state to keep the notification updated
        viewModelScope.launch {
            combine(currentTrack, isPlaying) { track, playing ->
                track to playing
            }.collect { (track, playing) ->
                if (track != null) {
                    notificationManager.showNotification(track, playing)
                } else {
                    notificationManager.cancelNotification()
                }
            }
        }

        // Mirror local (simulated/in-app) playback into the shared flows only while NOT
        // controlling a real remote player; in remote-session mode these flows are instead
        // driven by live MA queue events (see handleMassEvent / applyQueueState).
        viewModelScope.launch {
            playerManager.isPlaying.collect { if (!_isRemoteSession.value) _isPlaying.value = it }
        }
        viewModelScope.launch {
            playerManager.currentPositionSec.collect { if (!_isRemoteSession.value) _currentPositionSec.value = it }
        }
        viewModelScope.launch {
            playerManager.durationSec.collect { if (!_isRemoteSession.value) _durationSec.value = it }
        }

        // Music Assistant only pushes "elapsed_time" snapshots periodically (not every second),
        // so tick the position forward locally once per second while a remote player is playing.
        // Any real queue_updated/queue_time_updated event (handled in applyQueueState) overwrites
        // this with the authoritative value, keeping drift in check.
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (_isRemoteSession.value && _isPlaying.value) {
                    val dur = _durationSec.value
                    val next = _currentPositionSec.value + 1
                    _currentPositionSec.value = if (dur > 0) next.coerceAtMost(dur) else next
                }
            }
        }

        // Connection watcher & Auto-reconnection loop
        viewModelScope.launch {
            var isConnecting = false
            while (true) {
                delay(5000) // check every 5 seconds
                if (!useDemoMode.value && !client.isSessionConnected && host.value.isNotEmpty() && port.value.isNotEmpty() && !isConnecting) {
                    isConnecting = true
                    Log.d("MusicViewModel", "Connection watcher: Session is not connected, attempting auto-reconnect...")
                    try {
                        val resolvedToken = client.resolveToken(host.value, port.value, username.value, password.value, token.value)
                        if (resolvedToken != null) {
                            if (resolvedToken != token.value) {
                                configManager.token = resolvedToken
                                token.value = resolvedToken
                            }
                            client.setSessionEventListener { event, objectId, data -> handleMassEvent(event, objectId, data) }
                            val connected = client.connectSession(host.value, port.value, resolvedToken)
                            _isRemoteSession.value = connected
                            if (connected) {
                                Log.i("MusicViewModel", "Auto-reconnect successful!")
                                client.getQueues(host.value, port.value).forEach { queueStateCache[it.queueId] = it }
                                _activePlayer.value?.let { 
                                    applyQueueState(queueStateCache[it.id])
                                    fetchRemoteQueue()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Auto-reconnect failed: ${e.message}")
                    } finally {
                        isConnecting = false
                    }
                }
            }
        }

        loadLibrary()
    }

    // Load library items depending on Demo Mode
    fun loadLibrary() {
        _isLoading.value = true
        viewModelScope.launch {
            if (useDemoMode.value) {
                if (_isRemoteSession.value) {
                    client.disconnectSession()
                    _isRemoteSession.value = false
                }
                _tracks.value = mockTracks
                _artists.value = mockArtists
                _albums.value = mockAlbums
                _playlists.value = mockPlaylists
                val demoList = listOf(LOCAL_PHONE_PLAYER) + mockPlayers
                _availablePlayers.value = demoList
                val savedPlayerId = configManager.activePlayerId
                val selected = demoList.find { it.id == savedPlayerId } ?: LOCAL_PHONE_PLAYER
                setActivePlayer(selected)
                syncLocalPlayerConnection()
                _isLoading.value = false
            } else {
                try {
                    // Resolve the bearer token once (logging in via username/password if needed)
                    // and persist it so we don't re-login on every single library call.
                    val resolvedToken = client.resolveToken(host.value, port.value, username.value, password.value, token.value)
                        ?: throw Exception("No se pudo autenticar con el servidor Music Assistant.")

                    if (resolvedToken != token.value) {
                        configManager.token = resolvedToken
                        token.value = resolvedToken
                    }

                    val realTracks = client.getTracks(host.value, port.value, "", "", resolvedToken)
                    val realArtists = client.getArtists(host.value, port.value, "", "", resolvedToken)
                    val realAlbums = client.getAlbums(host.value, port.value, "", "", resolvedToken)
                    val realPlaylists = client.getPlaylists(host.value, port.value, "", "", resolvedToken)
                    val realPlayers = client.getPlayers(host.value, port.value, "", "", resolvedToken)

                    _tracks.value = realTracks
                    _artists.value = realArtists
                    _albums.value = realAlbums
                    _playlists.value = realPlaylists
                    
                    val combinedPlayers = listOf(LOCAL_PHONE_PLAYER) + realPlayers
                    _availablePlayers.value = combinedPlayers
                    val savedPlayerId = configManager.activePlayerId
                    val selected = combinedPlayers.find { it.id == savedPlayerId } ?: LOCAL_PHONE_PLAYER
                    setActivePlayer(selected)

                    // Open (or reuse) the persistent WebSocket session for real-time remote control.
                    client.setSessionEventListener { event, objectId, data -> handleMassEvent(event, objectId, data) }
                    val connected = client.connectSession(host.value, port.value, resolvedToken)
                    _isRemoteSession.value = connected && selected.id != "local_phone"

                    // Connect (or keep disconnected) the real Sendspin player on this phone.
                    syncLocalPlayerConnection()
                    if (connected && selected.id != "local_phone") {
                        // Seed the queue state cache and reflect the active player's current state.
                        client.getQueues(host.value, port.value).forEach { queueStateCache[it.queueId] = it }
                        applyQueueState(queueStateCache[selected.id])
                        fetchRemoteQueue()
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Failed to fetch real data: ${e.message}")
                    _tracks.value = emptyList()
                    _artists.value = emptyList()
                    _albums.value = emptyList()
                    _playlists.value = emptyList()
                    val fallbackList = listOf(LOCAL_PHONE_PLAYER)
                    _availablePlayers.value = fallbackList
                    setActivePlayer(LOCAL_PHONE_PLAYER)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    /** Handles a live push event from Music Assistant (player_updated, queue_updated, queue_time_updated, ...). */
    private fun handleMassEvent(event: String, objectId: String?, data: JSONObject?) {
        if (event == "session_disconnected") {
            _isRemoteSession.value = false
            return
        }
        if (data == null) return
        try {
            when (event) {
                "queue_updated", "queue_time_updated", "queue_items_updated" -> {
                    val queueState = client.parseQueueStateObject(data, host.value, port.value) ?: return
                    queueStateCache[queueState.queueId] = queueState
                    if (queueState.queueId == _activePlayer.value?.id) {
                        applyQueueState(queueState)
                        if (event == "queue_updated" || event == "queue_items_updated") {
                            fetchRemoteQueue()
                        }
                    }
                }
                "player_updated" -> {
                    val playerId = data.optString("player_id", objectId ?: return)
                    val state = data.optString("state", "idle")
                    val volume = data.optInt("volume_level", -1)
                    val isMuted = data.optBoolean("is_muted", false)

                    _availablePlayers.value = _availablePlayers.value.map { p ->
                        if (p.id == playerId) {
                            p.copy(
                                state = state,
                                volume = if (volume >= 0) volume else p.volume,
                                isMuted = isMuted
                            )
                        } else p
                    }

                    if (playerId == _activePlayer.value?.id) {
                        _activePlayer.value = _activePlayer.value?.copy(
                            state = state,
                            volume = if (volume >= 0) volume else _activePlayer.value?.volume ?: 50,
                            isMuted = isMuted
                        )
                        if (volume >= 0) _volume.value = volume
                        _isPlaying.value = state == "playing"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Error handling MA event '$event': ${e.message}")
        }
    }

    /** Reflects a QueueState into the UI's playback flows (remote-control mode). */
    private fun applyQueueState(queueState: QueueState?) {
        if (queueState == null) return
        _currentTrack.value = queueState.currentTrack ?: _currentTrack.value
        _isPlaying.value = queueState.state == "playing"
        _currentPositionSec.value = queueState.elapsedTimeSec
        // Only overwrite duration when this update actually carried track info; a bare
        // queue_time_updated event (elapsed_time only) must not zero out the known duration.
        queueState.currentTrack?.let { _durationSec.value = it.durationSec }
        _shuffleEnabled.value = queueState.shuffleEnabled
        _repeatEnabled.value = queueState.repeatMode != "off"
    }

    fun fetchRemoteQueue() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch {
                try {
                    val items = client.getQueueItems(host.value, port.value, queueId)
                    _queue.value = items
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error fetching remote queue: ${e.message}")
                }
            }
        }
    }

    // Settings screen actions
    fun saveSettings(newHost: String, newPort: String, newUser: String, newPass: String, newToken: String, newDemo: Boolean, newAccentColor: String) {
        configManager.host = newHost
        configManager.port = newPort
        configManager.username = newUser
        configManager.password = newPass
        configManager.token = newToken
        configManager.useDemoMode = newDemo
        configManager.accentColor = newAccentColor

        host.value = newHost
        port.value = newPort
        username.value = newUser
        password.value = newPass
        token.value = newToken
        useDemoMode.value = newDemo
        accentColor.value = newAccentColor

        loadLibrary()
    }

    fun testConnection() {
        _isTestingConnection.value = true
        _connectionTestResult.value = ""
        viewModelScope.launch {
            client.testConnection(host.value, port.value, username.value, password.value, token.value) { success, msg ->
                _isTestingConnection.value = false
                _connectionTestResult.value = if (success) {
                    "✓ Conexión exitosa: $msg"
                } else {
                    "✗ Error de conexión: $msg"
                }
            }
        }
    }

    // Playback control
    fun playTrack(track: Track) {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            val mediaUri = track.uri ?: "library://track/${track.id}"
            viewModelScope.launch {
                try {
                    client.sendSessionCommand("player_queues/play_media", JSONObject().apply {
                        put("queue_id", queueId)
                        put("media", org.json.JSONArray().put(mediaUri))
                        put("option", "play")
                    })
                    // Optimistic local update; the authoritative state arrives via queue_updated event.
                    _currentTrack.value = track
                    _isPlaying.value = true
                    _currentPositionSec.value = 0
                    _durationSec.value = track.durationSec
                    fetchRemoteQueue()
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error playing media on remote session: ${e.message}")
                }
            }
            return
        }

        _currentTrack.value = track
        
        // Direct playback: clear current local queue and play
        val newQueue = listOf(track)
        _queue.value = newQueue
        currentQueueIndex = 0

        playerManager.setVolume(_volume.value)
        playerManager.play(track.streamUrl, track.durationSec, if (useDemoMode.value) null else token.value)
        updateActivePlayerState("playing", track.id)
    }

    fun togglePlayPause() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch { client.queuePlayPause(queueId) }
            return
        }

        val current = isPlaying.value
        if (current) {
            playerManager.pause()
            updateActivePlayerState("paused", _currentTrack.value?.id)
        } else {
            if (_currentTrack.value == null && _tracks.value.isNotEmpty()) {
                playTrack(_tracks.value.first())
            } else {
                playerManager.resume(_currentTrack.value?.durationSec ?: 180)
                updateActivePlayerState("playing", _currentTrack.value?.id)
            }
        }
    }

    fun skipNext() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch { client.queueNext(queueId) }
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return

        if (shuffleEnabled.value) {
            val randomIndex = (q.indices).random()
            currentQueueIndex = randomIndex
            playTrack(q[randomIndex])
        } else {
            currentQueueIndex = (currentQueueIndex + 1) % q.size
            playTrack(q[currentQueueIndex])
        }
    }

    fun skipPrevious() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch { client.queuePrevious(queueId) }
            return
        }

        if (currentPositionSec.value > 3) {
            seekTo(0)
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return

        currentQueueIndex = if (currentQueueIndex - 1 < 0) q.size - 1 else currentQueueIndex - 1
        playTrack(q[currentQueueIndex])
    }

    fun seekTo(seconds: Int) {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch { client.queueSeek(queueId, seconds) }
            _currentPositionSec.value = seconds // optimistic, event will confirm
            return
        }
        playerManager.seekTo(seconds)
    }

    fun setVolume(vol: Int) {
        _volume.value = vol
        _activePlayer.value = _activePlayer.value?.copy(volume = vol)

        if (_isRemoteSession.value) {
            val playerId = _activePlayer.value?.id ?: return
            viewModelScope.launch { client.playerSetVolume(playerId, vol) }
        } else {
            playerManager.setVolume(vol)
        }
    }

    fun toggleShuffle() {
        val newValue = !_shuffleEnabled.value
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch { client.queueSetShuffle(queueId, newValue) }
        }
        _shuffleEnabled.value = newValue
    }

    fun toggleRepeat() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            val newMode = if (_repeatEnabled.value) "off" else "all"
            viewModelScope.launch { client.queueSetRepeat(queueId, newMode) }
            _repeatEnabled.value = !_repeatEnabled.value
            return
        }
        _repeatEnabled.value = !_repeatEnabled.value
    }

    fun setActivePlayer(player: Player) {
        _activePlayer.value = player
        configManager.activePlayerId = player.id
        _volume.value = player.volume

        if (player.id == "local_phone") {
            _isRemoteSession.value = false
        } else {
            if (!useDemoMode.value && client.isSessionConnected) {
                _isRemoteSession.value = true
                applyQueueState(queueStateCache[player.id])
                fetchRemoteQueue()
            } else {
                _isRemoteSession.value = false
            }
        }
    }

    /**
     * Enables/disables and configures this phone as a real Music Assistant player via the
     * Sendspin protocol. Once connected, the phone registers itself with the server and will
     * show up in the normal player list (players/all) like any other speaker — from that point
     * on, selecting it and controlling playback works through the exact same remote-session code
     * (play/pause/volume/queue) used for every other player.
     */
    fun setLocalPlayerConfig(enabled: Boolean, name: String, sendspinPortValue: String) {
        configManager.localPlayerEnabled = enabled
        configManager.localPlayerName = name
        configManager.sendspinPort = sendspinPortValue
        localPlayerEnabled.value = enabled
        localPlayerName.value = name
        sendspinPort.value = sendspinPortValue
        syncLocalPlayerConnection()
    }

    /** Connects or disconnects the Sendspin client to match current settings. */
    private fun syncLocalPlayerConnection() {
        if (useDemoMode.value || host.value.isEmpty()) {
            sendspinClient.disconnect("user_request")
            return
        }
        if (localPlayerEnabled.value) {
            sendspinClient.connect(host.value, sendspinPort.value, configManager.sendspinClientId, localPlayerName.value)
        } else {
            sendspinClient.disconnect("user_request")
        }
    }

    // Queue management
    fun addToQueue(track: Track) {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            val mediaUri = track.uri ?: "library://track/${track.id}"
            viewModelScope.launch { client.sendSessionCommand("player_queues/play_media", JSONObject().apply {
                put("queue_id", queueId)
                put("media", org.json.JSONArray().put(mediaUri))
                put("option", "add")
            }) }
            return
        }

        val currentQueue = _queue.value.toMutableList()
        if (!currentQueue.any { it.id == track.id }) {
            currentQueue.add(track)
            _queue.value = currentQueue
            if (currentQueueIndex == -1) {
                currentQueueIndex = 0
            }
        }
    }

    fun removeFromQueue(track: Track) {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch {
                try {
                    client.sendSessionCommand("player_queues/delete_item", JSONObject().apply {
                        put("queue_id", queueId)
                        put("item_id_or_index", track.id)
                    })
                    fetchRemoteQueue()
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error removing track from remote queue: ${e.message}")
                }
            }
            return
        }

        val currentQueue = _queue.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == track.id }
        if (index != -1) {
            currentQueue.removeAt(index)
            _queue.value = currentQueue
            if (index == currentQueueIndex) {
                if (currentQueue.isEmpty()) {
                    playerManager.stop()
                    _currentTrack.value = null
                    currentQueueIndex = -1
                } else {
                    currentQueueIndex = currentQueueIndex.coerceAtMost(currentQueue.size - 1)
                    playTrack(currentQueue[currentQueueIndex])
                }
            } else if (index < currentQueueIndex) {
                currentQueueIndex--
            }
        }
    }

    fun clearQueue() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch {
                try {
                    client.sendSessionCommand("player_queues/clear", JSONObject().apply {
                        put("queue_id", queueId)
                    })
                    fetchRemoteQueue()
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error clearing remote queue: ${e.message}")
                }
            }
            return
        }
        playerManager.stop()
        _currentTrack.value = null
        _queue.value = emptyList()
        currentQueueIndex = -1
        updateActivePlayerState("idle", null)
    }

    private fun updateActivePlayerState(state: String, trackId: String?) {
        _activePlayer.value = _activePlayer.value?.copy(state = state, currentTrackId = trackId)
        _availablePlayers.value = _availablePlayers.value.map {
            if (it.id == _activePlayer.value?.id) {
                it.copy(state = state, currentTrackId = trackId)
            } else {
                it
            }
        }
    }

    fun stopPlayback() {
        if (_isRemoteSession.value) {
            val queueId = _activePlayer.value?.id ?: return
            viewModelScope.launch {
                try {
                    client.queueStop(queueId)
                    fetchRemoteQueue()
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error stopping remote queue: ${e.message}")
                }
            }
            return
        }
        playerManager.stop()
        _isPlaying.value = false
        _currentTrack.value = null
        updateActivePlayerState("idle", null)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(playbackReceiver)
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Error unregistering receiver: ${e.message}")
        }
        notificationManager.cancelNotification()
        playerManager.release()
        client.disconnectSession()
        sendspinClient.disconnect("shutdown")
    }

    companion object {
        val LOCAL_PHONE_PLAYER = Player(
            id = "local_phone",
            name = "Mi Teléfono (Este Dispositivo)",
            state = "idle",
            volume = 75
        )
    }
}
