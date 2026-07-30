package com.ai.assistance.operit.rescue.remote

import com.ai.assistance.operit.data.model.ChatMessage
import com.wuxianpi.assist.protocol.AssistMessage
import com.wuxianpi.assist.protocol.ConversationMessage
import com.wuxianpi.assist.protocol.ConversationRole
import com.wuxianpi.assist.protocol.ConversationSnapshot
import com.wuxianpi.assist.protocol.EndSession
import com.wuxianpi.assist.protocol.JoinDecision
import com.wuxianpi.assist.protocol.JoinRequest
import com.wuxianpi.assist.protocol.MessageUpsert
import com.wuxianpi.assist.protocol.Permission
import com.wuxianpi.assist.protocol.Ping
import com.wuxianpi.assist.protocol.Pong
import com.wuxianpi.assist.protocol.RemoteUserMessage
import com.wuxianpi.assist.protocol.RemoteUserMessageAck
import com.wuxianpi.assist.protocol.ToolFinished
import com.wuxianpi.assist.protocol.ToolStarted
import com.wuxianpi.assist.protocol.TurnState
import com.wuxianpi.assist.protocol.TurnStatus

internal class RescueAssistHostSession(
    val pinnedChatId: String,
    private val offeredPermission: Permission,
    private val historyProvider: suspend (String) -> List<ChatMessage>,
    private val remoteInputRouter: (chatId: String, text: String, requestId: String) -> String,
    private val send: (AssistMessage) -> Unit,
    private val onPeerJoined: (displayName: String, permission: Permission) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val activeToolCalls = mutableSetOf<String>()
    private var authorized = false
    private var joined = false
    private var closed = false
    private var grantedPermission: Permission? = null

    fun authorize() {
        synchronized(lock) {
            check(!closed) { "Remote assistance session is closed" }
            authorized = true
        }
    }

    suspend fun handleIncoming(message: AssistMessage) {
        val canHandle = synchronized(lock) { authorized && !closed }
        check(canHandle) { "Remote assistance session is not authorized" }

        when (message) {
            is JoinRequest -> handleJoin(message)
            is RemoteUserMessage -> handleRemoteUserMessage(message)
            is Ping -> send(Pong(message.nonce, now()))
            is EndSession -> close(sendEndMessage = false)
            else -> Unit
        }
    }

    fun publishHistoryMessage(chatId: String, message: ChatMessage) {
        if (!canPublish(chatId)) return
        send(MessageUpsert(chatId = pinnedChatId, message = message.toConversationMessage()))
    }

    fun publishPiEvent(event: RescuePiRemoteEvent) {
        if (!canPublish(event.chatId)) return
        when (event.type) {
            "text_delta", "thinking_delta" ->
                send(TurnState(pinnedChatId, TurnStatus.STREAMING, timestampMs = event.timestampMs))
            "tool_start", "host_tool_request" -> {
                val toolCallId = event.toolCallId ?: return
                val toolName = event.toolName ?: return
                val firstStart = synchronized(lock) { activeToolCalls.add(toolCallId) }
                if (firstStart) {
                    send(
                        ToolStarted(
                            chatId = pinnedChatId,
                            toolCallId = toolCallId,
                            toolName = toolName,
                            argumentsJson = event.argumentsJson,
                            timestampMs = event.timestampMs,
                        ),
                    )
                }
            }
            "tool_end" -> {
                val toolCallId = event.toolCallId ?: return
                val toolName = event.toolName ?: return
                synchronized(lock) { activeToolCalls.remove(toolCallId) }
                send(
                    ToolFinished(
                        chatId = pinnedChatId,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        content = event.content.orEmpty(),
                        isError = event.isError,
                        timestampMs = event.timestampMs,
                    ),
                )
            }
            "auto_compaction_start" ->
                send(TurnState(pinnedChatId, TurnStatus.THINKING, event.detail, event.timestampMs))
            "auto_compaction_end" ->
                send(TurnState(pinnedChatId, TurnStatus.STREAMING, event.detail, event.timestampMs))
            "agent_end", "prompt_completed" ->
                send(TurnState(pinnedChatId, TurnStatus.COMPLETED, timestampMs = event.timestampMs))
            "error", "prompt_failed" ->
                send(TurnState(pinnedChatId, TurnStatus.ERROR, event.detail, event.timestampMs))
        }
    }

    fun close(sendEndMessage: Boolean = true, reason: String? = null) {
        prepareCloseMessage(sendEndMessage, reason)?.let(send)
    }

    /**
     * Marks the application session closed while allowing the transport owner to queue the final
     * encrypted frame before it invalidates its generation and socket.
     */
    fun prepareHostStop(reason: String? = null): EndSession? =
        prepareCloseMessage(sendEndMessage = true, reason = reason)

    private fun prepareCloseMessage(sendEndMessage: Boolean, reason: String?): EndSession? {
        val shouldNotify = synchronized(lock) {
            if (closed) return null
            closed = true
            activeToolCalls.clear()
            authorized && joined && sendEndMessage
        }
        return if (shouldNotify) EndSession(reason = reason, timestampMs = now()) else null
    }

    private suspend fun handleJoin(request: JoinRequest) {
        val duplicate = synchronized(lock) { joined }
        if (duplicate) {
            send(
                JoinDecision(
                    requestId = request.requestId,
                    accepted = false,
                    reason = "A helper has already joined this sharing session",
                    timestampMs = now(),
                ),
            )
            return
        }

        val history = try {
            historyProvider(pinnedChatId)
        } catch (error: Exception) {
            send(
                JoinDecision(
                    requestId = request.requestId,
                    accepted = false,
                    reason = error.message?.takeIf(String::isNotBlank) ?: "Unable to read Rescue history",
                    timestampMs = now(),
                ),
            )
            return
        }
        val permission = grantPermission(request.requestedPermission)
        synchronized(lock) {
            joined = true
            grantedPermission = permission
        }
        onPeerJoined(request.displayName, permission)
        send(
            JoinDecision(
                requestId = request.requestId,
                accepted = true,
                grantedPermission = permission,
                hostDisplayName = "WuxianPi Rescue",
                timestampMs = now(),
            ),
        )
        send(
            ConversationSnapshot(
                chatId = pinnedChatId,
                messages = history.toConversationSnapshotMessages(),
                generatedAtMs = now(),
            ),
        )
    }

    private fun handleRemoteUserMessage(message: RemoteUserMessage) {
        val permission = synchronized(lock) { grantedPermission }
        val rejection = when {
            !synchronized(lock) { joined } -> "The helper has not joined the shared conversation"
            message.chatId != pinnedChatId -> "The shared conversation is pinned to another chat"
            permission != Permission.COLLABORATE -> "This sharing session is view-only"
            else -> null
        }
        if (rejection != null) {
            send(
                RemoteUserMessageAck(
                    requestId = message.requestId,
                    accepted = false,
                    reason = rejection,
                    timestampMs = now(),
                ),
            )
            return
        }

        try {
            val messageId = remoteInputRouter(pinnedChatId, message.text, message.requestId)
            send(
                RemoteUserMessageAck(
                    requestId = message.requestId,
                    accepted = true,
                    messageId = messageId,
                    timestampMs = now(),
                ),
            )
        } catch (error: Exception) {
            send(
                RemoteUserMessageAck(
                    requestId = message.requestId,
                    accepted = false,
                    reason = error.message?.takeIf(String::isNotBlank) ?: "Unable to send message",
                    timestampMs = now(),
                ),
            )
        }
    }

    private fun canPublish(chatId: String): Boolean = synchronized(lock) {
        authorized && joined && !closed && chatId == pinnedChatId
    }

    private fun grantPermission(requested: Permission): Permission = when {
        requested == Permission.VIEW -> Permission.VIEW
        offeredPermission == Permission.COLLABORATE -> Permission.COLLABORATE
        else -> Permission.VIEW
    }

    private fun now(): Long = clock().coerceAtLeast(0)
}

internal fun ChatMessage.toConversationMessage(idSuffix: String? = null): ConversationMessage {
    val role = when (sender.lowercase()) {
        "user" -> ConversationRole.USER
        "system" -> ConversationRole.SYSTEM
        "tool" -> ConversationRole.TOOL
        else -> ConversationRole.ASSISTANT
    }
    val suffix = idSuffix?.let { "-$it" }.orEmpty()
    return ConversationMessage(
        id = "message-$timestamp$suffix",
        role = role,
        content = content,
        timestampMs = timestamp.coerceAtLeast(0),
        isError = false,
    )
}

private fun List<ChatMessage>.toConversationSnapshotMessages(): List<ConversationMessage> {
    val duplicateCounts = groupingBy(ChatMessage::timestamp).eachCount()
    return mapIndexed { index, message ->
        message.toConversationMessage(
            idSuffix = index.toString().takeIf { duplicateCounts[message.timestamp] != 1 },
        )
    }
}
