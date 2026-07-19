package com.wuxianpi.pi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PiTransportInternalsTest {
    @Test
    fun `ordered queue survives pressure within its explicit capacity without loss`() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val done = CompletableDeferred<Unit>()
        val queue = OrderedQueue<Int>(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            capacity = 10_000,
        ) { value ->
            if (value % 50 == 0) delay(1)
            received += value
            if (value == 9_999) done.complete(Unit)
        }
        repeat(10_000) { assertEquals(QueueOfferResult.ACCEPTED, queue.offer(it)) }
        done.await()
        assertEquals((0 until 10_000).toList(), received.toList())
        queue.close()
    }

    @Test
    fun `bounded queue explicitly reports overflow so transport can reconnect`() = runBlocking {
        val consumerStarted = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val queue = OrderedQueue<Int>(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            capacity = 1,
        ) {
            consumerStarted.complete(Unit)
            releaseConsumer.await()
        }
        assertEquals(QueueOfferResult.ACCEPTED, queue.offer(1))
        consumerStarted.await()
        assertEquals(QueueOfferResult.ACCEPTED, queue.offer(2))
        assertEquals(QueueOfferResult.OVERFLOW_REQUIRES_RECOVERY, queue.offer(3))
        releaseConsumer.complete(Unit)
        queue.close()
    }

    @Test
    fun `recovery completes out of order and restores streaming state`() {
        val tracker = RecoveryTracker(7, "state", "messages")
        val messages = tracker.accept(response("messages", "get_messages", JSONObject()))
        assertFalse(messages.complete)
        val state = tracker.accept(
            response("state", "get_state", JSONObject().put("isStreaming", true)),
        )
        assertTrue(state.complete)
        assertEquals(true, state.isStreaming)
        assertFalse(state.failed)
    }

    @Test
    fun `prompt gate blocks concurrent prompt until agent end or idle recovery`() {
        val gate = PromptGate()
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
        assertFalse(gate.tryBegin(connected = true, isStreaming = false))
        gate.onAgentStart()
        assertFalse(gate.tryBegin(connected = true, isStreaming = true))
        gate.onAgentEnd()
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
        gate.onRecovered(isStreaming = false)
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
    }

    @Test
    fun `websocket bearer cannot leave trusted loopback origin`() {
        val base = "http://127.0.0.1:8765/".toHttpUrl()
        assertEquals(
            "http://127.0.0.1:8765/ws/rpc/lease",
            resolveTrustedWebSocketUrl(base, "/ws/rpc/lease").toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            resolveTrustedWebSocketUrl(base, "ws://example.com:8765/ws/rpc/lease")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveTrustedWebSocketUrl(base, "//example.com/ws/rpc/lease")
        }
    }

    @Test
    fun `superseded socket generation can never become current again`() {
        val gate = SocketGenerationGate()
        val first = gate.next()
        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        val replacement = gate.next()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(replacement))
    }

    @Test
    fun `invalid or revoked lease bypasses stale websocket retries`() {
        assertTrue(LeaseRecoveryPolicy.leaseIsInvalid(responseCode = 404))
        assertTrue(LeaseRecoveryPolicy.leaseIsInvalid(responseCode = 409))
        assertTrue(LeaseRecoveryPolicy.leaseIsInvalid(responseCode = 410))
        assertTrue(LeaseRecoveryPolicy.leaseIsInvalid(closeCode = 4001))
        assertFalse(LeaseRecoveryPolicy.leaseIsInvalid(responseCode = 503))
    }

    @Test
    fun `authentication rejection is terminal while network failures remain recoverable`() {
        assertTrue(LeaseRecoveryPolicy.isTerminalHandshakeFailure(401))
        assertTrue(LeaseRecoveryPolicy.isTerminalHandshakeFailure(403))
        assertFalse(LeaseRecoveryPolicy.isTerminalHandshakeFailure(404))
        assertFalse(LeaseRecoveryPolicy.isTerminalHandshakeFailure(503))
        assertFalse(LeaseRecoveryPolicy.isTerminalHandshakeFailure(null))
    }

    @Test
    fun `reconnect and lease acquisition retries are explicitly bounded`() {
        assertEquals(4, LeaseRecoveryPolicy.MAX_SOCKET_RECONNECT_ATTEMPTS)
        assertEquals(3, LeaseRecoveryPolicy.MAX_LEASE_ACQUIRE_ATTEMPTS)
        assertEquals(2, LeaseRecoveryPolicy.MAX_LEASE_REACQUIRE_CYCLES)
        assertEquals(500L, LeaseRecoveryPolicy.reconnectDelayMillis(1))
        assertEquals(4_000L, LeaseRecoveryPolicy.reconnectDelayMillis(4))
        assertEquals(2_000L, LeaseRecoveryPolicy.leaseAcquireDelayMillis(4))
    }

    private fun response(id: String, command: String, data: JSONObject) = PiResponse(
        id = id,
        command = command,
        success = true,
        data = data,
        error = null,
        rawJson = "{}",
    )
}
