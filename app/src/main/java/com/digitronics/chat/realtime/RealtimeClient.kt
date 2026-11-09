package com.digitronics.chat.realtime

import com.digitronics.chat.auth.TokenRefresher
import com.digitronics.chat.auth.TokenStore
import com.digitronics.chat.net.ApiJson
import com.digitronics.chat.net.RealtimeFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.random.Random

enum class RealtimeStatus { OFF, CONNECTING, CONNECTED, WAITING }

/**
 * The foreground-only DM socket (`/api/v1/dm/realtime/connect`).
 *
 *  - Authenticates with the `Authorization: Bearer` header the client's
 *    interceptor adds — never a token in the URL.
 *  - Sends `{"type":"ping"}` every 25 s.
 *  - Close 4001 (authorisation aged out) and 4003 (access changed), or a
 *    401/403 handshake, reconnect through a token refresh; a refused refresh
 *    ends the session and this loop with it.
 *  - Anything else reconnects with exponential backoff and jitter.
 *  - Every (re)connect calls [onConnected] so the caller re-syncs by cursor:
 *    events missed while disconnected are recovered from the server, not the socket.
 */
class RealtimeClient(
    private val http: OkHttpClient,
    private val url: String,
    private val tokens: TokenStore,
    private val refresher: TokenRefresher,
    private val scope: CoroutineScope,
    private val onConnected: suspend () -> Unit,
    private val onFrame: suspend (RealtimeFrame) -> Unit,
) {
    private val _status = MutableStateFlow(RealtimeStatus.OFF)
    val status: StateFlow<RealtimeStatus> = _status.asStateFlow()
    private var loop: Job? = null

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        _status.value = RealtimeStatus.OFF
    }

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive) {
            _status.value = RealtimeStatus.CONNECTING
            val end = connectOnce { attempt = 0 }
            _status.value = RealtimeStatus.WAITING
            if (end.needsReauthorization) {
                val token = try {
                    refresher.refresh(tokens.currentAccessToken())
                } catch (e: java.io.IOException) {
                    "retry-later"
                }
                if (token == null) break // session over; SessionManager has wiped and signed out
            }
            delay(backoffMillis(attempt++))
        }
        _status.value = RealtimeStatus.OFF
    }

    private suspend fun connectOnce(onOpen: () -> Unit): SocketEnd = suspendCancellableCoroutine { cont ->
        var pinger: Job? = null
        val socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onOpen()
                    _status.value = RealtimeStatus.CONNECTED
                    pinger = scope.launch {
                        while (isActive) {
                            delay(PING_INTERVAL_MS)
                            webSocket.send(PING)
                        }
                    }
                    scope.launch { runCatching { onConnected() } }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { ApiJson.decodeFromString(RealtimeFrame.serializer(), text) }.getOrNull() ?: return
                    if (frame.type == "pong") return
                    scope.launch { runCatching { onFrame(frame) } }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    pinger?.cancel()
                    if (cont.isActive) cont.resume(SocketEnd(code = code, httpStatus = null))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    pinger?.cancel()
                    if (cont.isActive) cont.resume(SocketEnd(code = null, httpStatus = response?.code))
                }
            },
        )
        cont.invokeOnCancellation {
            pinger?.cancel()
            socket.cancel()
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MS)
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    private data class SocketEnd(val code: Int?, val httpStatus: Int?) {
        val needsReauthorization: Boolean
            get() = code == CLOSE_REAUTHORIZE || code == CLOSE_ACCESS_CHANGED || httpStatus == 401 || httpStatus == 403
    }

    companion object {
        const val CLOSE_REAUTHORIZE = 4001
        const val CLOSE_ACCESS_CHANGED = 4003
        const val PING_INTERVAL_MS = 25_000L
        const val MAX_BACKOFF_MS = 60_000L
        private const val PING = """{"type":"ping"}"""
    }
}
