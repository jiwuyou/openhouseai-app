package com.wuxianpi.pi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PiDiagnosticsTest {
    @Test
    fun `ordinary mode stores metadata but not frame detail or secrets`() {
        val directory = Files.createTempDirectory("pi-diagnostics-test").toFile()
        RollingJsonlDiagnostics(directory, maxBytes = 64 * 1024).use { logger ->
            logger.record(
                "frame.inbound",
                fields = mapOf("type" to "agent.event", "token" to "secret-token"),
                detailedJson = """{"type":"message_update","delta":"private text","apiKey":"secret-key"}""",
            )
            val output = logger.snapshotJsonl().toString(Charsets.UTF_8)
            assertTrue(output.contains("agent.event"))
            assertTrue(output.contains("<redacted>"))
            assertFalse(output.contains("secret-token"))
            assertFalse(output.contains("secret-key"))
            assertFalse(output.contains("private text"))
        }
    }

    @Test
    fun `detailed mode includes sanitized frame and expires within limit`() {
        val directory = Files.createTempDirectory("pi-diagnostics-detail").toFile()
        RollingJsonlDiagnostics(directory, maxBytes = 64 * 1024).use { logger ->
            logger.enableDetailedMode(999_999)
            assertTrue(logger.detailedUntilMillis() <= System.currentTimeMillis() + MAX_DIAGNOSTIC_DETAIL_MS)
            logger.record(
                "frame.outbound",
                detailedJson = """{"type":"model.login","payload":{"apiKey":"secret","value":"visible-detail","content":"hidden-body","authorization":"Bearer abcdef","note":"sk-1234567890abcdef"}}""",
            )
            val output = logger.snapshotJsonl().toString(Charsets.UTF_8)
            assertTrue(output.contains("visible-detail"))
            assertTrue(output.contains("<redacted>"))
            assertTrue(output.contains("<redacted-body>"))
            assertFalse(output.contains("\"secret\""))
            assertFalse(output.contains("hidden-body"))
            assertFalse(output.contains("abcdef"))
            assertFalse(output.contains("sk-1234567890abcdef"))
        }
    }

    @Test
    fun `runtime ready and ack use frozen capability fields`() {
        val ready = parseRuntimeReady(
            """{"type":"runtime.ready","connectionId":"c-1","version":"1","protocol":"wuxianpi-sdk-v1","protocolVersion":2,"capabilities":{"eventAck":2,"eventStreamId":1,"persistentDiagnostics":1,"multiSessionSubscriptions":1}}""",
        )!!
        assertTrue(ready.capabilities.eventAck)
        assertTrue(ready.capabilities.eventStreamId)
        assertTrue(ready.capabilities.persistentDiagnostics)
        assertTrue(ready.capabilities.multiSessionSubscriptions)
        assertEquals(2, ready.protocolVersion)
        val ack = JSONObject(buildEventAckRequest("a1", "s1", "c-1", "stream-1", 7, "agent_settled"))
        assertEquals("event.ack", ack.getString("type"))
        assertEquals("s1", ack.getString("sessionId"))
        val payload = ack.getJSONObject("payload")
        assertEquals("c-1", payload.getString("connectionId"))
        assertEquals("stream-1", payload.getString("eventStreamId"))
        assertEquals("s1", payload.getString("sessionId"))
        assertEquals(7L, payload.getLong("sequence"))
    }

    @Test
    fun `only terminal events with capability enabled are acknowledged`() {
        val enabled = PiRuntimeReady(
            "c",
            null,
            WUXIANPI_PROTOCOL_VERSION,
            2,
            PiRuntimeCapabilities(
                eventAck = true,
                eventStreamId = true,
                persistentDiagnostics = true,
                multiSessionSubscriptions = true,
            ),
        )
        val disabled = enabled.copy(capabilities = enabled.capabilities.copy(eventAck = false))
        val start = PiEvent.AgentStart(sessionId = "s", rawJson = "{}")
        val settled = PiEvent.AgentSettled(sessionId = "s", rawJson = "{}")
        val stillRunning = PiEvent.PromptCompleted(false, true, sessionId = "s", rawJson = "{}")
        val completed = PiEvent.PromptCompleted(true, false, sessionId = "s", rawJson = "{}")
        assertFalse(shouldSendEventAck(enabled, start))
        assertTrue(shouldSendEventAck(enabled, settled))
        assertFalse(shouldSendEventAck(enabled, stillRunning))
        assertTrue(shouldSendEventAck(enabled, completed))
        assertFalse(shouldSendEventAck(disabled, settled))
    }

    @Test
    fun `ack responses are isolated and pending tracker is bounded and clearable`() {
        assertTrue(isAckResponseId("ack-1"))
        assertFalse(isAckResponseId("android-1"))
        val tracker = PendingAckTracker(capacity = 2)
        assertEquals(null, tracker.add("ack-1"))
        assertEquals(null, tracker.add("ack-2"))
        assertEquals("ack-1", tracker.add("ack-3"))
        assertEquals(2, tracker.size())
        assertFalse(tracker.remove("ack-1"))
        tracker.clear()
        assertEquals(0, tracker.size())
    }

    @Test
    fun `delta and tool updates aggregate until periodic or terminal flush`() {
        val textMetadata = parseWireFrameMetadata(
            """{"type":"agent.event","sessionId":"s","sequence":1,"payload":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hidden"}}}""",
        )
        assertEquals("text_delta", diagnosticAggregationCategory(textMetadata))
        val aggregator = DiagnosticEventAggregator(flushIntervalMs = 10_000, flushCount = 3)
        assertEquals(null, aggregator.add("text_delta", 10, 1_000))
        assertEquals(null, aggregator.add("thinking_delta", 20, 1_001))
        val periodic = aggregator.add("tool_update", 30, 1_002)!!
        assertEquals("periodic", periodic.reason)
        assertEquals(3L, periodic.counts.values.sum())
        aggregator.add("text_delta", 5, 2_000)
        val terminal = aggregator.drain("terminal", 2_001)!!
        assertEquals("terminal", terminal.reason)
        assertEquals(1L, terminal.counts["text_delta"])
    }

    @Test
    fun `rolling jsonl remains bounded`() {
        val directory = Files.createTempDirectory("pi-diagnostics-bounded").toFile()
        RollingJsonlDiagnostics(directory, maxBytes = 32 * 1024, queueCapacity = 2_000).use { logger ->
            repeat(1_000) { index ->
                logger.record("bounded.entry", mapOf("index" to index, "padding" to "x".repeat(80)))
            }
            val snapshot = logger.snapshotJsonl()
            assertTrue(snapshot.size <= 40 * 1024)
        }
    }

    @Test
    fun `node export accepts content without reading private path`() {
        val export = PiNodeDiagnosticsExport.from(
            JSONObject().put("content", "{\"event\":\"ready\"}\n").put("path", "/private/node.jsonl").put("size", 18),
        )
        assertTrue(export.content.contains("ready"))
        assertEquals("/private/node.jsonl", export.path)
    }
}
