package com.wuxianpi.assist.protocol

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class ApplicationFrameEncoding {
    TEXT,
    BINARY,
}

data class RelayConnectionConfig(
    val relayUrl: String,
    val roomId: String,
    val role: Role,
    val applicationFrameEncoding: ApplicationFrameEncoding = ApplicationFrameEncoding.TEXT,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        validateRelayUrl(relayUrl)
        validateRoomId(roomId)
        headers.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) {
                throw AssistProtocolException("Relay headers cannot contain blank names or values")
            }
        }
    }

}

interface AssistWebSocketTransport {
    fun connect(
        config: RelayConnectionConfig,
        listener: Listener,
    ): Connection

    interface Connection {
        val isOpen: Boolean

        fun send(envelope: RelayEnvelope): Boolean

        fun sendHandshake(message: HandshakeMessage): Boolean

        fun close(code: Int = 1000, reason: String = "client closed"): Boolean

        fun cancel()
    }

    interface Listener {
        fun onOpen(connection: Connection) = Unit

        fun onEnvelope(connection: Connection, envelope: RelayEnvelope)

        fun onControlEvent(connection: Connection, event: RelayControlEvent) = Unit

        fun onHandshakeMessage(connection: Connection, message: HandshakeMessage) = Unit

        fun onClosing(connection: Connection, code: Int, reason: String) = Unit

        fun onClosed(connection: Connection, code: Int, reason: String) = Unit

        fun onFailure(connection: Connection, error: Throwable) = Unit
    }
}

class OkHttpAssistWebSocketTransport(
    private val client: OkHttpClient,
) : AssistWebSocketTransport {
    override fun connect(
        config: RelayConnectionConfig,
        listener: AssistWebSocketTransport.Listener,
    ): AssistWebSocketTransport.Connection {
        val request = buildRequest(config)
        val bridge = ConnectionBridge(config, listener)
        bridge.attach(client.newWebSocket(request, bridge))
        return bridge
    }

    private fun buildRequest(config: RelayConnectionConfig): Request {
        val httpUrl = config.relayUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("v", ASSIST_PROTOCOL_VERSION.toString())
            .addQueryParameter("room", config.roomId)
            .addQueryParameter("role", config.role.name)
            .build()
        return Request.Builder()
            .url(httpUrl)
            .header("X-WuxianPi-Assist-Protocol", ASSIST_PROTOCOL_VERSION.toString())
            .apply {
                config.headers.forEach { (name, value) -> header(name, value) }
            }
            .build()
    }

    private class ConnectionBridge(
        private val config: RelayConnectionConfig,
        private val listener: AssistWebSocketTransport.Listener,
    ) : WebSocketListener(), AssistWebSocketTransport.Connection {
        @Volatile
        private var webSocket: WebSocket? = null
        private val open = AtomicBoolean(false)

        override val isOpen: Boolean
            get() = open.get()

        fun attach(value: WebSocket) {
            webSocket = value
        }

        override fun send(envelope: RelayEnvelope): Boolean {
            if (envelope.roomId != config.roomId || envelope.role != config.role) {
                throw AssistProtocolException("Relay envelope does not match this connection")
            }
            if (!open.get()) return false
            val payload = AssistProtocolJson.encodeRelayEnvelope(envelope)
            return when (config.applicationFrameEncoding) {
                ApplicationFrameEncoding.TEXT -> webSocket?.send(payload) ?: false
                ApplicationFrameEncoding.BINARY -> webSocket?.send(payload.encodeUtf8()) ?: false
            }
        }

        override fun sendHandshake(message: HandshakeMessage): Boolean {
            if (!open.get()) return false
            return webSocket?.send(AssistProtocolJson.encodeHandshakeMessage(message)) ?: false
        }

        override fun close(code: Int, reason: String): Boolean {
            open.set(false)
            return webSocket?.close(code, reason) ?: false
        }

        override fun cancel() {
            open.set(false)
            webSocket?.cancel()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket = webSocket
            open.set(true)
            listener.onOpen(this)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                AssistProtocolJson.decodeRelayTextFrame(text)
            } catch (error: Throwable) {
                rejectInvalidFrame(webSocket, error)
                return
            }
            when (frame) {
                is RelayTextFrame.Application -> deliverEnvelope(webSocket, frame.envelope)
                is RelayTextFrame.Control -> listener.onControlEvent(this, frame.event)
                is RelayTextFrame.Handshake -> listener.onHandshakeMessage(this, frame.message)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val envelope = try {
                AssistProtocolJson.decodeRelayEnvelope(decodeUtf8Strict(bytes))
            } catch (error: Throwable) {
                rejectInvalidFrame(webSocket, error)
                return
            }
            deliverEnvelope(webSocket, envelope)
        }

        private fun deliverEnvelope(webSocket: WebSocket, envelope: RelayEnvelope) {
            if (envelope.roomId != config.roomId || envelope.role == config.role) {
                val error = AssistProtocolException("Relay delivered an envelope for the wrong room or role")
                open.set(false)
                webSocket.close(1008, "invalid relay envelope")
                listener.onFailure(this, error)
                return
            }
            listener.onEnvelope(this, envelope)
        }

        private fun rejectInvalidFrame(webSocket: WebSocket, error: Throwable) {
            open.set(false)
            webSocket.close(1003, "invalid protocol frame")
            listener.onFailure(this, error)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            open.set(false)
            listener.onClosing(this, code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open.set(false)
            listener.onClosed(this, code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open.set(false)
            listener.onFailure(this, t)
        }
    }
}

private fun decodeUtf8Strict(bytes: ByteString): String = try {
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray()))
        .toString()
} catch (error: Exception) {
    throw AssistProtocolException("Binary application frame must contain UTF-8 JSON", error)
}
