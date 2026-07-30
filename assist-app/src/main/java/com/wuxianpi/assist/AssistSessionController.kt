package com.wuxianpi.assist

import com.wuxianpi.assist.protocol.AssistHandshake
import com.wuxianpi.assist.protocol.AssistHello
import com.wuxianpi.assist.protocol.AssistMessage
import com.wuxianpi.assist.protocol.AssistProtocolException
import com.wuxianpi.assist.protocol.AssistWebSocketTransport
import com.wuxianpi.assist.protocol.AuthorizedAssistCryptoSession
import com.wuxianpi.assist.protocol.ConversationSnapshot
import com.wuxianpi.assist.protocol.EndSession
import com.wuxianpi.assist.protocol.HandshakeMessage
import com.wuxianpi.assist.protocol.HostHello
import com.wuxianpi.assist.protocol.IdentitySigner
import com.wuxianpi.assist.protocol.JoinDecision
import com.wuxianpi.assist.protocol.JoinRequest
import com.wuxianpi.assist.protocol.MessageUpsert
import com.wuxianpi.assist.protocol.OkHttpAssistWebSocketTransport
import com.wuxianpi.assist.protocol.PeerLeft
import com.wuxianpi.assist.protocol.Permission
import com.wuxianpi.assist.protocol.Ping
import com.wuxianpi.assist.protocol.Pong
import com.wuxianpi.assist.protocol.RelayConnectionConfig
import com.wuxianpi.assist.protocol.RelayControlEvent
import com.wuxianpi.assist.protocol.RelayEnvelope
import com.wuxianpi.assist.protocol.RelayPeerLeft
import com.wuxianpi.assist.protocol.RelayPeerState
import com.wuxianpi.assist.protocol.RelayPeerStatus
import com.wuxianpi.assist.protocol.RemoteUserMessage
import com.wuxianpi.assist.protocol.RemoteUserMessageAck
import com.wuxianpi.assist.protocol.Role
import com.wuxianpi.assist.protocol.ToolFinished
import com.wuxianpi.assist.protocol.ToolStarted
import com.wuxianpi.assist.protocol.TurnState
import com.wuxianpi.assist.protocol.VerificationRequired
import com.wuxianpi.assist.protocol.VerificationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AssistSessionController(
    private val scope: CoroutineScope,
    private val signerProvider: () -> IdentitySigner,
    private val transport: AssistWebSocketTransport = OkHttpAssistWebSocketTransport(
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build(),
    ),
) {
    private val stateLock = Any()
    private val _state = MutableStateFlow(AssistUiState())
    val state: StateFlow<AssistUiState> = _state.asStateFlow()

    private var generation = 0L
    private var connection: AssistWebSocketTransport.Connection? = null
    private var handshake: AssistHandshake? = null
    private var cryptoSession: AuthorizedAssistCryptoSession? = null
    private var identitySigner: IdentitySigner? = null
    private var joinRequestId: String? = null
    private var reconnectJob: Job? = null
    private var userEnded = false

    fun updateInviteText(value: String) {
        dispatch(AssistStateAction.InviteChanged(value))
    }

    fun acceptInvite(raw: String, autoConnect: Boolean = false): Boolean {
        val invite = try {
            InviteInput.parse(raw)
        } catch (error: Throwable) {
            dispatch(
                AssistStateAction.InviteRejected(
                    raw = raw,
                    reason = error.message ?: "The invitation could not be read",
                ),
            )
            return false
        }
        dispatch(AssistStateAction.InviteAccepted(raw.trim(), invite))
        if (autoConnect) connect()
        return true
    }

    fun selectPermission(permission: Permission) {
        if (_state.value.phase in EDITABLE_PHASES) {
            dispatch(AssistStateAction.PermissionSelected(permission))
        }
    }

    fun connect() {
        if (_state.value.invite == null && !acceptInvite(_state.value.inviteText)) return
        userEnded = false
        beginConnection(reconnecting = false, attempt = 0)
    }

    fun retry() {
        userEnded = false
        beginConnection(reconnecting = true, attempt = 1)
    }

    fun sendUserMessage(text: String): Boolean {
        val normalized = text.trim()
        val currentState = _state.value
        if (normalized.isEmpty() || !currentState.canSend) return false
        val chatId = currentState.chatId ?: return false
        val now = System.currentTimeMillis()
        val request = RemoteUserMessage(
            requestId = UUID.randomUUID().toString(),
            chatId = chatId,
            text = normalized,
            timestampMs = now,
        )
        dispatch(
            AssistStateAction.MessageQueued(
                PendingRemoteMessage(request.requestId, normalized, now),
            ),
        )
        return try {
            sendApplication(request)
            true
        } catch (error: Throwable) {
            dispatch(
                AssistStateAction.MessageAcknowledged(
                    requestId = request.requestId,
                    accepted = false,
                    reason = error.message ?: "Message could not be sent",
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            scheduleReconnect(currentGeneration(), error.message ?: "Connection lost", false)
            false
        }
    }

    fun endSession(reason: String = "Helper ended the session") {
        if (_state.value.isAuthorized) {
            runCatching {
                sendApplication(EndSession(reason = reason, timestampMs = System.currentTimeMillis()))
            }
        }
        stopConnection()
        dispatch(AssistStateAction.SessionEnded(reason))
    }

    fun close() {
        stopConnection()
    }

    private fun beginConnection(reconnecting: Boolean, attempt: Int) {
        val invite = _state.value.invite ?: return
        val signer = try {
            signerProvider()
        } catch (error: Throwable) {
            dispatch(
                AssistStateAction.Failed(
                    error.message ?: "The helper identity could not be loaded",
                    terminal = true,
                ),
            )
            return
        }
        val nextHandshake = try {
            AssistHandshake.create(invite, signer)
        } catch (error: Throwable) {
            dispatch(AssistStateAction.Failed(error.message ?: "Handshake setup failed", terminal = true))
            return
        }

        val activeGeneration: Long
        val previousConnection: AssistWebSocketTransport.Connection?
        synchronized(stateLock) {
            generation += 1
            activeGeneration = generation
            previousConnection = connection
            connection = null
            handshake = nextHandshake
            cryptoSession = null
            identitySigner = signer
            joinRequestId = null
            reconnectJob?.cancel()
            reconnectJob = null
        }
        previousConnection?.cancel()
        dispatch(AssistStateAction.ConnectionStarted(reconnecting, attempt))

        val opened = try {
            transport.connect(
                RelayConnectionConfig(
                    relayUrl = invite.relayUrl,
                    roomId = invite.roomId,
                    role = Role.ASSIST,
                ),
                TransportListener(activeGeneration),
            )
        } catch (error: Throwable) {
            scheduleReconnect(activeGeneration, error.message ?: "Relay connection failed", false)
            return
        }
        synchronized(stateLock) {
            if (generation == activeGeneration && !userEnded) {
                connection = opened
            } else {
                opened.cancel()
            }
        }
    }

    private inner class TransportListener(
        private val callbackGeneration: Long,
    ) : AssistWebSocketTransport.Listener {
        override fun onOpen(connection: AssistWebSocketTransport.Connection) {
            if (!isCurrent(callbackGeneration)) return
            dispatch(AssistStateAction.SocketOpened)
        }

        override fun onEnvelope(
            connection: AssistWebSocketTransport.Connection,
            envelope: RelayEnvelope,
        ) {
            if (!isCurrent(callbackGeneration)) return
            try {
                val session = synchronized(stateLock) { cryptoSession }
                    ?: throw AssistProtocolException("Encrypted content arrived before SAS authorization")
                handleApplicationMessage(session.decryptMessage(envelope.frame), callbackGeneration)
            } catch (error: Throwable) {
                failProtocol(callbackGeneration, error)
            }
        }

        override fun onControlEvent(
            connection: AssistWebSocketTransport.Connection,
            event: RelayControlEvent,
        ) {
            if (!isCurrent(callbackGeneration)) return
            when (event) {
                is RelayPeerStatus -> when (event.status) {
                    RelayPeerState.WAITING -> Unit
                    RelayPeerState.CONNECTED -> dispatch(AssistStateAction.HostConnected)
                }

                is RelayPeerLeft -> scheduleReconnect(
                    callbackGeneration,
                    "The Rescue AI device disconnected",
                    peerLeft = true,
                )
            }
        }

        override fun onHandshakeMessage(
            connection: AssistWebSocketTransport.Connection,
            message: HandshakeMessage,
        ) {
            if (!isCurrent(callbackGeneration)) return
            try {
                handleHandshakeMessage(connection, message)
            } catch (error: Throwable) {
                failProtocol(callbackGeneration, error)
            }
        }

        override fun onClosing(
            connection: AssistWebSocketTransport.Connection,
            code: Int,
            reason: String,
        ) = Unit

        override fun onClosed(
            connection: AssistWebSocketTransport.Connection,
            code: Int,
            reason: String,
        ) {
            scheduleReconnect(callbackGeneration, reason.ifBlank { "Connection closed" }, false)
        }

        override fun onFailure(
            connection: AssistWebSocketTransport.Connection,
            error: Throwable,
        ) {
            if (error is AssistProtocolException) {
                failProtocol(callbackGeneration, error)
            } else {
                scheduleReconnect(
                    callbackGeneration,
                    error.message ?: "Connection failed",
                    peerLeft = false,
                )
            }
        }
    }

    private fun handleHandshakeMessage(
        callbackConnection: AssistWebSocketTransport.Connection,
        message: HandshakeMessage,
    ) {
        val currentHandshake = synchronized(stateLock) { handshake }
            ?: throw AssistProtocolException("Handshake is not active")
        when (message) {
            is HostHello -> {
                val response: AssistHello = currentHandshake.receiveHostHello(message)
                if (!callbackConnection.sendHandshake(response)) {
                    throw AssistProtocolException("Unable to send helper handshake")
                }
            }

            is VerificationRequired -> {
                val challenge = currentHandshake.receiveVerificationRequired(message)
                dispatch(
                    AssistStateAction.VerificationPending(
                        sas = challenge.sasCode,
                        hostFingerprint = challenge.hostIdentity.fingerprint,
                    ),
                )
            }

            is VerificationResult -> {
                val authorized = currentHandshake.receiveVerificationResult(message)
                synchronized(stateLock) { cryptoSession = authorized }
                dispatch(AssistStateAction.CryptographyAuthorized)
                sendJoinRequest()
            }

            else -> throw AssistProtocolException("Unexpected helper-side handshake message")
        }
    }

    private fun sendJoinRequest() {
        val signer = synchronized(stateLock) { identitySigner }
            ?: throw AssistProtocolException("Helper identity is unavailable")
        val currentState = _state.value
        val request = JoinRequest(
            requestId = UUID.randomUUID().toString(),
            peerId = signer.identity.fingerprint,
            displayName = "WuxianPi Helper",
            requestedPermission = currentState.requestedPermission,
            timestampMs = System.currentTimeMillis(),
        )
        synchronized(stateLock) { joinRequestId = request.requestId }
        sendApplication(request)
    }

    private fun handleApplicationMessage(message: AssistMessage, sourceGeneration: Long) {
        when (message) {
            is JoinDecision -> {
                val expectedRequestId = synchronized(stateLock) { joinRequestId }
                    ?: throw AssistProtocolException("No join request is awaiting a decision")
                if (message.requestId != expectedRequestId) {
                    throw AssistProtocolException("Join decision does not match this request")
                }
                synchronized(stateLock) { joinRequestId = null }
                if (message.accepted) {
                    val grantedPermission = message.grantedPermission
                        ?: throw AssistProtocolException("Host omitted the granted permission")
                    if (
                        _state.value.requestedPermission == Permission.VIEW &&
                        grantedPermission != Permission.VIEW
                    ) {
                        throw AssistProtocolException("Host granted more access than requested")
                    }
                    dispatch(
                        AssistStateAction.JoinAccepted(
                            permission = grantedPermission,
                            hostName = message.hostDisplayName,
                        ),
                    )
                } else {
                    dispatch(AssistStateAction.JoinRejected(message.reason ?: "Access rejected"))
                    stopConnection()
                }
            }

            is ConversationSnapshot -> dispatch(
                AssistStateAction.SnapshotReceived(message.chatId, message.messages),
            )

            is MessageUpsert -> dispatch(
                AssistStateAction.MessageReceived(message.chatId, message.message),
            )

            is TurnState -> dispatch(
                AssistStateAction.TurnChanged(
                    chatId = message.chatId,
                    status = message.state,
                    detail = message.detail,
                    timestampMs = message.timestampMs,
                ),
            )

            is ToolStarted -> dispatch(
                AssistStateAction.ActivityReceived(
                    chatId = message.chatId,
                    item = AssistActivityItem(
                        id = "tool-start-${message.toolCallId}",
                        kind = AssistActivityKind.TOOL,
                        title = "Tool started: ${message.toolName}",
                        detail = message.argumentsJson,
                        timestampMs = message.timestampMs,
                    ),
                ),
            )

            is ToolFinished -> dispatch(
                AssistStateAction.ActivityReceived(
                    chatId = message.chatId,
                    item = AssistActivityItem(
                        id = "tool-finish-${message.toolCallId}",
                        kind = if (message.isError) AssistActivityKind.ERROR else AssistActivityKind.TOOL,
                        title = if (message.isError) {
                            "Tool failed: ${message.toolName}"
                        } else {
                            "Tool completed: ${message.toolName}"
                        },
                        detail = message.content,
                        timestampMs = message.timestampMs,
                        isError = message.isError,
                    ),
                ),
            )

            is RemoteUserMessageAck -> dispatch(
                AssistStateAction.MessageAcknowledged(
                    requestId = message.requestId,
                    accepted = message.accepted,
                    reason = message.reason,
                    timestampMs = message.timestampMs,
                ),
            )

            is Ping -> sendApplication(Pong(message.nonce, System.currentTimeMillis()))
            is Pong -> Unit
            is PeerLeft -> scheduleReconnect(
                sourceGeneration,
                message.reason ?: "The Rescue AI device left",
                peerLeft = true,
            )

            is EndSession -> finishRemoteSession(message.reason)
            is JoinRequest,
            is RemoteUserMessage,
            -> throw AssistProtocolException("Host sent a helper-only application message")
        }
    }

    private fun sendApplication(message: AssistMessage) {
        val activeConnection: AssistWebSocketTransport.Connection
        val envelope: RelayEnvelope
        synchronized(stateLock) {
            val invite = _state.value.invite
                ?: throw AssistProtocolException("Invitation is unavailable")
            val session = cryptoSession
                ?: throw AssistProtocolException("Session is not authorized")
            activeConnection = connection
                ?: throw AssistProtocolException("Relay is not connected")
            envelope = RelayEnvelope(
                roomId = invite.roomId,
                role = Role.ASSIST,
                frame = session.encryptMessage(message),
            )
        }
        if (!activeConnection.send(envelope)) {
            throw AssistProtocolException("Relay did not accept the encrypted message")
        }
    }

    private fun scheduleReconnect(sourceGeneration: Long, reason: String, peerLeft: Boolean) {
        val reconnectToken: Long
        val attempt: Int
        val staleConnection: AssistWebSocketTransport.Connection?
        synchronized(stateLock) {
            if (sourceGeneration != generation || userEnded) return
            generation += 1
            reconnectToken = generation
            staleConnection = connection
            connection = null
            handshake = null
            cryptoSession = null
            joinRequestId = null
            attempt = _state.value.reconnectAttempt + 1
            reconnectJob?.cancel()
            reconnectJob = null
        }
        staleConnection?.cancel()

        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            dispatch(AssistStateAction.Failed("$reason. Tap reconnect to try again", terminal = true))
            return
        }
        if (peerLeft) {
            dispatch(AssistStateAction.PeerLeft(reconnecting = true))
        }
        dispatch(AssistStateAction.Reconnecting(reason, attempt))
        reconnectJob = scope.launch {
            delay(reconnectDelayMs(attempt))
            if (isCurrent(reconnectToken) && !userEnded) {
                beginConnection(reconnecting = true, attempt = attempt)
            }
        }
    }

    private fun failProtocol(sourceGeneration: Long, error: Throwable) {
        val activeConnection: AssistWebSocketTransport.Connection?
        synchronized(stateLock) {
            if (sourceGeneration != generation) return
            generation += 1
            userEnded = true
            activeConnection = connection
            connection = null
            handshake = null
            cryptoSession = null
            joinRequestId = null
            reconnectJob?.cancel()
            reconnectJob = null
        }
        activeConnection?.close(1008, "protocol failure")
        dispatch(
            AssistStateAction.Failed(
                error.message ?: "The secure session was rejected",
                terminal = true,
            ),
        )
    }

    private fun finishRemoteSession(reason: String?) {
        stopConnection()
        dispatch(AssistStateAction.SessionEnded(reason ?: "The Rescue AI device ended the session"))
    }

    private fun stopConnection() {
        val activeConnection: AssistWebSocketTransport.Connection?
        synchronized(stateLock) {
            userEnded = true
            generation += 1
            activeConnection = connection
            connection = null
            handshake = null
            cryptoSession = null
            identitySigner = null
            joinRequestId = null
            reconnectJob?.cancel()
            reconnectJob = null
        }
        activeConnection?.close(1000, "helper closed")
    }

    private fun dispatch(action: AssistStateAction) {
        _state.update { AssistStateReducer.reduce(it, action) }
    }

    private fun isCurrent(value: Long): Boolean = synchronized(stateLock) {
        value == generation && !userEnded
    }

    private fun currentGeneration(): Long = synchronized(stateLock) { generation }

    private fun reconnectDelayMs(attempt: Int): Long = min(15_000L, 1_000L shl (attempt - 1))

    private companion object {
        val EDITABLE_PHASES = setOf(
            AssistConnectionPhase.IDLE,
            AssistConnectionPhase.ENDED,
            AssistConnectionPhase.ERROR,
        )
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
