package com.wuxianpi.assist

import com.wuxianpi.assist.protocol.ConversationMessage
import com.wuxianpi.assist.protocol.ConversationRole
import com.wuxianpi.assist.protocol.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistStateReducerTest {
    @Test
    fun snapshotIsHiddenUntilHostVerificationAndJoinApproval() {
        val snapshot = AssistStateAction.SnapshotReceived("chat-1", listOf(message("first")))
        val verifying = AssistUiState(
            phase = AssistConnectionPhase.VERIFYING,
            sasCode = "123456",
        )

        assertTrue(AssistStateReducer.reduce(verifying, snapshot).messages.isEmpty())

        val cryptoAuthorized = AssistStateReducer.reduce(
            verifying,
            AssistStateAction.CryptographyAuthorized,
        )
        assertTrue(AssistStateReducer.reduce(cryptoAuthorized, snapshot).messages.isEmpty())

        val joined = AssistStateReducer.reduce(
            cryptoAuthorized,
            AssistStateAction.JoinAccepted(Permission.VIEW, "Owner"),
        )
        val withSnapshot = AssistStateReducer.reduce(joined, snapshot)

        assertEquals("chat-1", withSnapshot.chatId)
        assertEquals(listOf("first"), withSnapshot.messages.map { it.id })
    }

    @Test
    fun viewPermissionNeverEnablesComposer() {
        val joined = AssistStateReducer.reduce(
            AssistUiState(phase = AssistConnectionPhase.WAITING_FOR_APPROVAL),
            AssistStateAction.JoinAccepted(Permission.VIEW, null),
        )
        val withChat = AssistStateReducer.reduce(
            joined,
            AssistStateAction.SnapshotReceived("chat-1", emptyList()),
        )

        assertTrue(withChat.isAuthorized)
        assertFalse(withChat.canSend)
    }

    @Test
    fun collaboratePermissionEnablesComposerAfterSnapshot() {
        val joined = AssistStateReducer.reduce(
            AssistUiState(phase = AssistConnectionPhase.WAITING_FOR_APPROVAL),
            AssistStateAction.JoinAccepted(Permission.COLLABORATE, null),
        )
        assertFalse(joined.canSend)

        val withChat = AssistStateReducer.reduce(
            joined,
            AssistStateAction.SnapshotReceived("chat-1", emptyList()),
        )

        assertTrue(withChat.canSend)
    }

    @Test
    fun messageUpsertReplacesStreamingMessageWithoutDuplicates() {
        val authorized = AssistUiState(
            phase = AssistConnectionPhase.AUTHORIZED,
            grantedPermission = Permission.VIEW,
            chatId = "chat-1",
            messages = listOf(message("stream", "partial")),
        )
        val updated = AssistStateReducer.reduce(
            authorized,
            AssistStateAction.MessageReceived("chat-1", message("stream", "complete")),
        )

        assertEquals(1, updated.messages.size)
        assertEquals("complete", updated.messages.single().content)
    }

    @Test
    fun reconnectClearsAuthorizationAndConversation() {
        val authorized = AssistUiState(
            phase = AssistConnectionPhase.AUTHORIZED,
            grantedPermission = Permission.COLLABORATE,
            chatId = "chat-1",
            messages = listOf(message("one")),
        )
        val reconnecting = AssistStateReducer.reduce(
            authorized,
            AssistStateAction.ConnectionStarted(reconnecting = true, attempt = 1),
        )

        assertFalse(reconnecting.isAuthorized)
        assertTrue(reconnecting.messages.isEmpty())
        assertEquals(AssistConnectionPhase.RECONNECTING, reconnecting.phase)
    }

    private fun message(id: String, content: String = id) = ConversationMessage(
        id = id,
        role = ConversationRole.ASSISTANT,
        content = content,
        timestampMs = 1,
    )
}
