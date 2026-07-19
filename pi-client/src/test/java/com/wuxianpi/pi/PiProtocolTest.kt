package com.wuxianpi.pi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiProtocolTest {
    @Test
    fun `request keeps native Pi fields at top level`() {
        val request = JSONObject(PiProtocol.request("1", "prompt", JSONObject().put("message", "hello")))
        assertEquals("1", request.getString("id"))
        assertEquals("prompt", request.getString("type"))
        assertEquals("hello", request.getString("message"))
        assertTrue(!request.has("params"))
    }

    @Test
    fun `message update extracts native nested text delta`() {
        val frame = PiProtocol.parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hi"}}""",
        )
        assertTrue(frame is ParsedPiFrame.Event)
        assertEquals("hi", ((frame as ParsedPiFrame.Event).value as PiEvent.TextDelta).delta)
    }

    @Test
    fun `failed tool is an event and not an agent end`() {
        val frame = PiProtocol.parse(
            """{"type":"tool_execution_end","toolCallId":"t1","toolName":"bash","result":{},"isError":true}""",
        ) as ParsedPiFrame.Event
        val tool = frame.value as PiEvent.ToolEnd
        assertTrue(tool.isError)
        assertEquals("t1", tool.callId)
    }

    @Test
    fun `provider failure is retained on agent end`() {
        val frame = PiProtocol.parse(
            """{"type":"agent_end","sessionId":"s1","messages":[],"error":"insufficient_quota"}""",
        ) as ParsedPiFrame.Event
        assertEquals("insufficient_quota", (frame.value as PiEvent.AgentEnd).error)
    }
}
