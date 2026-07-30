package com.wuxianpi.assist.protocol

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AssistWebSocketTransportTest {
    @Test
    fun textApplicationFramesRemainSupported() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val serverReceived = AtomicReference<String>()
        val serverMessage = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverReceived.set(text)
                        serverMessage.countDown()
                    }
                },
            ),
        )
        server.start()

        try {
            val invite = testInvite(server, 69)
            val envelope = envelope(invite.roomId, Role.HOST, 1)
            val opened = CountDownLatch(1)
            val connection = OkHttpAssistWebSocketTransport(client).connect(
                RelayConnectionConfig(invite.relayUrl, invite.roomId, Role.HOST),
                object : AssistWebSocketTransport.Listener {
                    override fun onOpen(connection: AssistWebSocketTransport.Connection) {
                        opened.countDown()
                    }

                    override fun onEnvelope(
                        connection: AssistWebSocketTransport.Connection,
                        envelope: RelayEnvelope,
                    ) = Unit
                },
            )
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertTrue(connection.send(envelope))
            assertTrue(serverMessage.await(5, TimeUnit.SECONDS))
            assertEquals(envelope, AssistProtocolJson.decodeRelayEnvelope(serverReceived.get()))
            connection.cancel()
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun transportConnectsAndExchangesStrictRelayEnvelopes() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val serverSocket = AtomicReference<WebSocket>()
        val serverReceived = AtomicReference<ByteString>()
        val serverOpen = CountDownLatch(1)
        val serverMessage = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        serverOpen.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        serverReceived.set(bytes)
                        serverMessage.countDown()
                    }
                },
            ),
        )
        server.start()

        try {
            val invite = testInvite(server, 70)
            val hostEnvelope = envelope(invite.roomId, Role.HOST, 1)
            val assistEnvelope = envelope(invite.roomId, Role.ASSIST, 1)
            val clientOpen = CountDownLatch(1)
            val clientMessage = CountDownLatch(1)
            val received = AtomicReference<RelayEnvelope>()
            val failure = AtomicReference<Throwable>()
            val connection = OkHttpAssistWebSocketTransport(client).connect(
                RelayConnectionConfig(
                    invite.relayUrl,
                    invite.roomId,
                    Role.HOST,
                    applicationFrameEncoding = ApplicationFrameEncoding.BINARY,
                ),
                object : AssistWebSocketTransport.Listener {
                    override fun onOpen(connection: AssistWebSocketTransport.Connection) {
                        clientOpen.countDown()
                    }

                    override fun onEnvelope(
                        connection: AssistWebSocketTransport.Connection,
                        envelope: RelayEnvelope,
                    ) {
                        received.set(envelope)
                        clientMessage.countDown()
                    }

                    override fun onFailure(
                        connection: AssistWebSocketTransport.Connection,
                        error: Throwable,
                    ) {
                        failure.set(error)
                        clientMessage.countDown()
                    }
                },
            )

            assertTrue(serverOpen.await(5, TimeUnit.SECONDS))
            assertTrue(clientOpen.await(5, TimeUnit.SECONDS))
            assertTrue(connection.isOpen)
            assertTrue(connection.send(hostEnvelope))
            assertTrue(serverMessage.await(5, TimeUnit.SECONDS))
            assertEquals(
                hostEnvelope,
                AssistProtocolJson.decodeRelayEnvelope(serverReceived.get().utf8()),
            )

            serverSocket.get().send(AssistProtocolJson.encodeRelayEnvelope(assistEnvelope).encodeUtf8())
            assertTrue(clientMessage.await(5, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertEquals(assistEnvelope, received.get())

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("1", request.requestUrl!!.queryParameter("v"))
            assertEquals(invite.roomId, request.requestUrl!!.queryParameter("room"))
            assertEquals(Role.HOST.name, request.requestUrl!!.queryParameter("role"))
            connection.close()
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun peerStatusControlFrameIsDeliveredWithoutClosingConnection() {
        val event = RelayPeerStatus(RelayPeerState.WAITING)
        val control = runControlFrameTest(AssistProtocolJson.encodeRelayControlEvent(event))

        assertEquals(event, control.event)
        assertTrue(control.connection.isOpen)
        control.close()
    }

    @Test
    fun handshakeTextFrameIsDeliveredSeparatelyFromApplicationFrames() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val serverSocket = AtomicReference<WebSocket>()
        val serverOpen = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        serverOpen.countDown()
                    }
                },
            ),
        )
        server.start()

        try {
            val hostSigner = IdentityKeys.generate(seededRandom(73))
            val invite = Invite.create(
                server.url("/relay").toString().replaceFirst("http://", "ws://"),
                hostSigner.identity,
                seededRandom(74),
            )
            val hostHello = HostHandshake.create(
                invite,
                hostSigner,
                secureRandom = seededRandom(75),
            ).hostHello
            val received = AtomicReference<HandshakeMessage>()
            val receivedLatch = CountDownLatch(1)
            val connection = OkHttpAssistWebSocketTransport(client).connect(
                RelayConnectionConfig(invite.relayUrl, invite.roomId, Role.ASSIST),
                object : AssistWebSocketTransport.Listener {
                    override fun onEnvelope(
                        connection: AssistWebSocketTransport.Connection,
                        envelope: RelayEnvelope,
                    ) = Unit

                    override fun onHandshakeMessage(
                        connection: AssistWebSocketTransport.Connection,
                        message: HandshakeMessage,
                    ) {
                        received.set(message)
                        receivedLatch.countDown()
                    }
                },
            )
            assertTrue(serverOpen.await(5, TimeUnit.SECONDS))
            serverSocket.get().send(AssistProtocolJson.encodeHandshakeMessage(hostHello))
            assertTrue(receivedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(hostHello, received.get())
            assertTrue(connection.isOpen)
            connection.cancel()
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun peerLeftControlFrameIsDeliveredWithoutClosingConnection() {
        val event = RelayPeerLeft(Role.ASSIST)
        val control = runControlFrameTest(AssistProtocolJson.encodeRelayControlEvent(event))

        assertEquals(event, control.event)
        assertTrue(control.connection.isOpen)
        control.close()
    }

    @Test
    fun invalidRelayControlFrameClosesWithUnsupportedData() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val failure = AtomicReference<Throwable>()
        val failureLatch = CountDownLatch(1)
        val serverCloseCode = AtomicReference<Int>()
        val serverClosed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            "{\"relay\":1,\"type\":\"peer_status\",\"status\":\"invalid\"}",
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        serverCloseCode.set(code)
                        serverClosed.countDown()
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.start()

        try {
            val invite = testInvite(server, 71)
            val connection = OkHttpAssistWebSocketTransport(client).connect(
                RelayConnectionConfig(invite.relayUrl, invite.roomId, Role.HOST),
                object : AssistWebSocketTransport.Listener {
                    override fun onEnvelope(
                        connection: AssistWebSocketTransport.Connection,
                        envelope: RelayEnvelope,
                    ) = Unit

                    override fun onFailure(
                        connection: AssistWebSocketTransport.Connection,
                        error: Throwable,
                    ) {
                        failure.set(error)
                        failureLatch.countDown()
                    }
                },
            )

            assertTrue(failureLatch.await(5, TimeUnit.SECONDS))
            assertTrue(serverClosed.await(5, TimeUnit.SECONDS))
            assertTrue(failure.get() is AssistProtocolException)
            assertEquals(1003, serverCloseCode.get())
            assertEquals(false, connection.isOpen)
            connection.cancel()
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun runControlFrameTest(payload: String): ReceivedControl {
        val server = MockWebServer()
        val client = OkHttpClient()
        val serverSocket = AtomicReference<WebSocket>()
        val serverOpen = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        serverOpen.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.start()

        val invite = testInvite(server, 72)
        val received = AtomicReference<RelayControlEvent>()
        val controlLatch = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val connection = OkHttpAssistWebSocketTransport(client).connect(
            RelayConnectionConfig(invite.relayUrl, invite.roomId, Role.HOST),
            object : AssistWebSocketTransport.Listener {
                override fun onEnvelope(
                    connection: AssistWebSocketTransport.Connection,
                    envelope: RelayEnvelope,
                ) = Unit

                override fun onControlEvent(
                    connection: AssistWebSocketTransport.Connection,
                    event: RelayControlEvent,
                ) {
                    received.set(event)
                    controlLatch.countDown()
                }

                override fun onFailure(
                    connection: AssistWebSocketTransport.Connection,
                    error: Throwable,
                ) {
                    failure.set(error)
                    controlLatch.countDown()
                }
            },
        )
        assertTrue(serverOpen.await(5, TimeUnit.SECONDS))
        serverSocket.get().send(payload)
        assertTrue(controlLatch.await(5, TimeUnit.SECONDS))
        if (failure.get() != null) {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            throw AssertionError("Control frame unexpectedly failed", failure.get())
        }
        return ReceivedControl(
            event = received.get(),
            connection = connection,
            close = {
                connection.cancel()
                server.close()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            },
        )
    }

    private data class ReceivedControl(
        val event: RelayControlEvent,
        val connection: AssistWebSocketTransport.Connection,
        val close: () -> Unit,
    ) {
        fun close() = close.invoke()
    }

    private fun envelope(roomId: String, role: Role, sequence: Long): RelayEnvelope =
        RelayEnvelope(
            roomId = roomId,
            role = role,
            frame = EncryptedFrame(
                direction = role.outboundDirection,
                sequence = sequence,
                ciphertext = encodeBase64Url(ByteArray(29) { (it + role.ordinal).toByte() }),
            ),
        )

    private fun testInvite(server: MockWebServer, seed: Long): Invite = Invite.create(
        server.url("/relay").toString().replaceFirst("http://", "ws://"),
        IdentityKeys.generate(seededRandom(seed)).identity,
        seededRandom(seed + 1),
    )
}
