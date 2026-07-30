package com.ai.assistance.operit.rescue.remote

import com.ai.assistance.operit.data.model.ChatMessage
import com.wuxianpi.assist.protocol.AssistMessage
import com.wuxianpi.assist.protocol.ConversationSnapshot
import com.wuxianpi.assist.protocol.EndSession
import com.wuxianpi.assist.protocol.JoinDecision
import com.wuxianpi.assist.protocol.JoinRequest
import com.wuxianpi.assist.protocol.MessageUpsert
import com.wuxianpi.assist.protocol.Permission
import com.wuxianpi.assist.protocol.RemoteUserMessage
import com.wuxianpi.assist.protocol.RemoteUserMessageAck
import com.wuxianpi.assist.protocol.ToolStarted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueAssistHostSessionTest {
    @Test
    fun `join sends pinned history snapshot and ignores other chat updates`() = runBlocking {
        val sent = mutableListOf<AssistMessage>()
        val requestedHistory = mutableListOf<String>()
        val session =
            RescueAssistHostSession(
                pinnedChatId = "rescue::pinned",
                offeredPermission = Permission.VIEW,
                historyProvider = { chatId ->
                    requestedHistory += chatId
                    listOf(
                        ChatMessage(sender = "user", content = "help", timestamp = 10),
                        ChatMessage(sender = "ai", content = "ready", timestamp = 11),
                    )
                },
                remoteInputRouter = { _, _, _ -> error("view session must not route input") },
                send = sent::add,
                clock = { 100 },
            )
        session.authorize()

        session.handleIncoming(joinRequest(Permission.VIEW))
        session.publishHistoryMessage(
            "rescue::other",
            ChatMessage(sender = "ai", content = "not shared", timestamp = 12),
        )
        session.publishHistoryMessage(
            "rescue::pinned",
            ChatMessage(sender = "ai", content = "shared", timestamp = 13),
        )

        assertEquals(listOf("rescue::pinned"), requestedHistory)
        assertTrue(sent[0] is JoinDecision && (sent[0] as JoinDecision).accepted)
        val snapshot = sent[1] as ConversationSnapshot
        assertEquals("rescue::pinned", snapshot.chatId)
        assertEquals(listOf("help", "ready"), snapshot.messages.map { it.content })
        assertEquals(1, sent.filterIsInstance<MessageUpsert>().size)
        assertEquals("shared", sent.filterIsInstance<MessageUpsert>().single().message.content)
    }

    @Test
    fun `view permission rejects remote input without routing it`() = runBlocking {
        val sent = mutableListOf<AssistMessage>()
        var routed = false
        val session =
            session(
                permission = Permission.VIEW,
                sent = sent,
                inputRouter = { _, _, _ ->
                    routed = true
                    "unexpected"
                },
            )
        session.authorize()
        session.handleIncoming(joinRequest(Permission.COLLABORATE))
        session.handleIncoming(remoteMessage(chatId = "rescue::pinned"))

        val ack = sent.filterIsInstance<RemoteUserMessageAck>().single()
        assertFalse(ack.accepted)
        assertTrue(ack.reason!!.contains("view-only"))
        assertFalse(routed)
        assertEquals(Permission.VIEW, sent.filterIsInstance<JoinDecision>().single().grantedPermission)
    }

    @Test
    fun `collaborate input is always routed to pinned chat`() = runBlocking {
        val sent = mutableListOf<AssistMessage>()
        val routed = mutableListOf<Triple<String, String, String>>()
        val session =
            session(
                permission = Permission.COLLABORATE,
                sent = sent,
                inputRouter = { chatId, text, requestId ->
                    routed += Triple(chatId, text, requestId)
                    "local-message-id"
                },
            )
        session.authorize()
        session.handleIncoming(joinRequest(Permission.COLLABORATE))
        session.handleIncoming(remoteMessage(chatId = "rescue::pinned"))

        assertEquals(
            listOf(Triple("rescue::pinned", "please inspect", "remote-request")),
            routed,
        )
        val ack = sent.filterIsInstance<RemoteUserMessageAck>().single()
        assertTrue(ack.accepted)
        assertEquals("local-message-id", ack.messageId)
    }

    @Test
    fun `wrong chat id is rejected even for collaborate`() = runBlocking {
        val sent = mutableListOf<AssistMessage>()
        var routed = false
        val session =
            session(
                permission = Permission.COLLABORATE,
                sent = sent,
                inputRouter = { _, _, _ ->
                    routed = true
                    "unexpected"
                },
            )
        session.authorize()
        session.handleIncoming(joinRequest(Permission.COLLABORATE))
        session.handleIncoming(remoteMessage(chatId = "rescue::locally-selected-later"))

        assertFalse(routed)
        assertFalse(sent.filterIsInstance<RemoteUserMessageAck>().single().accepted)
    }

    @Test
    fun `application messages require local authorization`() {
        val session = session(Permission.COLLABORATE, mutableListOf()) { _, _, _ -> "id" }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.handleIncoming(joinRequest(Permission.COLLABORATE)) }
        }
    }

    @Test
    fun `duplicate native tool starts fan out once`() = runBlocking {
        val sent = mutableListOf<AssistMessage>()
        val session = session(Permission.VIEW, sent) { _, _, _ -> "id" }
        session.authorize()
        session.handleIncoming(joinRequest(Permission.VIEW))
        val event =
            RescuePiRemoteEvent(
                chatId = "rescue::pinned",
                type = "tool_start",
                toolCallId = "tool-1",
                toolName = "inspect",
                argumentsJson = "{}",
                timestampMs = 20,
            )

        session.publishPiEvent(event)
        session.publishPiEvent(event.copy(type = "host_tool_request"))

        assertEquals(1, sent.filterIsInstance<ToolStarted>().size)
    }

    @Test
    fun `explicit host stop yields one EndSession before transport disconnect`() = runBlocking {
        val sent = mutableListOf<AssistMessage>()
        val session = session(Permission.VIEW, sent) { _, _, _ -> "id" }
        session.authorize()
        session.handleIncoming(joinRequest(Permission.VIEW))
        val order = mutableListOf<String>()

        session.prepareHostStop("finished")?.let { message ->
            assertTrue(message is EndSession)
            order += "end_session"
        }
        order += "disconnect"

        assertEquals(listOf("end_session", "disconnect"), order)
        assertNull(session.prepareHostStop("duplicate"))
    }

    private fun session(
        permission: Permission,
        sent: MutableList<AssistMessage>,
        inputRouter: (String, String, String) -> String,
    ): RescueAssistHostSession =
        RescueAssistHostSession(
            pinnedChatId = "rescue::pinned",
            offeredPermission = permission,
            historyProvider = { emptyList() },
            remoteInputRouter = inputRouter,
            send = sent::add,
            clock = { 100 },
        )

    private fun joinRequest(permission: Permission) =
        JoinRequest(
            requestId = "join-request",
            peerId = "helper-peer",
            displayName = "Helper",
            requestedPermission = permission,
            timestampMs = 1,
        )

    private fun remoteMessage(chatId: String) =
        RemoteUserMessage(
            requestId = "remote-request",
            chatId = chatId,
            text = "please inspect",
            timestampMs = 2,
        )
}
