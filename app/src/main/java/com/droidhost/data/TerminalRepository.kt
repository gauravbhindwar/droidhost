package com.droidhost.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

enum class TerminalConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

interface TerminalRepository {
    val connectionState: StateFlow<TerminalConnectionState>
    val output: SharedFlow<String>
    fun connect()
    fun disconnect()
    fun send(text: String)
    fun sendBytes(bytes: ByteArray)
}

class WebSocketTerminalRepository(
    private val wsUrl: String,
    private val token: String
) : TerminalRepository {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow(TerminalConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TerminalConnectionState> = _connectionState.asStateFlow()

    private val _output = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val output: SharedFlow<String> = _output.asSharedFlow()

    override fun connect() {
        if (_connectionState.value == TerminalConnectionState.CONNECTED ||
            _connectionState.value == TerminalConnectionState.CONNECTING
        ) {
            return
        }

        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null

        _connectionState.value = TerminalConnectionState.CONNECTING

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = TerminalConnectionState.CONNECTED
                _output.tryEmit("[Connected to Linux VM Terminal]\r\n")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _output.tryEmit(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _output.tryEmit(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _connectionState.value = TerminalConnectionState.DISCONNECTED
                _output.tryEmit("\r\n[Session closed]\r\n")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = TerminalConnectionState.ERROR
                _output.tryEmit("\r\n[Connection failed: ${t.message ?: "network error"}]\r\n")
            }
        })
    }

    override fun disconnect() {
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {
        } finally {
            webSocket = null
            _connectionState.value = TerminalConnectionState.DISCONNECTED
        }
    }

    override fun send(text: String) {
        val ws = webSocket
        if (ws != null && _connectionState.value == TerminalConnectionState.CONNECTED) {
            ws.send(text)
        } else {
            connect()
            webSocket?.send(text)
        }
    }

    override fun sendBytes(bytes: ByteArray) {
        val ws = webSocket
        if (ws != null && _connectionState.value == TerminalConnectionState.CONNECTED) {
            ws.send(ByteString.of(*bytes))
        } else {
            connect()
            webSocket?.send(ByteString.of(*bytes))
        }
    }
}
