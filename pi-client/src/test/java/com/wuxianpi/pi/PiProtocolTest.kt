package com.wuxianpi.pi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiProtocolTest {
    @Test
    fun `request uses sdk service envelope`() {
        val request = JSONObject(
            PiProtocol.request(
                id = "1",
                type = "session.prompt",
                sessionId = "session-1",
                payload = JSONObject().put("message", "hello"),
            ),
        )
        assertEquals("1", request.getString("id"))
        assertEquals("session.prompt", request.getString("type"))
        assertEquals("session-1", request.getString("sessionId"))
        assertEquals("hello", request.getJSONObject("payload").getString("message"))
    }

    @Test
    fun `structured response error retains code and message`() {
        val frame = PiProtocol.parse(
            """{"id":"r1","ok":false,"error":{"code":"PROVIDER_ERROR","message":"insufficient quota"}}""",
        ) as ParsedPiFrame.Response
        assertFalse(frame.value.success)
        assertEquals("PROVIDER_ERROR", frame.value.errorCode)
        assertEquals("insufficient quota", frame.value.error)
    }

    @Test
    fun `wrapped message update keeps session identity and sequence`() {
        val frame = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sessionPath":"/tmp/s.jsonl","sequence":9,"payload":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hi"}}}""",
        ) as ParsedPiFrame.Event
        val event = frame.value as PiEvent.TextDelta
        assertEquals("hi", event.delta)
        assertEquals("s1", event.sessionId)
        assertEquals("/tmp/s.jsonl", event.sessionPath)
        assertEquals(9L, event.sequence)
    }

    @Test
    fun `failed tool is not an agent terminal event`() {
        val frame = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sequence":2,"payload":{"type":"tool_execution_end","toolCallId":"t1","toolName":"bash","result":{},"isError":true}}""",
        ) as ParsedPiFrame.Event
        val tool = frame.value as PiEvent.ToolEnd
        assertTrue(tool.isError)
        assertEquals("t1", tool.callId)
    }

    @Test
    fun `agent end and settled remain distinct`() {
        val end = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sequence":3,"payload":{"type":"agent_end","messages":[],"willRetry":true}}""",
        ) as ParsedPiFrame.Event
        assertTrue((end.value as PiEvent.AgentEnd).willRetry)

        val settled = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sequence":4,"payload":{"type":"agent_settled"}}""",
        ) as ParsedPiFrame.Event
        assertTrue(settled.value is PiEvent.AgentSettled)
    }

    @Test
    fun `provider error from message end becomes inline runtime error`() {
        val frame = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sequence":5,"payload":{"type":"message_end","message":{"role":"assistant","errorMessage":"insufficient quota"}}}""",
        ) as ParsedPiFrame.Event
        val event = frame.value as PiEvent.RuntimeError
        assertEquals("provider", event.phase)
        assertEquals("insufficient quota", event.message)
    }

    @Test
    fun `extension request uses requestId from sdk bridge`() {
        val frame = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sequence":6,"payload":{"type":"extension_ui_request","requestId":"ui-42","method":"confirm","title":"Continue?"}}""",
        ) as ParsedPiFrame.Event
        assertEquals("ui-42", (frame.value as PiEvent.ExtensionUiRequest).requestId)
    }

    @Test
    fun `handled without agent prompt is explicit`() {
        val frame = PiProtocol.parse(
            """{"type":"agent.event","sessionId":"s1","sequence":7,"payload":{"type":"prompt_completed","handledWithoutAgent":true,"isRunning":false}}""",
        ) as ParsedPiFrame.Event
        val event = frame.value as PiEvent.PromptCompleted
        assertTrue(event.handledWithoutAgent)
        assertFalse(event.isRunning)
    }
}
