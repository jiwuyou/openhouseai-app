package com.ai.assistance.operit.rescue.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescuePiRemoteEventHubTest {
    @Test
    fun `native event is fanned out without another native poller`() = runBlocking {
        val hub = RescuePiRemoteEventHub()
        val received = async(start = CoroutineStart.UNDISPATCHED) { hub.events.first() }

        assertTrue(
            hub.publishNative(
                chatId = "rescue::pinned",
                event =
                    JSONObject()
                        .put("type", "tool_start")
                        .put("toolCallId", "call-1")
                        .put("toolName", "inspect")
                        .put("args", JSONObject().put("path", "/tmp")),
                timestampMs = 42,
            ),
        )

        assertEquals(
            RescuePiRemoteEvent(
                chatId = "rescue::pinned",
                type = "tool_start",
                toolCallId = "call-1",
                toolName = "inspect",
                argumentsJson = "{\"path\":\"/tmp\"}",
                timestampMs = 42,
            ),
            received.await(),
        )
    }
}
