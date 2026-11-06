package com.example.api

import android.util.Log
import com.example.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for Music Assistant 2.9.4+.
 *
 * As of API Schema Version 28, authentication is mandatory:
 *  - WebSocket: ws://host:port/ws  (auth command MUST be the first message sent)
 *  - HTTP RPC:  POST http://host:port/api  with header "Authorization: Bearer <token>"
 *  - Login:     POST http://host:port/auth/login  ->  { token, user }
 *
 * If the caller has no token yet but does have username/password, this client
 * will transparently log in first (HTTP) and use the resulting bearer token
 * for the WebSocket "auth" command and any subsequent calls.
 */
class MusicAssistantClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Dedicated client for the persistent "remote control" session WebSocket.
    // IMPORTANT: readTimeout must be disabled (0) here. A finite read timeout closes the
    // socket if no frame arrives within that window; since MA doesn't necessarily push a
    // frame every few seconds, a short read timeout (even one longer than the ping interval)
    // will intermittently kill the connection. Liveness is instead guaranteed by pingInterval,
    // which sends WebSocket ping frames and expects pongs back.
    private val sessionHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // --- Persistent "remote control" session state ---
    // Unlike the one-shot executeWebSocketCommand() used for library browsing, this keeps a
    // single WebSocket connection open so we can both send player/queue commands AND receive
    // live push events (player_updated, queue_updated, queue_time_updated, ...) without polling.
    private var sessionSocket: WebSocket? = null
    private val sessionMsgCounter = java.util.concurrent.atomic.AtomicInteger(1)
    private val sessionPendingCommands =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JSONObject?>>()
    private var sessionEventListener: ((event: String, objectId: String?, data: JSONObject?) -> Unit)? = null
    @Volatile private var sessionAuthenticated = false

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------------
    // URL helpers
    // ---------------------------------------------------------------------

    /** Normalizes "host" (which may include protocol/port/path) + "port" into protocol + authority. */
    private fun splitHostAndProtocol(host: String, port: String): Pair<String, String>? {
        if (host.isEmpty()) return null
        var formattedHost = host.trim()
        while (formattedHost.endsWith("/")) {
            formattedHost = formattedHost.substring(0, formattedHost.length - 1)
        }

        val isHttps = formattedHost.startsWith("https://", ignoreCase = true)
        var hostWithoutProtocol = formattedHost
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("HTTPS://")
            .removePrefix("HTTP://")

        val trimmedPort = port.trim()
        if (trimmedPort.isNotEmpty()) {
            val firstSlash = hostWithoutProtocol.indexOf('/')
            val authority = if (firstSlash != -1) hostWithoutProtocol.substring(0, firstSlash) else hostWithoutProtocol
            val path = if (firstSlash != -1) hostWithoutProtocol.substring(firstSlash) else ""

            if (!authority.contains(":")) {
                hostWithoutProtocol = "$authority:$trimmedPort$path"
            }
        }

        return Pair(if (isHttps) "https" else "http", hostWithoutProtocol)
    }

    private fun getWsUrl(host: String, port: String): String? {
        val (protocol, authority) = splitHostAndProtocol(host, port) ?: return null
        val wsProtocol = if (protocol == "https") "wss" else "ws"
        return "$wsProtocol://$authority/ws"
    }

    private fun getHttpBaseUrl(host: String, port: String): String? {
        val (protocol, authority) = splitHostAndProtocol(host, port) ?: return null
        return "$protocol://$authority"
    }

    private fun getApiRpcUrl(host: String, port: String): String? {
        val base = getHttpBaseUrl(host, port) ?: return null
        return "$base/api"
    }

    /** Best-effort direct stream URL for a track (not part of the documented RPC, kept for local playback fallback). */
    private fun getStreamUrl(host: String, port: String, trackId: String): String? {
        val base = getHttpBaseUrl(host, port) ?: return null
        return "$base/stream/track/$trackId"
    }

    /**
     * Resolves a usable image URL from a MediaItem's "metadata.images[0]" entry.
     * Music Assistant images don't carry a ready-to-use "url" — they carry a "path" (which may
     * be a local file path, provider-relative path, or a remote URL) plus a "provider" id, and
     * must be routed through the server's image proxy: /imageproxy?provider=X&path=Y&size=..&fmt=..
     * (Falls back to using "path"/"url" directly only if no provider id is present and it's
     * already an absolute http(s) URL.)
     */
    private fun resolveImageUrl(host: String, port: String, imagesArray: JSONArray?): String? {
        if (imagesArray == null || imagesArray.length() == 0) return null
        val img = imagesArray.optJSONObject(0) ?: return null
        val path = img.optString("path", img.optString("url", ""))
        if (path.isEmpty()) return null

        val provider = img.optString("provider", "")
        if (provider.isNotEmpty()) {
            val base = getHttpBaseUrl(host, port) ?: return path.takeIf { it.startsWith("http") }
            val encodedPath = try {
                java.net.URLEncoder.encode(path, "UTF-8")
            } catch (e: Exception) {
                path
            }
            return "$base/imageproxy?provider=$provider&path=$encodedPath&size=500&fmt=jpeg"
        }

        return path.takeIf { it.startsWith("http") }
    }

    // ---------------------------------------------------------------------
    // Authentication
    // ---------------------------------------------------------------------

    /**
     * Returns a usable bearer token:
     *  - the provided [token] if non-empty
     *  - otherwise, logs in with [user]/[pass] via HTTP and returns the resulting token
     *  - otherwise null
     */
    suspend fun resolveToken(host: String, port: String, user: String, pass: String, token: String): String? {
        if (token.isNotEmpty()) return token
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            return login(host, port, user, pass)
        }
        return null
    }

    /** POST /auth/login -> returns the access token, or null on failure. */
    suspend fun login(host: String, port: String, user: String, pass: String): String? {
        val baseUrl = getHttpBaseUrl(host, port) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("credentials", JSONObject().apply {
                        put("username", user)
                        put("password", pass)
                    })
                }.toString().toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$baseUrl/auth/login")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        Log.e("MusicAssistantClient", "Login failed: HTTP ${response.code}")
                        return@use null
                    }
                    val obj = JSONObject(responseBody)
                    if (obj.optBoolean("success", true) && (obj.has("token") || obj.has("access_token"))) {
                        if (obj.has("token")) obj.getString("token") else obj.getString("access_token")
                    } else {
                        Log.e("MusicAssistantClient", "Login failed: ${obj.optString("error", "unknown error")}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicAssistantClient", "Exception during login: ${e.message}")
                null
            }
        }
    }

    // ---------------------------------------------------------------------
    // Connection test
    // ---------------------------------------------------------------------

    fun testConnection(
        host: String,
        port: String,
        user: String,
        pass: String,
        token: String,
        onResult: (Boolean, String) -> Unit
    ) {
        client.dispatcher.executorService.submit {
            kotlinx.coroutines.runBlocking {
                val resolvedToken = resolveToken(host, port, user, pass, token)
                if (resolvedToken == null) {
                    onResult(false, "No se pudo autenticar: verifica usuario/contraseña o token.")
                    return@runBlocking
                }

                val wsUrl = getWsUrl(host, port)
                if (wsUrl == null) {
                    onResult(false, "URL del servidor inválida.")
                    return@runBlocking
                }

                var isCompleted = false
                val request = Request.Builder().url(wsUrl).build()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d("MusicAssistantClient", "Test connection WebSocket opened")
                        val authMsg = JSONObject().apply {
                            put("message_id", "auth-test")
                            put("command", "auth")
                            put("args", JSONObject().apply { put("token", resolvedToken) })
                        }
                        webSocket.send(authMsg.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (isCompleted) return
                        try {
                            val obj = JSONObject(text)
                            if (obj.optString("message_id") == "auth-test") {
                                val result = obj.optJSONObject("result")
                                if (result != null && result.optBoolean("authenticated", false)) {
                                    isCompleted = true
                                    onResult(true, "Autenticado y conectado exitosamente!")
                                } else {
                                    isCompleted = true
                                    val err = obj.optJSONObject("error")?.optString("message")
                                        ?: "Token de autenticación inválido."
                                    onResult(false, err)
                                }
                                webSocket.close(1000, "Test done")
                            }
                        } catch (e: Exception) {
                            Log.e("MusicAssistantClient", "Error parsing test message: ${e.message}")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!isCompleted) {
                            isCompleted = true
                            onResult(false, t.localizedMessage ?: "No se pudo establecer la conexión de WebSocket.")
                        }
                    }
                })

                try {
                    Thread.sleep(6000)
                    if (!isCompleted) {
                        isCompleted = true
                        ws.close(1000, "Timeout")
                        onResult(false, "Tiempo de espera agotado al conectar.")
                    }
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Library / players
    // ---------------------------------------------------------------------

    suspend fun getTracks(host: String, port: String, user: String, pass: String, token: String): List<Track> {
        return try {
            val resolvedToken = resolveToken(host, port, user, pass, token) ?: return emptyList()
            val args = JSONObject().apply { put("limit", 500) }
            val responseString = executeWebSocketCommand(host, port, "music/tracks/library_items", resolvedToken, args) ?: return emptyList()
            val cleanJson = getCleanJson(responseString)
            parseTracks(cleanJson, host, port)
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error fetching tracks: ${e.message}")
            emptyList()
        }
    }

    suspend fun getArtists(host: String, port: String, user: String, pass: String, token: String): List<Artist> {
        return try {
            val resolvedToken = resolveToken(host, port, user, pass, token) ?: return emptyList()
            val args = JSONObject().apply { put("limit", 500) }
            val responseString = executeWebSocketCommand(host, port, "music/artists/library_items", resolvedToken, args) ?: return emptyList()
            val cleanJson = getCleanJson(responseString)
            parseArtists(cleanJson, host, port)
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error fetching artists: ${e.message}")
            emptyList()
        }
    }

    suspend fun getAlbums(host: String, port: String, user: String, pass: String, token: String): List<Album> {
        return try {
            val resolvedToken = resolveToken(host, port, user, pass, token) ?: return emptyList()
            val args = JSONObject().apply { put("limit", 500) }
            val responseString = executeWebSocketCommand(host, port, "music/albums/library_items", resolvedToken, args) ?: return emptyList()
            val cleanJson = getCleanJson(responseString)
            parseAlbums(cleanJson, host, port)
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error fetching albums: ${e.message}")
            emptyList()
        }
    }

    suspend fun getPlaylists(host: String, port: String, user: String, pass: String, token: String): List<Playlist> {
        return try {
            val resolvedToken = resolveToken(host, port, user, pass, token) ?: return emptyList()
            val args = JSONObject().apply { put("limit", 500) }
            val responseString = executeWebSocketCommand(host, port, "music/playlists/library_items", resolvedToken, args) ?: return emptyList()
            val cleanJson = getCleanJson(responseString)
            parsePlaylists(cleanJson, host, port)
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error fetching playlists: ${e.message}")
            emptyList()
        }
    }

    suspend fun getPlayers(host: String, port: String, user: String, pass: String, token: String): List<Player> {
        return try {
            // If the persistent remote-control session is active, request directly over it (ultra-fast)
            if (isSessionConnected) {
                val sessionRes = sendSessionCommand("players/all")
                if (sessionRes != null) {
                    val parsed = parsePlayers(sessionRes.toString())
                    if (parsed.isNotEmpty()) return parsed
                }
            }
            val resolvedToken = resolveToken(host, port, user, pass, token) ?: return emptyList()
            val responseString = executeWebSocketCommand(host, port, "players/all", resolvedToken) ?: return emptyList()
            val cleanJson = getCleanJson(responseString)
            parsePlayers(cleanJson)
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error fetching players: ${e.message}")
            emptyList()
        }
    }

    /** Play a media URI (e.g. "library://track/456") on the given player/queue. */
    suspend fun playMedia(host: String, port: String, user: String, pass: String, token: String, queueId: String, mediaUri: String): Boolean {
        return try {
            val resolvedToken = resolveToken(host, port, user, pass, token) ?: return false
            val args = JSONObject().apply {
                put("queue_id", queueId)
                put("media", JSONArray().put(mediaUri))
            }
            val responseString = executeWebSocketCommand(host, port, "player_queues/play_media", resolvedToken, args)
            responseString != null
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error playing media: ${e.message}")
            false
        }
    }

    // =======================================================================================
    // Remote-control session: a single, long-lived, authenticated WebSocket connection used to
    // send player/queue commands and to receive live push events for a true "remote control"
    // experience (no polling needed).
    // =======================================================================================

    fun setSessionEventListener(listener: ((event: String, objectId: String?, data: JSONObject?) -> Unit)?) {
        sessionEventListener = listener
    }

    val isSessionConnected: Boolean
        get() = sessionAuthenticated && sessionSocket != null

    /** Opens the persistent session (or reuses it if already connected+authenticated). */
    suspend fun connectSession(host: String, port: String, token: String): Boolean {
        if (isSessionConnected) return true
        disconnectSession()

        val wsUrl = getWsUrl(host, port) ?: return false
        val authDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        val request = Request.Builder().url(wsUrl).build()
        val socket = sessionHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("MusicAssistantSession", "Session WebSocket opened")
                val authMsg = JSONObject().apply {
                    put("message_id", "session-auth")
                    put("command", "auth")
                    put("args", JSONObject().apply { put("token", token) })
                }
                webSocket.send(authMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)

                    // Push event (no message_id correlation): forward to listener
                    if (obj.has("event")) {
                        val eventType = obj.optString("event")
                        val objectId = if (obj.has("object_id") && !obj.isNull("object_id")) obj.optString("object_id") else null
                        val data = obj.optJSONObject("data")
                        sessionEventListener?.invoke(eventType, objectId, data)
                        return
                    }

                    val msgId = obj.optString("message_id", obj.optString("id", ""))
                    if (msgId.isEmpty()) return // e.g. initial server_info message, ignore

                    if (msgId == "session-auth") {
                        val result = obj.optJSONObject("result")
                        if (result != null && result.optBoolean("authenticated", false)) {
                            sessionAuthenticated = true
                            if (!authDeferred.isCompleted) authDeferred.complete(true)
                        } else {
                            sessionAuthenticated = false
                            if (!authDeferred.isCompleted) authDeferred.complete(false)
                        }
                        return
                    }

                    // Resolve a pending command, if any
                    sessionPendingCommands.remove(msgId)?.let { deferred ->
                        if (obj.has("error")) {
                            Log.e("MusicAssistantSession", "Command error: ${obj.optJSONObject("error")}")
                            deferred.complete(null)
                        } else {
                            deferred.complete(obj)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MusicAssistantSession", "Error parsing session message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("MusicAssistantSession", "Session WebSocket failure: ${t.message}")
                sessionAuthenticated = false
                sessionSocket = null
                if (!authDeferred.isCompleted) authDeferred.complete(false)
                // Fail any commands still waiting on this connection
                sessionPendingCommands.values.forEach { if (!it.isCompleted) it.complete(null) }
                sessionPendingCommands.clear()
                sessionEventListener?.invoke("session_disconnected", null, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sessionAuthenticated = false
                sessionSocket = null
                sessionEventListener?.invoke("session_disconnected", null, null)
            }
        })
        sessionSocket = socket

        return try {
            kotlinx.coroutines.withTimeout(8000) { authDeferred.await() }
        } catch (e: Exception) {
            Log.e("MusicAssistantSession", "Timed out authenticating session: ${e.message}")
            false
        }
    }

    fun disconnectSession() {
        sessionSocket?.close(1000, "Client disconnect")
        sessionSocket = null
        sessionAuthenticated = false
        sessionPendingCommands.values.forEach { if (!it.isCompleted) it.complete(null) }
        sessionPendingCommands.clear()
    }

    /** Sends a command over the persistent session and waits for its result (or null on error/timeout). */
    suspend fun sendSessionCommand(command: String, args: JSONObject = JSONObject()): JSONObject? {
        val socket = sessionSocket
        if (socket == null || !sessionAuthenticated) return null

        val msgId = "cmd_${sessionMsgCounter.getAndIncrement()}"
        val deferred = kotlinx.coroutines.CompletableDeferred<JSONObject?>()
        sessionPendingCommands[msgId] = deferred

        val cmdMsg = JSONObject().apply {
            put("message_id", msgId)
            put("command", command)
            put("args", args)
        }
        socket.send(cmdMsg.toString())

        return try {
            kotlinx.coroutines.withTimeout(8000) { deferred.await() }
        } catch (e: Exception) {
            sessionPendingCommands.remove(msgId)
            Log.e("MusicAssistantSession", "Command $command timed out: ${e.message}")
            null
        }
    }

    // --- Convenience player/queue remote-control commands (all use the persistent session) ---

    suspend fun getQueues(host: String, port: String): List<QueueState> {
        val result = sendSessionCommand("player_queues/all") ?: return emptyList()
        val resultValue = result.opt("result") ?: return emptyList()
        return parseQueueStates(resultValue.toString(), host, port)
    }

    suspend fun getQueueItems(host: String, port: String, queueId: String): List<Track> {
        val result = sendSessionCommand("player_queues/items", JSONObject().put("queue_id", queueId)) ?: return emptyList()
        val resultValue = result.opt("result") ?: return emptyList()
        return parseQueueItems(resultValue.toString(), host, port)
    }

    suspend fun queuePlayPause(queueId: String): Boolean =
        sendSessionCommand("player_queues/play_pause", JSONObject().put("queue_id", queueId)) != null

    suspend fun queueStop(queueId: String): Boolean =
        sendSessionCommand("player_queues/stop", JSONObject().put("queue_id", queueId)) != null

    suspend fun queueNext(queueId: String): Boolean =
        sendSessionCommand("player_queues/next", JSONObject().put("queue_id", queueId)) != null

    suspend fun queuePrevious(queueId: String): Boolean =
        sendSessionCommand("player_queues/previous", JSONObject().put("queue_id", queueId)) != null

    suspend fun queueSeek(queueId: String, positionSec: Int): Boolean =
        sendSessionCommand(
            "player_queues/seek",
            JSONObject().put("queue_id", queueId).put("position", positionSec)
        ) != null

    suspend fun queueSetShuffle(queueId: String, enabled: Boolean): Boolean =
        sendSessionCommand(
            "player_queues/shuffle",
            JSONObject().put("queue_id", queueId).put("shuffle_enabled", enabled)
        ) != null

    suspend fun queueSetRepeat(queueId: String, mode: String): Boolean =
        sendSessionCommand(
            "player_queues/repeat",
            JSONObject().put("queue_id", queueId).put("repeat_mode", mode)
        ) != null

    suspend fun playerSetVolume(playerId: String, volume: Int): Boolean =
        sendSessionCommand(
            "players/cmd/volume_set",
            JSONObject().put("player_id", playerId).put("volume_level", volume)
        ) != null

    suspend fun playMediaOnSession(queueId: String, mediaUri: String): Boolean =
        sendSessionCommand(
            "player_queues/play_media",
            JSONObject().put("queue_id", queueId).put("media", JSONArray().put(mediaUri))
        ) != null

    private fun getCleanJson(rawJson: String): String {
        try {
            if (rawJson.startsWith("[")) return rawJson
            val obj = JSONObject(rawJson)
            if (obj.has("result")) {
                val result = obj.get("result")
                return result.toString()
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error extracting result field: ${e.message}")
        }
        return rawJson
    }

    // ---------------------------------------------------------------------
    // WebSocket command execution
    // ---------------------------------------------------------------------

    /**
     * Opens a WebSocket, authenticates (auth MUST be the first message sent, per
     * Music Assistant 2.9.4), then sends [command] with [args] and returns the raw
     * JSON response (the full envelope, e.g. {"message_id":..,"result":..}).
     */
    private suspend fun executeWebSocketCommand(
        host: String,
        port: String,
        command: String,
        token: String,
        args: JSONObject = JSONObject()
    ): String? {
        val wsUrl = getWsUrl(host, port) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val completer = kotlinx.coroutines.CompletableDeferred<String?>()
            val request = Request.Builder().url(wsUrl).build()

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                val authMessageId = "auth_1"
                val messageId = "msg_1"

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("MusicAssistantWS", "Connected to WebSocket: $wsUrl")
                    // Per the 2.9.4 API, "auth" must be the very first message sent.
                    val authMsg = JSONObject().apply {
                        put("message_id", authMessageId)
                        put("command", "auth")
                        put("args", JSONObject().apply { put("token", token) })
                    }
                    webSocket.send(authMsg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("MusicAssistantWS", "Received WebSocket message")
                    try {
                        val obj = JSONObject(text)

                        // Ignore unsolicited events (no message_id correlation)
                        if (obj.has("event") && !obj.has("message_id")) {
                            return
                        }

                        val msgId = obj.optString("message_id", obj.optString("id", ""))

                        if (msgId == authMessageId) {
                            val result = obj.optJSONObject("result")
                            if (result != null && result.optBoolean("authenticated", false)) {
                                sendCommand(webSocket)
                            } else {
                                Log.e("MusicAssistantWS", "WebSocket authentication failed")
                                completer.complete(null)
                                webSocket.close(1000, "Auth failed")
                            }
                            return
                        }

                        if (msgId == messageId) {
                            if (obj.has("error")) {
                                Log.e("MusicAssistantWS", "Command error: ${obj.optJSONObject("error")}")
                                completer.complete(null)
                            } else {
                                completer.complete(text)
                            }
                            webSocket.close(1000, "Done")
                        }
                    } catch (e: Exception) {
                        Log.e("MusicAssistantWS", "Error parsing WS message: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("MusicAssistantWS", "WebSocket failure: ${t.message}", t)
                    if (!completer.isCompleted) {
                        completer.complete(null)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                private fun sendCommand(webSocket: WebSocket) {
                    val cmdMsg = JSONObject().apply {
                        put("message_id", messageId)
                        put("command", command)
                        put("args", args)
                    }
                    webSocket.send(cmdMsg.toString())
                }
            })

            try {
                kotlinx.coroutines.withTimeout(8000) {
                    completer.await()
                }
            } catch (e: Exception) {
                Log.e("MusicAssistantWS", "WebSocket call timed out or failed for $command: ${e.message}")
                ws.close(1000, "Timeout")
                null
            }
        }
    }

    // ---------------------------------------------------------------------
    // HTTP RPC (POST /api) — kept available for one-off calls outside WebSocket
    // ---------------------------------------------------------------------

    suspend fun executeHttpCommand(
        host: String,
        port: String,
        token: String,
        command: String,
        args: JSONObject = JSONObject()
    ): String? {
        val apiUrl = getApiRpcUrl(host, port) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("command", command)
                    put("args", args)
                }.toString().toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            } catch (e: IOException) {
                Log.e("MusicAssistantClient", "IOException during HTTP API call: ${e.message}")
                null
            }
        }
    }

    // --- Dynamic JSON Parsers with fallbacks ---

    private fun parseTracks(json: String, host: String, port: String): List<Track> {
        val list = mutableListOf<Track>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("tracks")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("item_id", obj.optString("id", ""))
                val name = obj.optString("name", "Unknown Track")

                // Artist parsing
                var artistName = "Unknown Artist"
                val artistsArr = obj.optJSONArray("artists")
                if (artistsArr != null && artistsArr.length() > 0) {
                    artistName = artistsArr.getJSONObject(0).optString("name", "Unknown Artist")
                } else {
                    val artistObj = obj.optJSONObject("artist")
                    if (artistObj != null) {
                        artistName = artistObj.optString("name", "Unknown Artist")
                    }
                }

                // Album parsing
                var albumName = "Single"
                val albumObj = obj.optJSONObject("album")
                if (albumObj != null) {
                    albumName = albumObj.optString("name", "Unknown Album")
                }

                val duration = obj.optInt("duration", 210)

                // Image parsing
                var imageUrl: String? = null
                val metadata = obj.optJSONObject("metadata")
                if (metadata != null) {
                    imageUrl = resolveImageUrl(host, port, metadata.optJSONArray("images"))
                }
                if (imageUrl.isNullOrEmpty() && albumObj != null) {
                    val albumMetadata = albumObj.optJSONObject("metadata")
                    if (albumMetadata != null) {
                        imageUrl = resolveImageUrl(host, port, albumMetadata.optJSONArray("images"))
                    }
                }
                if (imageUrl.isNullOrEmpty()) {
                    imageUrl = obj.optString("image", null)
                }

                val uri = obj.optString("uri", null)
                var streamUrl = obj.optString("stream_url", null)
                if (streamUrl.isNullOrEmpty() && id.isNotEmpty()) {
                    streamUrl = getStreamUrl(host, port, id)
                }

                if (id.isNotEmpty()) {
                    list.add(Track(id, name, artistName, albumName, duration, imageUrl, streamUrl, uri))
                }
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing tracks: ${e.message}")
        }
        return list
    }

    private fun parseArtists(json: String, host: String, port: String): List<Artist> {
        val list = mutableListOf<Artist>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("artists")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("item_id", obj.optString("id", ""))
                val name = obj.optString("name", "Unknown Artist")

                var imageUrl: String? = null
                val metadata = obj.optJSONObject("metadata")
                if (metadata != null) {
                    imageUrl = resolveImageUrl(host, port, metadata.optJSONArray("images"))
                }

                if (id.isNotEmpty()) {
                    list.add(Artist(id, name, imageUrl))
                }
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing artists: ${e.message}")
        }
        return list
    }

    private fun parseAlbums(json: String, host: String, port: String): List<Album> {
        val list = mutableListOf<Album>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("albums")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("item_id", obj.optString("id", ""))
                val name = obj.optString("name", "Unknown Album")

                var artistName = "Unknown Artist"
                val artistObj = obj.optJSONObject("artist")
                if (artistObj != null) {
                    artistName = artistObj.optString("name", "Unknown Artist")
                } else {
                    val artistsArr = obj.optJSONArray("artists")
                    if (artistsArr != null && artistsArr.length() > 0) {
                        artistName = artistsArr.getJSONObject(0).optString("name", "Unknown Artist")
                    }
                }

                var imageUrl: String? = null
                val metadata = obj.optJSONObject("metadata")
                if (metadata != null) {
                    imageUrl = resolveImageUrl(host, port, metadata.optJSONArray("images"))
                }

                if (id.isNotEmpty()) {
                    list.add(Album(id, name, artistName, imageUrl))
                }
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing albums: ${e.message}")
        }
        return list
    }

    private fun parsePlaylists(json: String, host: String, port: String): List<Playlist> {
        val list = mutableListOf<Playlist>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("playlists")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("item_id", obj.optString("id", ""))
                val name = obj.optString("name", "Unknown Playlist")

                var imageUrl: String? = null
                val metadata = obj.optJSONObject("metadata")
                if (metadata != null) {
                    imageUrl = resolveImageUrl(host, port, metadata.optJSONArray("images"))
                }

                val trackCount = obj.optInt("track_count", 0)

                if (id.isNotEmpty()) {
                    list.add(Playlist(id, name, imageUrl, trackCount))
                }
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing playlists: ${e.message}")
        }
        return list
    }

    private fun parsePlayers(json: String): List<Player> {
        val list = mutableListOf<Player>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("result")
                        ?: obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("players")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("player_id", obj.optString("id", ""))
                val name = obj.optString("name", "Unknown Player")
                val state = obj.optString("state", "idle")
                val volume = obj.optInt("volume_level", 50)
                val currentTrackId = obj.optString("current_item_id", null)
                val isMuted = obj.optBoolean("is_muted", false)

                if (id.isNotEmpty()) {
                    list.add(Player(id, name, state, volume, currentTrackId, isMuted))
                }
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing players: ${e.message}")
        }
        return list
    }

    /** Parses a "player_queues/all" result array into a list of QueueState. */
    fun parseQueueStates(json: String, host: String, port: String): List<QueueState> {
        val list = mutableListOf<QueueState>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("queues")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                parseQueueStateObject(arr.getJSONObject(i), host, port)?.let { list.add(it) }
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing queues: ${e.message}")
        }
        return list
    }

    /**
     * Parses a single queue object (whether from "player_queues/all" or pushed live via a
     * "queue_updated" / "queue_time_updated" event's "data" field) into a QueueState.
     * Tolerant of slightly different field names/nesting since this isn't formally documented.
     */
    fun parseQueueStateObject(obj: JSONObject, host: String, port: String): QueueState? {
        val queueId = obj.optString("queue_id", obj.optString("id", ""))
        if (queueId.isEmpty()) return null

        val state = obj.optString("state", if (obj.optBoolean("active", false)) "playing" else "idle")
        val shuffleEnabled = obj.optBoolean("shuffle_enabled", false)
        val repeatMode = obj.optString("repeat_mode", "off")
        // NOTE: "elapsed_time" is a snapshot (seconds) taken at "elapsed_time_last_updated" (a
        // timestamp) — it is NOT continuously pushed every second, so the UI must locally tick
        // this value forward once per second while playing and resync whenever a fresh event
        // arrives (see MusicViewModel's position ticker).
        val elapsedTime = obj.optDouble("elapsed_time", 0.0).toInt()

        val currentItemObj = obj.optJSONObject("current_item") ?: obj.optJSONObject("current_track")
        val currentTrack = currentItemObj?.let { item ->
            // The track metadata may be nested under "media_item" / "track", or be the item itself.
            val mediaObj = item.optJSONObject("media_item") ?: item.optJSONObject("track") ?: item

            val trackId = mediaObj.optString("item_id", mediaObj.optString("id", item.optString("queue_item_id", "")))
            val name = mediaObj.optString("name", "Unknown Track")

            var artistName = "Unknown Artist"
            val artistsArr = mediaObj.optJSONArray("artists")
            if (artistsArr != null && artistsArr.length() > 0) {
                artistName = artistsArr.getJSONObject(0).optString("name", "Unknown Artist")
            } else {
                mediaObj.optJSONObject("artist")?.let { artistName = it.optString("name", "Unknown Artist") }
            }

            var albumName = "Single"
            mediaObj.optJSONObject("album")?.let { albumName = it.optString("name", "Unknown Album") }

            // Prefer the queue item's own duration (accounts for radio/stream edge cases) and
            // fall back to the underlying media item's duration.
            val duration = item.optInt("duration", mediaObj.optInt("duration", 0))

            val imageUrl = resolveImageUrl(host, port, mediaObj.optJSONObject("metadata")?.optJSONArray("images"))

            val uri = mediaObj.optString("uri", null)

            Track(
                id = trackId.ifEmpty { "queue_item" },
                name = name,
                artist = artistName,
                album = albumName,
                durationSec = duration,
                image = imageUrl,
                streamUrl = null,
                uri = uri
            )
        }

        return QueueState(
            queueId = queueId,
            state = state,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            elapsedTimeSec = elapsedTime,
            currentTrack = currentTrack
        )
    }

    fun parseQueueItems(json: String, host: String, port: String): List<Track> {
        val list = mutableListOf<Track>()
        try {
            val arr = when {
                json.startsWith("[") -> JSONArray(json)
                else -> {
                    val obj = JSONObject(json)
                    obj.optJSONArray("items")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val mediaObj = item.optJSONObject("media_item") ?: item.optJSONObject("track") ?: item
                val trackId = mediaObj.optString("item_id", mediaObj.optString("id", item.optString("queue_item_id", "")))
                if (trackId.isEmpty()) continue
                
                val name = mediaObj.optString("name", "Unknown Track")

                var artistName = "Unknown Artist"
                val artistsArr = mediaObj.optJSONArray("artists")
                if (artistsArr != null && artistsArr.length() > 0) {
                    artistName = artistsArr.getJSONObject(0).optString("name", "Unknown Artist")
                } else {
                    mediaObj.optJSONObject("artist")?.let { artistName = it.optString("name", "Unknown Artist") }
                }

                var albumName = "Single"
                mediaObj.optJSONObject("album")?.let { albumName = it.optString("name", "Unknown Album") }

                val duration = item.optInt("duration", mediaObj.optInt("duration", 0))
                val imageUrl = resolveImageUrl(host, port, mediaObj.optJSONObject("metadata")?.optJSONArray("images"))
                val uri = mediaObj.optString("uri", null)

                list.add(
                    Track(
                        id = trackId,
                        name = name,
                        artist = artistName,
                        album = albumName,
                        durationSec = duration,
                        image = imageUrl,
                        streamUrl = null,
                        uri = uri
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("MusicAssistantClient", "Error parsing queue items: ${e.message}", e)
        }
        return list
    }
}
