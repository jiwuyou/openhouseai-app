package com.wuxianpi.assist.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}

@Serializable
enum class TurnStatus {
    IDLE,
    THINKING,
    STREAMING,
    EXECUTING_TOOL,
    WAITING_FOR_USER,
    COMPLETED,
    ERROR,
    CANCELLED,
}

@Serializable
data class ConversationMessage(
    val id: String,
    val role: ConversationRole,
    val content: String,
    val timestampMs: Long,
    val isError: Boolean = false,
) {
    init {
        requireIdentifier(id, "message id")
        requireTimestamp(timestampMs)
    }
}

@Serializable
sealed interface AssistMessage {
    val version: Int
}

@Serializable
@SerialName("join_request")
data class JoinRequest(
    val requestId: String,
    val peerId: String,
    val displayName: String,
    val requestedPermission: Permission,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(requestId, "request id")
        requireIdentifier(peerId, "peer id")
        requireText(displayName, "display name")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("join_decision")
data class JoinDecision(
    val requestId: String,
    val accepted: Boolean,
    val grantedPermission: Permission? = null,
    val hostDisplayName: String? = null,
    val reason: String? = null,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(requestId, "request id")
        requireTimestamp(timestampMs)
        requireOptionalText(hostDisplayName, "host display name")
        requireOptionalText(reason, "reason")
        if (accepted && grantedPermission == null) {
            throw AssistProtocolException("Accepted join decision requires grantedPermission")
        }
        if (!accepted && grantedPermission != null) {
            throw AssistProtocolException("Rejected join decision cannot grant permission")
        }
    }
}

@Serializable
@SerialName("conversation_snapshot")
data class ConversationSnapshot(
    val chatId: String,
    val messages: List<ConversationMessage>,
    val generatedAtMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(chatId, "chat id")
        requireTimestamp(generatedAtMs)
        requireUniqueMessageIds(messages)
    }
}

@Serializable
@SerialName("message_upsert")
data class MessageUpsert(
    val chatId: String,
    val message: ConversationMessage,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(chatId, "chat id")
    }
}

@Serializable
@SerialName("turn_state")
data class TurnState(
    val chatId: String,
    val state: TurnStatus,
    val detail: String? = null,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(chatId, "chat id")
        requireOptionalText(detail, "turn detail")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("tool_started")
data class ToolStarted(
    val chatId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String? = null,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(chatId, "chat id")
        requireIdentifier(toolCallId, "tool call id")
        requireIdentifier(toolName, "tool name")
        requireOptionalText(argumentsJson, "tool arguments")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("tool_finished")
data class ToolFinished(
    val chatId: String,
    val toolCallId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(chatId, "chat id")
        requireIdentifier(toolCallId, "tool call id")
        requireIdentifier(toolName, "tool name")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("remote_user_message")
data class RemoteUserMessage(
    val requestId: String,
    val chatId: String,
    val text: String,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(requestId, "request id")
        requireIdentifier(chatId, "chat id")
        requireText(text, "message text")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("remote_user_message_ack")
data class RemoteUserMessageAck(
    val requestId: String,
    val accepted: Boolean,
    val messageId: String? = null,
    val reason: String? = null,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(requestId, "request id")
        requireOptionalIdentifier(messageId, "message id")
        requireOptionalText(reason, "reason")
        requireTimestamp(timestampMs)
        if (accepted && messageId == null) {
            throw AssistProtocolException("Accepted remote message requires messageId")
        }
        if (!accepted && reason == null) {
            throw AssistProtocolException("Rejected remote message requires reason")
        }
    }
}

@Serializable
@SerialName("peer_left")
data class PeerLeft(
    val reason: String? = null,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireOptionalText(reason, "reason")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("end_session")
data class EndSession(
    val reason: String? = null,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireOptionalText(reason, "reason")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("ping")
data class Ping(
    val nonce: String,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(nonce, "ping nonce")
        requireTimestamp(timestampMs)
    }
}

@Serializable
@SerialName("pong")
data class Pong(
    val nonce: String,
    val timestampMs: Long,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
) : AssistMessage {
    init {
        requireVersion(version)
        requireIdentifier(nonce, "pong nonce")
        requireTimestamp(timestampMs)
    }
}

internal fun requireVersion(version: Int) {
    if (version != ASSIST_PROTOCOL_VERSION) {
        throw AssistProtocolException("Unsupported assist protocol version: $version")
    }
}

private fun requireUniqueMessageIds(messages: List<ConversationMessage>) {
    if (messages.map { it.id }.toSet().size != messages.size) {
        throw AssistProtocolException("Conversation snapshot contains duplicate message ids")
    }
}

private fun requireTimestamp(value: Long) {
    if (value < 0) {
        throw AssistProtocolException("timestampMs cannot be negative")
    }
}

private fun requireIdentifier(value: String, field: String) {
    if (value.isBlank() || value.length > 512) {
        throw AssistProtocolException("$field must be non-blank and at most 512 characters")
    }
}

private fun requireOptionalIdentifier(value: String?, field: String) {
    if (value != null) requireIdentifier(value, field)
}

private fun requireText(value: String, field: String) {
    if (value.isBlank()) {
        throw AssistProtocolException("$field must be non-blank")
    }
}

private fun requireOptionalText(value: String?, field: String) {
    if (value != null) requireText(value, field)
}
