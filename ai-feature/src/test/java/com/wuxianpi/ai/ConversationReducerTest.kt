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
            PiEvent.AgentStart("s", "{}"),
        )
        state = ConversationReducer.reduce(
            state,
            PiEvent.ToolStart("t", "code_runner", JSONObject(), "{}"),
        )
        state = ConversationReducer.reduce(
            state,
            PiEvent.ToolEnd("t", "code_runner", JSONObject(), true, "{}"),
        )
        assertTrue(state.isAgentRunning)
        assertTrue(state.messages.single().tools.single().status == ToolStatus.FAILED)
    }

    @Test
    fun `extension error does not stop agent`() {
        val running = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.AgentStart("s", "{}"),
        )
        val afterError = ConversationReducer.reduce(
            running,
            PiEvent.ExtensionError(null, "tool", "failed", "{}"),
        )
        assertTrue(afterError.isAgentRunning)
    }

    @Test
    fun `only agent end clears running`() {
        val running = ConversationReducer.reduce(
            ConversationState(),
            PiEvent.AgentStart("s", "{}"),
        )
        val ended = ConversationReducer.reduce(
            running,
            PiEvent.AgentEnd("s", null, null, "{}"),
        )
        assertFalse(ended.isAgentRunning)
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
            PiResponse("r", "get_messages", true, data, null, "{}"),
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
