package com.wuxianpi.ai

import com.wuxianpi.pi.PiEvent
import com.wuxianpi.pi.PiResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReducerTest {
    @Test
    fun `failed tool does not stop agent`() {
        var state = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.AgentStart(sessionId = "s", rawJson = "{}"),
        )
        state = ConversationReducer.reduce(
            state,
            PiEvent.ToolStart(callId = "t", name = "code_runner", arguments = JSONObject(), rawJson = "{}"),
        )
        state = ConversationReducer.reduce(
            state,
            PiEvent.ToolEnd(
                callId = "t",
                name = "code_runner",
                result = JSONObject(),
                isError = true,
                rawJson = "{}",
            ),
        )
        assertTrue(state.isAgentRunning)
        assertTrue(state.messages.single().tools.single().status == ToolStatus.FAILED)
    }

    @Test
    fun `extension error does not stop agent`() {
        val running = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.AgentStart(sessionId = "s", rawJson = "{}"),
        )
        val afterError = ConversationReducer.reduce(
            running,
            PiEvent.ExtensionError(extensionId = null, event = "tool", error = "failed", rawJson = "{}"),
        )
        assertTrue(afterError.isAgentRunning)
    }

    @Test
    fun `fire and forget extension notification never opens response dialog`() {
        val state = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.ExtensionUiRequest(
                requestId = "notification-1",
                method = "notify",
                payload = JSONObject()
                    .put("message", "Package installed")
                    .put("notifyType", "info"),
                sessionId = "s",
                rawJson = "{}",
            ),
        )
        assertEquals(null, state.extensionRequest)
        assertEquals("Package installed", state.messages.single().text)
    }

    @Test
    fun `interactive extension request opens dialog`() {
        val request = PiEvent.ExtensionUiRequest(
            requestId = "confirm-1",
            method = "confirm",
            payload = JSONObject().put("title", "Continue?"),
            sessionId = "s",
            rawJson = "{}",
        )
        val state = ConversationReducer.reduce(ConversationState(), request)
        assertEquals(request, state.extensionRequest)
    }

    @Test
    fun `agent end stays running until agent settled`() {
        val running = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.AgentStart(sessionId = "s", rawJson = "{}"),
        )
        val lowLevelEnded = ConversationReducer.reduce(
            running,
            PiEvent.AgentEnd(sessionId = "s", willRetry = true, messages = null, rawJson = "{}"),
        )
        assertTrue(lowLevelEnded.isAgentRunning)
        val settled = ConversationReducer.reduce(
            lowLevelEnded,
            PiEvent.AgentSettled(sessionId = "s", rawJson = "{}"),
        )
        assertFalse(settled.isAgentRunning)
    }

    @Test
    fun `provider error is inline and does not settle conversation`() {
        val running = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.AgentStart(sessionId = "s", rawJson = "{}"),
        )
        val failed = ConversationReducer.reduce(
            running,
            PiEvent.RuntimeError(
                phase = "provider",
                commandType = "session.prompt",
                message = "insufficient quota",
                recoverable = true,
                sessionId = "s",
                rawJson = "{}",
            ),
        )
        assertTrue(failed.isAgentRunning)
        assertTrue(failed.messages.last().isError)
        assertTrue(failed.messages.last().text.contains("insufficient quota"))
    }

    @Test
    fun `history restores Pi thinking field and current text prefix`() {
        val data = JSONObject().put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "assistant")
                    .put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "thinking").put("thinking", "reasoning"))
                            .put(JSONObject().put("type", "text").put("text", "partial answer")),
                    ),
            ),
        )
        val restored = ConversationReducer.restore(
            PiResponse("r", "session.history", true, data, null, "{}"),
            ConversationState(),
        )
        assertEquals("reasoning", restored.messages.single().thinking)
        assertEquals("partial answer", restored.messages.single().text)
    }

    @Test
    fun `cold start recovery streaming true makes stop state visible`() {
        val restored = ConversationReducer.setAgentRunning(
            ConversationState(messages = listOf(ChatMessageState(role = MessageRole.ASSISTANT))),
            active = true,
        )
        assertTrue(restored.isAgentRunning)
    }
}
