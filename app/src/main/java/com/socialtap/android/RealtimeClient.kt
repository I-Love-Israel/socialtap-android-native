package com.socialtap.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.BufferOverflow

class RealtimeEvent(val name: String, val args: JSONArray, val attachments: List<ByteArray>)

/**
 * Minimal Socket.IO 4 (Engine.IO 4) client over WebSocket, as used by SocialTap's chat and voice APIs.
 * Authenticates with the OAuth access token in the connection auth object (auth.access_token).
 */
class RealtimeClient(private val tokens: TokenStore, private val api: SocialTapApi) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _events = MutableSharedFlow<RealtimeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    var sid: String? = null
        private set

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var generation = 0

    private var running = false
    private var pingIntervalMs = 25_000L
    private var pingTimeoutMs = 20_000L
    @Volatile
    private var lastSeen = 0L
    private var watchdog: Job? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 1_000L
    private val nextAckId = AtomicInteger(1)
    private val acks = ConcurrentHashMap<Int, (JSONArray) -> Unit>()
    private var pending: PendingBinary? = null

    private class PendingBinary(val name: String, val args: JSONArray, val expected: Int) {
        val attachments = ArrayList<ByteArray>()
    }

    @Synchronized
    fun start() {
        if (running || !tokens.isSignedIn) return
        running = true
        open()
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        reconnectJob?.cancel()
        watchdog?.cancel()
        socket?.close(1000, "client stopped")
        socket = null
        pending = null
        sid = null
        _connected.value = false
    }

    @Synchronized
    private fun open() {
        if (!running) return
        if (!tokens.isSignedIn) {
            running = false
            return
        }
        val gen = ++generation
        lastSeen = System.currentTimeMillis()
        val request = Request.Builder()
            .url("wss://${Config.WS_HOST}/socket.io/?EIO=4&transport=websocket")
            .header("User-Agent", Config.USER_AGENT)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) handleBinary(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) {
                    val authFailed = response?.code == 401 || response?.code == 403
                    onDown(authFailed)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) onDown(authFailed = false)
            }
        })
    }

    @Synchronized
    private fun onDown(authFailed: Boolean) {
        generation++
        watchdog?.cancel()
        pending = null
        acks.clear()
        sid = null
        socket = null
        _connected.value = false
        if (!running) return
        reconnectJob?.cancel()
        val waitMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        reconnectJob = scope.launch {
            delay(waitMs)
            if (authFailed) api.refreshTokens()
            synchronized(this@RealtimeClient) {
                if (running) open()
            }
        }
    }

    @Synchronized
    private fun handleText(text: String) {
        lastSeen = System.currentTimeMillis()
        when {
            // Engine.IO open packet: handshake JSON with ping settings. Reply with Socket.IO CONNECT + auth.
            text.startsWith("0") -> {
                val handshake = JSONObject(text.substring(1))
                pingIntervalMs = handshake.optLong("pingInterval", 25_000L)
                pingTimeoutMs = handshake.optLong("pingTimeout", 20_000L)
                val token = tokens.accessToken
                if (token == null) {
                    stop()
                    return
                }
                socket?.send("40" + JSONObject().put("access_token", token).toString())
                startWatchdog()
            }
            text == "2" -> socket?.send("3")
            text == "3" -> Unit
            text.startsWith("40") -> {
                sid = runCatching { JSONObject(text.substring(2)).optString("sid") }.getOrNull()
                backoffMs = 1_000L
                _connected.value = true
            }
            text.startsWith("41") -> onDown(authFailed = false)
            text.startsWith("44") -> {
                val message = text.substring(2)
                val authIssue = message.contains("unauthor", ignoreCase = true) ||
                    message.contains("token", ignoreCase = true) ||
                    message.contains("auth", ignoreCase = true)
                onDown(authFailed = authIssue)
            }
            text.startsWith("42") -> handleEvent(text.substring(2))
            text.startsWith("43") -> handleAck(text.substring(2))
            text.startsWith("45") -> startBinary(text.substring(2))
        }
    }

    @Synchronized
    private fun handleBinary(bytes: ByteArray) {
        val p = pending ?: return
        p.attachments.add(bytes)
        if (p.attachments.size >= p.expected) {
            pending = null
            _events.tryEmit(RealtimeEvent(p.name, p.args, p.attachments))
        }
    }

    private fun handleEvent(body: String) {
        val (_, json) = splitId(body)
        val arr = JSONArray(json)
        val name = arr.optString(0)
        if (name.isEmpty()) return
        _events.tryEmit(RealtimeEvent(name, argsOf(arr), emptyList()))
    }

    private fun handleAck(body: String) {
        val (id, json) = splitId(body)
        if (id == null) return
        val callback = acks.remove(id) ?: return
        callback(JSONArray(json))
    }

    private fun startBinary(body: String) {
        val dash = body.indexOf('-')
        if (dash <= 0) return
        val count = body.substring(0, dash).toIntOrNull() ?: return
        val (_, json) = splitId(body.substring(dash + 1))
        val arr = JSONArray(json)
        pending = PendingBinary(arr.optString(0), argsOf(arr), count)
    }

    private fun argsOf(arr: JSONArray): JSONArray {
        val args = JSONArray()
        for (i in 1 until arr.length()) args.put(arr.get(i))
        return args
    }

    /** Splits an optional numeric acknowledgement id from the front of a packet body. */
    private fun splitId(body: String): Pair<Int?, String> {
        var i = 0
        while (i < body.length && body[i].isDigit()) i++
        val id = if (i > 0) body.substring(0, i).toInt() else null
        return id to body.substring(i)
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(5_000L)
                if (System.currentTimeMillis() - lastSeen > pingIntervalMs + pingTimeoutMs) {
                    synchronized(this@RealtimeClient) { onDown(authFailed = false) }
                    return@launch
                }
            }
        }
    }

    /** Emits an event and waits for the server acknowledgement. Throws on errors or timeout. */
    suspend fun request(event: String, payload: JSONObject, timeoutMs: Long = 10_000L): JSONObject {
        val s = socket ?: throw IOException("Not connected to SocialTap")
        val id = nextAckId.getAndIncrement()
        val reply = CompletableDeferred<JSONArray>()
        acks[id] = { reply.complete(it) }
        if (!s.send("42$id" + JSONArray().put(event).put(payload).toString())) {
            acks.remove(id)
            throw IOException("Could not send $event")
        }
        val result = withTimeoutOrNull(timeoutMs) { reply.await() }
            ?: run {
                acks.remove(id)
                throw IOException("SocialTap did not answer $event")
            }
        val data = result.optJSONObject(0) ?: JSONObject()
        if (data.has("success") && !data.optBoolean("success", false)) {
            throw ApiException(data.optInt("status", 400), data.optString("error", "$event failed"))
        }
        return data
    }

    /** Fire-and-forget event. */
    fun emit(event: String, payload: JSONObject): Boolean {
        val s = socket ?: return false
        return s.send("42" + JSONArray().put(event).put(payload).toString())
    }

    /** Event carrying one binary attachment; the attachment is placed at the "frame" field. */
    fun emitBinary(event: String, payload: JSONObject, frame: ByteArray): Boolean {
        val s = socket ?: return false
        val body = JSONObject(payload.toString())
            .put("frame", JSONObject().put("_placeholder", true).put("num", 0))
        if (!s.send("451-" + JSONArray().put(event).put(body).toString())) return false
        return s.send(frame.toByteString())
    }
}
