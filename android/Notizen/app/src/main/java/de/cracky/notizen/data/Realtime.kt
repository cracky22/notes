package de.cracky.notizen.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Eine dauerhafte WebSocket-Verbindung zum Server (wie im Web-Client):
 * Live-Änderungen aller Notizen, Bestätigungen eigener Befehle, Labels, Einstellungen.
 * Verbindet sich mit exponentiellem Backoff neu; Netzwechsel lösen sofort einen neuen Versuch aus.
 */
class Realtime(private val api: NotizenApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)
    val events: SharedFlow<JsonObject> = _events
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var socket: WebSocket? = null
    private var wanted = false
    private var attempt = 0
    @Volatile private var generation = 0
    private var reconnectJob: Job? = null

    @Synchronized
    fun start() {
        wanted = true
        if (socket == null) connect()
    }

    @Synchronized
    fun stop() {
        wanted = false
        reconnectJob?.cancel()
        generation++
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    /** Sofort neu verbinden (z. B. wenn das Netz wieder da ist). */
    @Synchronized
    fun kick() {
        if (!wanted || _connected.value) return
        reconnectJob?.cancel()
        attempt = 0
        if (socket == null) connect()
    }

    fun send(obj: JsonObject): Boolean {
        val ws = synchronized(this) { socket.takeIf { _connected.value } } ?: return false
        return ws.send(obj.toString())
    }

    private fun connect() {
        val gen = ++generation
        socket = try {
            api.openWebSocket(listener = Listener(gen))
        } catch (_: Exception) {
            null
        }
        if (socket == null) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val base = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        val wait = base + Random.nextLong(0, 700)
        attempt++
        reconnectJob = scope.launch {
            delay(wait)
            synchronized(this@Realtime) { if (wanted && socket == null) connect() }
        }
    }

    private fun lost(gen: Int, authProblem: Boolean) {
        synchronized(this) {
            if (gen != generation) return
            socket = null
            _connected.value = false
            if (authProblem) {
                wanted = false
            } else if (wanted) {
                scheduleReconnect()
            }
        }
        if (authProblem) _events.tryEmit(JsonObject().apply { addProperty("t", "auth_lost") })
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@Realtime) {
                if (gen != generation) return
                attempt = 0
                _connected.value = true
            }
            _events.tryEmit(JsonObject().apply { addProperty("t", "connected") })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
            _events.tryEmit(obj)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(gen, code == 4401)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Starlette lehnt den Handshake ohne gültige Sitzung mit HTTP 403 ab
            lost(gen, response?.code == 403 || response?.code == 401)
        }
    }
}
