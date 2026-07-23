package com.wuxianpi.pi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PiTransportInternalsTest {
    @Test
    fun `ordered queue survives pressure within capacity without loss`() = runBlocking {
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
    fun `bounded queue reports overflow`() = runBlocking {
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
    fun `prompt gate remains occupied through agent end and opens only on settled`() {
        val gate = PromptGate()
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
        assertFalse(gate.tryBegin(connected = true, isStreaming = false))
        gate.onAgentStart()
        assertFalse(gate.tryBegin(connected = true, isStreaming = true))
        // There is deliberately no agent-end transition on the gate.
        assertFalse(gate.tryBegin(connected = true, isStreaming = false))
        gate.onAgentSettled()
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
    }

    @Test
    fun `prompt handled by extension releases submission gate without agent settled`() {
        val gate = PromptGate()
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
        gate.onPromptCompletedWithoutAgent()
        assertTrue(gate.tryBegin(connected = true, isStreaming = false))
    }

    @Test
    fun `prompt acceptance has no arbitrary timeout`() {
        assertNull(PROMPT_ACCEPT_TIMEOUT_MILLIS)
    }

    @Test
    fun `foreign session events are rejected while unscoped events remain visible`() {
        assertTrue(eventBelongsToActiveSession("session-a", "session-a", activationInFlight = false))
        assertFalse(eventBelongsToActiveSession("session-a", "session-b", activationInFlight = false))
        assertTrue(eventBelongsToActiveSession("session-a", null, activationInFlight = false))
        assertFalse(eventBelongsToActiveSession(null, "session-b", activationInFlight = false))
        assertTrue(eventBelongsToActiveSession("session-a", "session-b", activationInFlight = true))
    }

    @Test
    fun `service and websocket remain fixed to loopback`() {
        val base = "http://127.0.0.1:8765/legacy/path?x=1".toHttpUrl()
        assertEquals(
            "http://127.0.0.1:8765/v1/ws",
            resolveServiceWebSocketUrl(base).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8765/api/web/v1/models/setup",
            resolveServiceHttpUrl(base, WuxianPiModelClient.MODELS_SETUP_PATH).toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireLoopbackService("https://example.com/".toHttpUrl())
        }
    }

    @Test
    fun `superseded socket generation cannot become current`() {
        val gate = SocketGenerationGate()
        val first = gate.next()
        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(gate.next()))
    }

    @Test
    fun `reconnect retries are bounded with capped backoff`() {
        assertEquals(8, ReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(250L, ReconnectPolicy.delayMillis(1))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(8))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(20))
    }
}
