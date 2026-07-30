package com.wuxianpi.assist

import com.wuxianpi.assist.protocol.ConversationMessage
import com.wuxianpi.assist.protocol.Invite
import com.wuxianpi.assist.protocol.Permission
import com.wuxianpi.assist.protocol.TurnStatus

enum class AssistConnectionPhase {
    IDLE,
    CONNECTING,
    WAITING_FOR_HOST,
    VERIFYING,
    WAITING_FOR_APPROVAL,
    AUTHORIZED,
    RECONNECTING,
    PEER_LEFT,
    ENDED,
    ERROR,
}

enum class AssistActivityKind {
    TOOL,
    STATE,
    MESSAGE_STATUS,
    ERROR,
}

data class AssistActivityItem(
    val id: String,
    val kind: AssistActivityKind,
    val title: String,
    val detail: String? = null,
    val timestampMs: Long,
    val isError: Boolean = false,
)

data class PendingRemoteMessage(
    val requestId: String,
    val text: String,
    val timestampMs: Long,
)

data class AssistUiState(
    val inviteText: String = "",
    val invite: Invite? = null,
    val requestedPermission: Permission = Permission.COLLABORATE,
    val phase: AssistConnectionPhase = AssistConnectionPhase.IDLE,
    val statusText: String = "Paste an invitation to begin",
    val sasCode: String? = null,
    val hostFingerprint: String? = null,
    val grantedPermission: Permission? = null,
    val chatId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val activity: List<AssistActivityItem> = emptyList(),
    val turnStatus: TurnStatus? = null,
    val turnDetail: String? = null,
    val pendingMessages: Map<String, PendingRemoteMessage> = emptyMap(),
    val errorMessage: String? = null,
    val reconnectAttempt: Int = 0,
) {
    val isAuthorized: Boolean
        get() = phase == AssistConnectionPhase.AUTHORIZED && grantedPermission != null

    val canSend: Boolean
        get() = isAuthorized && grantedPermission == Permission.COLLABORATE && chatId != null
}

sealed interface AssistStateAction {
    data class InviteChanged(val text: String) : AssistStateAction
    data class InviteAccepted(val raw: String, val invite: Invite) : AssistStateAction
    data class InviteRejected(val raw: String, val reason: String) : AssistStateAction
    data class PermissionSelected(val permission: Permission) : AssistStateAction
    data class ConnectionStarted(val reconnecting: Boolean, val attempt: Int) : AssistStateAction
    data object SocketOpened : AssistStateAction
    data object HostConnected : AssistStateAction
    data class VerificationPending(val sas: String, val hostFingerprint: String) : AssistStateAction
    data object CryptographyAuthorized : AssistStateAction
    data class JoinAccepted(val permission: Permission, val hostName: String?) : AssistStateAction
    data class JoinRejected(val reason: String) : AssistStateAction
    data class SnapshotReceived(val chatId: String, val messages: List<ConversationMessage>) : AssistStateAction
    data class MessageReceived(val chatId: String, val message: ConversationMessage) : AssistStateAction
    data class TurnChanged(
        val chatId: String,
        val status: TurnStatus,
        val detail: String?,
        val timestampMs: Long,
    ) : AssistStateAction

    data class ActivityReceived(val chatId: String, val item: AssistActivityItem) : AssistStateAction
    data class MessageQueued(val pending: PendingRemoteMessage) : AssistStateAction
    data class MessageAcknowledged(
        val requestId: String,
        val accepted: Boolean,
        val reason: String?,
        val timestampMs: Long,
    ) : AssistStateAction

    data class Reconnecting(val reason: String, val attempt: Int) : AssistStateAction
    data class PeerLeft(val reconnecting: Boolean) : AssistStateAction
    data class SessionEnded(val reason: String?) : AssistStateAction
    data class Failed(val reason: String, val terminal: Boolean = false) : AssistStateAction
}

object AssistStateReducer {
    fun reduce(state: AssistUiState, action: AssistStateAction): AssistUiState = when (action) {
        is AssistStateAction.InviteChanged -> state.copy(
            inviteText = action.text,
            errorMessage = null,
        )

        is AssistStateAction.InviteAccepted -> state.copy(
            inviteText = action.raw,
            invite = action.invite,
            phase = AssistConnectionPhase.IDLE,
            statusText = "Invitation ready",
            errorMessage = null,
        )

        is AssistStateAction.InviteRejected -> state.copy(
            inviteText = action.raw,
            invite = null,
            phase = AssistConnectionPhase.ERROR,
            statusText = "Invalid invitation",
            errorMessage = action.reason,
        )

        is AssistStateAction.PermissionSelected -> state.copy(requestedPermission = action.permission)

        is AssistStateAction.ConnectionStarted -> state.copy(
            phase = if (action.reconnecting) {
                AssistConnectionPhase.RECONNECTING
            } else {
                AssistConnectionPhase.CONNECTING
            },
            statusText = if (action.reconnecting) "Reconnecting" else "Connecting to relay",
            sasCode = null,
            hostFingerprint = state.invite?.hostIdentity?.fingerprint,
            grantedPermission = null,
            chatId = null,
            messages = emptyList(),
            activity = emptyList(),
            turnStatus = null,
            turnDetail = null,
            pendingMessages = emptyMap(),
            errorMessage = null,
            reconnectAttempt = action.attempt,
        )

        AssistStateAction.SocketOpened -> state.copy(
            phase = AssistConnectionPhase.WAITING_FOR_HOST,
            statusText = "Waiting for the Rescue AI device",
            errorMessage = null,
        )

        AssistStateAction.HostConnected -> state.copy(statusText = "Securing the connection")

        is AssistStateAction.VerificationPending -> state.copy(
            phase = AssistConnectionPhase.VERIFYING,
            statusText = "Send this code through your trusted channel",
            sasCode = action.sas,
            hostFingerprint = action.hostFingerprint,
            errorMessage = null,
        )

        AssistStateAction.CryptographyAuthorized -> state.copy(
            phase = AssistConnectionPhase.WAITING_FOR_APPROVAL,
            statusText = "Code verified. Waiting for access approval",
            errorMessage = null,
        )

        is AssistStateAction.JoinAccepted -> state.copy(
            phase = AssistConnectionPhase.AUTHORIZED,
            statusText = action.hostName?.let { "Connected to $it" } ?: "Connected",
            grantedPermission = action.permission,
            errorMessage = null,
            reconnectAttempt = 0,
        )

        is AssistStateAction.JoinRejected -> clearedSession(state).copy(
            phase = AssistConnectionPhase.ENDED,
            statusText = "Access was not granted",
            errorMessage = action.reason,
        )

        is AssistStateAction.SnapshotReceived -> {
            if (!state.isAuthorized) state else state.copy(
                chatId = action.chatId,
                messages = action.messages,
            )
        }

        is AssistStateAction.MessageReceived -> {
            if (!state.isAuthorized || state.chatId != action.chatId) {
                state
            } else {
                state.copy(messages = upsertMessage(state.messages, action.message))
            }
        }

        is AssistStateAction.TurnChanged -> {
            if (!state.isAuthorized || state.chatId != action.chatId) {
                state
            } else {
                state.copy(
                    turnStatus = action.status,
                    turnDetail = action.detail,
                    activity = appendActivity(
                        state.activity,
                        AssistActivityItem(
                            id = "turn-${action.timestampMs}-${action.status}",
                            kind = AssistActivityKind.STATE,
                            title = action.status.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            detail = action.detail,
                            timestampMs = action.timestampMs,
                            isError = action.status == TurnStatus.ERROR,
                        ),
                    ),
                )
            }
        }

        is AssistStateAction.ActivityReceived -> {
            if (!state.isAuthorized || state.chatId != action.chatId) {
                state
            } else {
                state.copy(activity = appendActivity(state.activity, action.item))
            }
        }

        is AssistStateAction.MessageQueued -> {
            if (!state.canSend) state else state.copy(
                pendingMessages = state.pendingMessages + (action.pending.requestId to action.pending),
            )
        }

        is AssistStateAction.MessageAcknowledged -> {
            val pending = state.pendingMessages[action.requestId] ?: return state
            val title = if (action.accepted) "Message delivered" else "Message rejected"
            state.copy(
                pendingMessages = state.pendingMessages - action.requestId,
                activity = appendActivity(
                    state.activity,
                    AssistActivityItem(
                        id = "ack-${action.requestId}",
                        kind = if (action.accepted) {
                            AssistActivityKind.MESSAGE_STATUS
                        } else {
                            AssistActivityKind.ERROR
                        },
                        title = title,
                        detail = action.reason ?: pending.text.take(120),
                        timestampMs = action.timestampMs,
                        isError = !action.accepted,
                    ),
                ),
                errorMessage = if (action.accepted) null else action.reason,
            )
        }

        is AssistStateAction.Reconnecting -> state.copy(
            phase = AssistConnectionPhase.RECONNECTING,
            statusText = "Reconnecting (attempt ${action.attempt})",
            sasCode = null,
            grantedPermission = null,
            chatId = null,
            pendingMessages = emptyMap(),
            errorMessage = action.reason,
            reconnectAttempt = action.attempt,
        )

        is AssistStateAction.PeerLeft -> state.copy(
            phase = if (action.reconnecting) {
                AssistConnectionPhase.RECONNECTING
            } else {
                AssistConnectionPhase.PEER_LEFT
            },
            statusText = if (action.reconnecting) "The Rescue AI device left. Reconnecting" else "Peer left",
            sasCode = null,
            grantedPermission = null,
            chatId = null,
            pendingMessages = emptyMap(),
        )

        is AssistStateAction.SessionEnded -> clearedSession(state).copy(
            phase = AssistConnectionPhase.ENDED,
            statusText = action.reason ?: "Session ended",
        )

        is AssistStateAction.Failed -> state.copy(
            phase = if (action.terminal) AssistConnectionPhase.ERROR else state.phase,
            statusText = if (action.terminal) "Unable to continue" else state.statusText,
            errorMessage = action.reason,
        )
    }

    private fun upsertMessage(
        messages: List<ConversationMessage>,
        message: ConversationMessage,
    ): List<ConversationMessage> {
        val index = messages.indexOfFirst { it.id == message.id }
        return if (index < 0) {
            messages + message
        } else {
            messages.toMutableList().apply { set(index, message) }
        }
    }

    private fun appendActivity(
        activity: List<AssistActivityItem>,
        item: AssistActivityItem,
    ): List<AssistActivityItem> = (activity + item).takeLast(MAX_ACTIVITY_ITEMS)

    private fun clearedSession(state: AssistUiState): AssistUiState = state.copy(
        sasCode = null,
        grantedPermission = null,
        chatId = null,
        messages = emptyList(),
        activity = emptyList(),
        turnStatus = null,
        turnDetail = null,
        pendingMessages = emptyMap(),
    )

    private const val MAX_ACTIVITY_ITEMS = 500
}
