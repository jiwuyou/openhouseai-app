package com.ai.assistance.operit.rescue.remote

import android.content.Context
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.pi.RescuePiChatEngine
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatHistoryMessageEvent
import com.wuxianpi.assist.protocol.AssistHello
import com.wuxianpi.assist.protocol.AssistMessage
import com.wuxianpi.assist.protocol.AssistProtocolException
import com.wuxianpi.assist.protocol.AssistWebSocketTransport
import com.wuxianpi.assist.protocol.AuthorizedAssistCryptoSession
import com.wuxianpi.assist.protocol.EndSession
import com.wuxianpi.assist.protocol.HostHandshake
import com.wuxianpi.assist.protocol.HostVerificationChallenge
import com.wuxianpi.assist.protocol.Invite
import com.wuxianpi.assist.protocol.OkHttpAssistWebSocketTransport
import com.wuxianpi.assist.protocol.Permission
import com.wuxianpi.assist.protocol.RelayConnectionConfig
import com.wuxianpi.assist.protocol.RelayControlEvent
import com.wuxianpi.assist.protocol.RelayEnvelope
import com.wuxianpi.assist.protocol.RelayPeerLeft
import com.wuxianpi.assist.protocol.RelayPeerState
import com.wuxianpi.assist.protocol.RelayPeerStatus
import com.wuxianpi.assist.protocol.Role
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class RescueRemoteAssistController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val transport: AssistWebSocketTransport =
        OkHttpAssistWebSocketTransport(OkHttpClient.Builder().build())
    private val rescueCore: ChatServiceCore =
        ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.RESCUE)
    private val historyDelegate = rescueCore.getChatHistoryDelegate()
    private val rescueEngine = RescuePiChatEngine.getInstance(appContext)

    private val mutableState = MutableStateFlow(RescueAssistHostState())
    val state: StateFlow<RescueAssistHostState> = mutableState.asStateFlow()

    private var generation = 0L
    private var invite: Invite? = null
    private var hostHandshake: HostHandshake? = null
    private var verificationChallenge: HostVerificationChallenge? = null
    private var cryptoSession: AuthorizedAssistCryptoSession? = null
    private var connection: AssistWebSocketTransport.Connection? = null
    private var hostSession: RescueAssistHostSession? = null
    private var inboundMessages: Channel<AssistMessage>? = null
    private var inboundJob: Job? = null

    init {
        scope.launch {
            historyDelegate.messageEvents.collect(::forwardHistoryEvent)
        }
        scope.launch {
            rescueEngine.remoteEvents.collect(::forwardPiEvent)
        }
    }

    fun startSharing(pinnedChatId: String, relayUrl: String, permission: Permission) {
        val intent = RescueRemoteAssistServiceIntents.start(appContext, pinnedChatId, relayUrl, permission)
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { publishLaunchError(it) }
    }

    fun verifySas(code: String) {
        val intent = RescueRemoteAssistServiceIntents.verifySas(appContext, code)
        runCatching { appContext.startService(intent) }
            .onFailure { publishLaunchError(it) }
    }

    fun stopSharing() {
        val intent = RescueRemoteAssistServiceIntents.stop(appContext)
        runCatching { appContext.startService(intent) }
            .onFailure {
                stopSharingNow()
                publishLaunchError(it)
            }
    }

    internal fun startSharingNow(pinnedChatId: String, relayUrl: String, permission: Permission) {
        scope.launch {
            if (pinnedChatId.isBlank()) {
                publishError("A Rescue conversation must be selected before sharing")
                return@launch
            }
            stopSharingNow(updateState = false)
            val token = synchronized(lock) { ++generation }
            try {
                val identity = AndroidKeystoreIdentitySigner.getOrCreate()
                val newInvite = Invite.create(relayUrl = relayUrl, hostIdentity = identity.identity)
                val handshake = HostHandshake.create(newInvite, identity)
                synchronized(lock) {
                    invite = newInvite
                    hostHandshake = handshake
                }
                mutableState.value =
                    RescueAssistHostState(
                        phase = RescueAssistHostPhase.CONNECTING,
                        inviteUri = newInvite.toUriString(),
                        pinnedChatId = pinnedChatId,
                        offeredPermission = permission,
                    )
                transport.connect(
                    RelayConnectionConfig(newInvite.relayUrl, newInvite.roomId, Role.HOST),
                    createTransportListener(token),
                )
            } catch (error: Exception) {
                fail(token, error)
            }
        }
    }

    internal fun verifySasNow(code: String) {
        scope.launch {
            val token: Long
            val challenge: HostVerificationChallenge
            val activeConnection: AssistWebSocketTransport.Connection
            val pinnedChatId: String
            val offeredPermission: Permission
            synchronized(lock) {
                token = generation
                challenge = verificationChallenge
                    ?: run {
                        publishError("No helper is waiting for verification", keepSession = true)
                        return@launch
                    }
                activeConnection = connection
                    ?: run {
                        publishError("Relay connection is unavailable", keepSession = true)
                        return@launch
                    }
                pinnedChatId = mutableState.value.pinnedChatId
                    ?: run {
                        publishError("The shared Rescue conversation is unavailable", keepSession = true)
                        return@launch
                    }
                offeredPermission = mutableState.value.offeredPermission ?: Permission.VIEW
            }

            try {
                val authorization = challenge.verifySas(code.trim())
                val session =
                    RescueAssistHostSession(
                        pinnedChatId = pinnedChatId,
                        offeredPermission = offeredPermission,
                        historyProvider = historyDelegate::getChatHistory,
                        remoteInputRouter = ::routeRemoteInput,
                        send = { message -> sendApplication(token, message) },
                        onPeerJoined = { displayName, permission ->
                            updatePeerJoined(token, displayName, permission)
                        },
                    ).also(RescueAssistHostSession::authorize)
                val channel = Channel<AssistMessage>(capacity = 64)
                val job = scope.launch {
                    try {
                        for (message in channel) session.handleIncoming(message)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        fail(token, error)
                    }
                }
                synchronized(lock) {
                    if (generation != token) {
                        channel.close()
                        job.cancel()
                        return@launch
                    }
                    cryptoSession = authorization.session
                    hostSession = session
                    inboundMessages = channel
                    inboundJob = job
                }
                check(activeConnection.sendHandshake(authorization.verificationResult)) {
                    "Unable to send SAS verification result"
                }
                mutableState.value =
                    mutableState.value.copy(
                        phase = RescueAssistHostPhase.AUTHORIZED,
                        error = null,
                    )
            } catch (error: AssistProtocolException) {
                mutableState.value =
                    mutableState.value.copy(
                        phase = RescueAssistHostPhase.WAITING_FOR_SAS,
                        error = error.message ?: "Verification code did not match",
                    )
            } catch (error: Exception) {
                fail(token, error)
            }
        }
    }

    internal fun stopSharingNow(updateState: Boolean = true) {
        val oldSession: RescueAssistHostSession?
        val oldConnection: AssistWebSocketTransport.Connection?
        val oldInbound: Channel<AssistMessage>?
        val oldInboundJob: Job?
        synchronized(lock) {
            oldSession = hostSession
            oldConnection = connection
            oldInbound = inboundMessages
            oldInboundJob = inboundJob
            oldSession?.prepareHostStop(reason = "Host stopped sharing")?.let { endSession ->
                queueFinalEndSessionLocked(endSession)
            }
            generation += 1
            invite = null
            hostHandshake = null
            verificationChallenge = null
            cryptoSession = null
            connection = null
            hostSession = null
            inboundMessages = null
            inboundJob = null
        }
        oldInbound?.close()
        oldInboundJob?.cancel()
        runCatching { oldConnection?.close(reason = "Host stopped sharing") }
        if (updateState) mutableState.value = RescueAssistHostState()
    }

    /** The caller holds [lock], so no newer sharing generation can replace these references. */
    private fun queueFinalEndSessionLocked(message: EndSession) {
        val activeInvite = invite ?: return
        val activeCrypto = cryptoSession ?: return
        val activeConnection = connection ?: return
        runCatching {
            activeConnection.send(
                RelayEnvelope(
                    roomId = activeInvite.roomId,
                    role = Role.HOST,
                    frame = activeCrypto.encryptMessage(message),
                ),
            )
        }
    }

    private fun createTransportListener(token: Long): AssistWebSocketTransport.Listener =
        object : AssistWebSocketTransport.Listener {
            override fun onOpen(connection: AssistWebSocketTransport.Connection) {
                if (!isCurrent(token)) {
                    connection.close(reason = "stale sharing session")
                    return
                }
                synchronized(lock) { this@RescueRemoteAssistController.connection = connection }
                mutableState.value =
                    mutableState.value.copy(
                        phase = RescueAssistHostPhase.WAITING_FOR_PEER,
                        error = null,
                    )
            }

            override fun onEnvelope(
                connection: AssistWebSocketTransport.Connection,
                envelope: RelayEnvelope,
            ) {
                if (!isCurrent(token)) return
                try {
                    val crypto = synchronized(lock) { cryptoSession }
                        ?: throw AssistProtocolException("Encrypted data arrived before SAS authorization")
                    val message = crypto.decryptMessage(envelope.frame)
                    val accepted = synchronized(lock) { inboundMessages }?.trySend(message)
                    if (accepted == null || accepted.isFailure) {
                        throw IllegalStateException("Remote assistance input buffer is unavailable")
                    }
                } catch (error: Exception) {
                    fail(token, error)
                }
            }

            override fun onControlEvent(
                connection: AssistWebSocketTransport.Connection,
                event: RelayControlEvent,
            ) {
                if (!isCurrent(token)) return
                when (event) {
                    is RelayPeerStatus -> when (event.status) {
                        RelayPeerState.WAITING ->
                            mutableState.value =
                                mutableState.value.copy(
                                    phase = RescueAssistHostPhase.WAITING_FOR_PEER,
                                    error = null,
                                )
                        RelayPeerState.CONNECTED -> {
                            if (event.role != Role.ASSIST) {
                                fail(token, AssistProtocolException("Relay connected an unexpected peer role"))
                                return
                            }
                            sendHostHello(token, connection)
                        }
                    }
                    is RelayPeerLeft -> {
                        if (event.role == Role.ASSIST) resetForNextPeer(token)
                    }
                }
            }

            override fun onHandshakeMessage(
                connection: AssistWebSocketTransport.Connection,
                message: com.wuxianpi.assist.protocol.HandshakeMessage,
            ) {
                if (!isCurrent(token)) return
                if (message !is AssistHello) {
                    fail(token, AssistProtocolException("Host received an unexpected handshake message"))
                    return
                }
                try {
                    val handshake = synchronized(lock) { hostHandshake }
                        ?: throw IllegalStateException("Host handshake is unavailable")
                    val challenge = handshake.receiveAssistHello(message)
                    synchronized(lock) { verificationChallenge = challenge }
                    check(connection.sendHandshake(challenge.verificationRequired)) {
                        "Unable to request SAS verification"
                    }
                    mutableState.value =
                        mutableState.value.copy(
                            phase = RescueAssistHostPhase.WAITING_FOR_SAS,
                            peerFingerprint = challenge.peerIdentityFingerprint,
                            peerDisplayName = null,
                            grantedPermission = null,
                            error = null,
                        )
                } catch (error: Exception) {
                    fail(token, error)
                }
            }

            override fun onClosed(
                connection: AssistWebSocketTransport.Connection,
                code: Int,
                reason: String,
            ) {
                if (isCurrent(token)) fail(token, IllegalStateException("Relay closed: $code $reason"))
            }

            override fun onFailure(
                connection: AssistWebSocketTransport.Connection,
                error: Throwable,
            ) {
                if (isCurrent(token)) fail(token, error)
            }
        }

    private fun sendHostHello(token: Long, activeConnection: AssistWebSocketTransport.Connection) {
        try {
            val hello = synchronized(lock) { hostHandshake?.hostHello }
                ?: throw IllegalStateException("Host handshake is unavailable")
            check(activeConnection.sendHandshake(hello)) { "Unable to send host handshake" }
            mutableState.value =
                mutableState.value.copy(
                    phase = RescueAssistHostPhase.WAITING_FOR_PEER,
                    error = null,
                )
        } catch (error: Exception) {
            fail(token, error)
        }
    }

    private fun resetForNextPeer(token: Long) {
        val newHandshake = try {
            val activeInvite = synchronized(lock) { invite } ?: return
            HostHandshake.create(activeInvite, AndroidKeystoreIdentitySigner.getOrCreate())
        } catch (error: Exception) {
            fail(token, error)
            return
        }
        val oldSession: RescueAssistHostSession?
        val oldInbound: Channel<AssistMessage>?
        val oldJob: Job?
        synchronized(lock) {
            if (generation != token) return
            oldSession = hostSession
            oldInbound = inboundMessages
            oldJob = inboundJob
            hostHandshake = newHandshake
            verificationChallenge = null
            cryptoSession = null
            hostSession = null
            inboundMessages = null
            inboundJob = null
        }
        oldSession?.close(sendEndMessage = false)
        oldInbound?.close()
        oldJob?.cancel()
        mutableState.value =
            mutableState.value.copy(
                phase = RescueAssistHostPhase.WAITING_FOR_PEER,
                peerFingerprint = null,
                peerDisplayName = null,
                grantedPermission = null,
                error = null,
            )
    }

    private fun sendApplication(token: Long, message: AssistMessage) {
        if (!isCurrent(token)) return
        try {
            val activeInvite: Invite
            val activeCrypto: AuthorizedAssistCryptoSession
            val activeConnection: AssistWebSocketTransport.Connection
            synchronized(lock) {
                activeInvite = invite ?: throw IllegalStateException("Sharing invite is unavailable")
                activeCrypto = cryptoSession ?: throw IllegalStateException("Sharing is not authorized")
                activeConnection = connection ?: throw IllegalStateException("Relay connection is unavailable")
            }
            val envelope =
                RelayEnvelope(
                    roomId = activeInvite.roomId,
                    role = Role.HOST,
                    frame = activeCrypto.encryptMessage(message),
                )
            check(activeConnection.send(envelope)) { "Unable to send encrypted assistance message" }
        } catch (error: Exception) {
            fail(token, error)
        }
    }

    private fun routeRemoteInput(chatId: String, text: String, requestId: String): String {
        rescueCore.sendUserMessage(chatIdOverride = chatId, messageTextOverride = text)
        return "remote-$requestId"
    }

    private fun forwardHistoryEvent(event: ChatHistoryMessageEvent) {
        val session = synchronized(lock) { hostSession } ?: return
        session.publishHistoryMessage(event.chatId, event.message)
    }

    private fun forwardPiEvent(event: RescuePiRemoteEvent) {
        val session = synchronized(lock) { hostSession } ?: return
        session.publishPiEvent(event)
    }

    private fun updatePeerJoined(token: Long, displayName: String, permission: Permission) {
        if (!isCurrent(token)) return
        mutableState.value =
            mutableState.value.copy(
                phase = RescueAssistHostPhase.AUTHORIZED,
                peerDisplayName = displayName,
                grantedPermission = permission,
                error = null,
            )
    }

    private fun fail(token: Long, error: Throwable) {
        if (!isCurrent(token)) return
        val previous = mutableState.value
        stopSharingNow(updateState = false)
        mutableState.value =
            previous.copy(
                phase = RescueAssistHostPhase.ERROR,
                error = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
            )
    }

    private fun publishError(message: String, keepSession: Boolean = false) {
        val previous = mutableState.value
        if (!keepSession) stopSharingNow(updateState = false)
        mutableState.value = previous.copy(phase = RescueAssistHostPhase.ERROR, error = message)
    }

    private fun publishLaunchError(error: Throwable) {
        publishError(
            error.message?.takeIf(String::isNotBlank) ?: "Unable to start remote assistance service",
        )
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    companion object {
        @Volatile private var instance: RescueRemoteAssistController? = null

        fun getInstance(context: Context): RescueRemoteAssistController =
            instance ?: synchronized(this) {
                instance ?: RescueRemoteAssistController(context.applicationContext).also { instance = it }
            }
    }
}
