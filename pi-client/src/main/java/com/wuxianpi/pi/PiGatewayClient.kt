package com.wuxianpi.pi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PiGatewayConfig(
    val adminBaseUrl: HttpUrl,
    val bearerToken: String,
    val clientId: String,
) {
    init {
        requireTrustedGateway(adminBaseUrl)
        require(bearerToken.isNotBlank()) { "Gateway token is required" }
        require(clientId.isNotBlank()) { "Client id is required" }
    }
}

data class PiLease(
    val sessionId: String,
    val leaseId: String,
    val wsPath: String,
)

sealed interface LeaseResult {
    data class Acquired(val lease: PiLease) : LeaseResult
    data class Conflict(val sessionId: String, val ownerClientId: String?, val message: String) : LeaseResult
    data class Failure(val message: String, val retryable: Boolean) : LeaseResult
}

sealed interface PiConnectionState {
    data object Disconnected : PiConnectionState
    data class AcquiringLease(val sessionId: String) : PiConnectionState
    data class Connecting(val sessionId: String) : PiConnectionState
    data class Recovering(val sessionId: String, val leaseId: String) : PiConnectionState
    data class Connected(val sessionId: String, val leaseId: String) : PiConnectionState
    data class Reconnecting(val sessionId: String, val attempt: Int) : PiConnectionState
    data class LeaseConflict(val sessionId: String, val ownerClientId: String?, val message: String) : PiConnectionState
    data class Failed(val message: String, val retryable: Boolean) : PiConnectionState
}

/** Management-plane HTTP client. WebSocket frames remain 100% Pi-native JSON. */
class PiGatewayAdminClient(
    private val config: PiGatewayConfig,
    private val http: OkHttpClient,
) {
    suspend fun acquireLease(sessionId: String, takeover: Boolean): LeaseResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("clientId", config.clientId)
            .put("takeover", takeover)
            .toString()
            .toRequestBody(JSON)
        val url = config.adminBaseUrl.newBuilder().addPathSegments("admin/v1/leases").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.bearerToken}")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { response -> parseLease(response, sessionId) }
        } catch (error: IOException) {
            LeaseResult.Failure(error.message ?: "Gateway unavailable", retryable = true)
        }
    }

    private fun parseLease(response: Response, sessionId: String): LeaseResult {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        val apiError = json.optJSONObject("error")
        if (response.code == 409) {
            return LeaseResult.Conflict(
                sessionId = sessionId,
                ownerClientId = json.optString("ownerClientId").takeIf(String::isNotEmpty),
                message = apiError?.optString("message")?.takeIf(String::isNotBlank)
                    ?: "This session is controlled by another client",
            )
        }
        if (!response.isSuccessful) {
            return LeaseResult.Failure(
                message = apiError?.optString("message")?.takeIf(String::isNotBlank)
                    ?: "Lease request failed (${response.code})",
                retryable = response.code >= 500,
            )
        }
        val leaseId = json.optString("leaseId")
        val wsPath = json.optString("wsPath")
        if (leaseId.isBlank() || wsPath.isBlank()) {
            return LeaseResult.Failure("Gateway returned an invalid lease", retryable = false)
        }
        // Validate before the token can ever be attached to the WebSocket request.
        runCatching { resolveTrustedWebSocketUrl(config.adminBaseUrl, wsPath) }.getOrElse {
            return LeaseResult.Failure(it.message ?: "Gateway returned an unsafe WebSocket URL", false)
        }
        return LeaseResult.Acquired(PiLease(sessionId, leaseId, wsPath))
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private data class InboundFrame(val socketGeneration: Long, val text: String)

internal object LeaseRecoveryPolicy {
    const val MAX_SOCKET_RECONNECT_ATTEMPTS = 4
    const val MAX_LEASE_ACQUIRE_ATTEMPTS = 3
    const val MAX_LEASE_REACQUIRE_CYCLES = 2

    fun leaseIsInvalid(responseCode: Int? = null, closeCode: Int? = null): Boolean =
        responseCode == 404 || responseCode == 409 || responseCode == 410 || closeCode == 4001

    fun isTerminalHandshakeFailure(responseCode: Int?): Boolean =
        responseCode != null && responseCode in 400..499 && !leaseIsInvalid(responseCode = responseCode)

    fun reconnectDelayMillis(attempt: Int): Long =
        (500L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(15_000L)

    fun leaseAcquireDelayMillis(attempt: Int): Long =
        (500L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(2_000L)
}

class PiRpcClient(
    private val config: PiGatewayConfig,
    private val http: OkHttpClient = OkHttpClient(),
    parentScope: CoroutineScope? = null,
) : AutoCloseable {
    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownsScope = parentScope == null
    private val admin = PiGatewayAdminClient(config, http)
    private val requestCounter = AtomicLong(0)
    private val socketGenerations = SocketGenerationGate()
    private val sessionEpoch = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PiResponse>>()
    private val promptGate = PromptGate()
    private val _events = MutableSharedFlow<PiEvent>()
    private val _responses = MutableSharedFlow<PiResponse>()
    private val _connection = MutableStateFlow<PiConnectionState>(PiConnectionState.Disconnected)
    private val _agentActive = MutableStateFlow(false)
    private val _recoveredMessages = MutableStateFlow<PiResponse?>(null)
    private val inbound = OrderedQueue<InboundFrame>(scope, consume = ::handleFrame)

    val events: SharedFlow<PiEvent> = _events.asSharedFlow()
    val responses: SharedFlow<PiResponse> = _responses.asSharedFlow()
    val connection: StateFlow<PiConnectionState> = _connection.asStateFlow()
    val recoveredMessages: StateFlow<PiResponse?> = _recoveredMessages.asStateFlow()

    /** Live errors do not clear this. agent_end does; get_state restores it after attach/reconnect. */
    val agentActive: StateFlow<Boolean> = _agentActive.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var activeLease: PiLease? = null
    @Volatile private var explicitClose = false
    @Volatile private var recovery: RecoveryTracker? = null
    @Volatile private var recoveryLifecycleOverride: Boolean? = null
    @Volatile private var recoveryMessagesResponse: PiResponse? = null
    private val recoveryEventBuffer = ArrayList<PiEvent>()
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var leaseReacquireCycle = 0

    suspend fun openSession(sessionId: String = "new", takeover: Boolean = false): LeaseResult {
        val epoch = sessionEpoch.incrementAndGet()
        explicitClose = false
        invalidateSocket("switching session")
        _agentActive.value = false
        promptGate.onRecovered(false)
        _connection.value = PiConnectionState.AcquiringLease(sessionId)
        val result = admin.acquireLease(sessionId, takeover)
        if (epoch != sessionEpoch.get() || explicitClose) {
            return LeaseResult.Failure("Session request was superseded", retryable = true)
        }
        return when (result) {
            is LeaseResult.Acquired -> {
                activeLease = result.lease
                openSocket(result.lease, reconnecting = false, epoch = epoch)
                result
            }
            is LeaseResult.Conflict -> {
                _connection.value = PiConnectionState.LeaseConflict(
                    result.sessionId,
                    result.ownerClientId,
                    result.message,
                )
                result
            }
            is LeaseResult.Failure -> {
                _connection.value = PiConnectionState.Failed(result.message, result.retryable)
                result
            }
        }
    }

    suspend fun takeOver(sessionId: String): LeaseResult = openSession(sessionId, takeover = true)

    suspend fun command(
        type: String,
        parameters: JSONObject = JSONObject(),
        timeoutMillis: Long = 30_000,
    ): PiResponse = executeCommand(type, parameters, timeoutMillis, onQueued = null)

    private suspend fun executeCommand(
        type: String,
        parameters: JSONObject,
        timeoutMillis: Long,
        onQueued: (() -> Unit)?,
    ): PiResponse {
        val isPrompt = type == "prompt"
        if (isPrompt && !promptGate.tryBegin(
                connected = _connection.value is PiConnectionState.Connected,
                isStreaming = _agentActive.value,
            )
        ) {
            throw IllegalStateException("A Pi turn is active or session recovery is incomplete")
        }

        val id = "android-${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
        val deferred = CompletableDeferred<PiResponse>()
        pending[id] = deferred
        val current = socket
        if (current == null || !current.send(PiProtocol.request(id, type, parameters))) {
            pending.remove(id)
            if (isPrompt) promptGate.onPromptRejected()
            throw IOException("Pi RPC is not connected")
        }
        onQueued?.invoke()
        return try {
            val response = withTimeout(timeoutMillis) { deferred.await() }
            if (isPrompt && !response.success) promptGate.onPromptRejected()
            response
        } finally {
            // If a sent prompt times out, keep the gate occupied until agent_end or get_state.
            pending.remove(id)
        }
    }

    suspend fun prompt(message: String, onQueued: () -> Unit = {}): PiResponse = executeCommand(
        type = "prompt",
        parameters = JSONObject().put("message", message),
        timeoutMillis = 30_000,
        onQueued = onQueued,
    )

    suspend fun abort(): PiResponse = command("abort")

    suspend fun extensionUiResponse(requestId: String, response: Any?): PiResponse {
        val params = JSONObject().put("requestId", requestId)
        when (response) {
            null -> params.put("cancelled", true)
            is Boolean -> params.put("confirmed", response)
            else -> params.put("value", response)
        }
        return command("extension_ui_response", params)
    }

    private fun openSocket(lease: PiLease, reconnecting: Boolean, epoch: Long) {
        if (epoch != sessionEpoch.get() || explicitClose) return
        _connection.value = if (reconnecting) {
            PiConnectionState.Reconnecting(lease.sessionId, reconnectAttempt)
        } else {
            PiConnectionState.Connecting(lease.sessionId)
        }
        val url = resolveTrustedWebSocketUrl(config.adminBaseUrl, lease.wsPath)
        val generation = socketGenerations.next()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.bearerToken}")
            .build()
        socket = http.newWebSocket(request, SocketListener(lease, generation, epoch))
    }

    private inner class SocketListener(
        private val lease: PiLease,
        private val generation: Long,
        private val epoch: Long,
    ) : WebSocketListener() {
        private fun current(): Boolean =
            socketGenerations.isCurrent(generation) && epoch == sessionEpoch.get() && !explicitClose

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current()) {
                webSocket.close(1000, "superseded")
                return
            }
            socket = webSocket
            reconnectJob = null
            recoveryLifecycleOverride = null
            recoveryMessagesResponse = null
            synchronized(recoveryEventBuffer) { recoveryEventBuffer.clear() }
            val stateId = "recovery-state-${requestCounter.incrementAndGet()}"
            val messagesId = "recovery-messages-${requestCounter.incrementAndGet()}"
            recovery = RecoveryTracker(generation, stateId, messagesId)
            _connection.value = PiConnectionState.Recovering(lease.sessionId, lease.leaseId)
            if (!webSocket.send(PiProtocol.request(stateId, "get_state")) ||
                !webSocket.send(PiProtocol.request(messagesId, "get_messages"))
            ) {
                webSocket.close(1011, "recovery request failed")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (current() &&
                inbound.offer(InboundFrame(generation, text)) == QueueOfferResult.OVERFLOW_REQUIRES_RECOVERY
            ) {
                if (socket === webSocket) socket = null
                failPending(IOException("Pi RPC inbound queue overflow"))
                webSocket.close(1013, "inbound queue overflow; reconnecting for recovery")
                scheduleReconnect(lease, generation, epoch)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return
            if (socket === webSocket) socket = null
            scheduleReconnect(lease, generation, epoch, closeCode = code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            if (socket === webSocket) socket = null
            failPending(IOException("Pi RPC connection lost", t))
            val responseCode = response?.code
            if (LeaseRecoveryPolicy.isTerminalHandshakeFailure(responseCode)) {
                failConnection(
                    generation,
                    epoch,
                    "Pi RPC connection was rejected by the gateway ($responseCode)",
                    retryable = false,
                )
            } else {
                scheduleReconnect(lease, generation, epoch, responseCode = responseCode)
            }
        }
    }

    private suspend fun handleFrame(frame: InboundFrame) {
        // A slow collector can leave old frames queued; never apply them to a newer socket/session.
        if (!socketGenerations.isCurrent(frame.socketGeneration)) return
        when (val parsed = PiProtocol.parse(frame.text)) {
            is ParsedPiFrame.Response -> handleResponse(frame.socketGeneration, parsed.value)
            is ParsedPiFrame.Event -> handleEvent(parsed.value)
        }
    }

    private suspend fun handleResponse(generation: Long, response: PiResponse) {
        pending.remove(response.id)?.complete(response)
        val tracker = recovery?.takeIf { it.generation == generation }
        val progress = tracker?.accept(response)

        if (progress?.isStreaming != null && recoveryLifecycleOverride == null) {
            _agentActive.value = progress.isStreaming
            promptGate.onRecovered(progress.isStreaming)
        }
        if (response.success && response.command == "get_messages") {
            // StateFlow retains the current transcript even if the UI collector attaches late.
            _recoveredMessages.value = response
            recoveryMessagesResponse = response
        }
        _responses.emit(response)
        if (!response.success) _events.emit(PiEvent.CommandError(response, response.rawJson))

        if (progress?.complete == true) {
            recovery = null
            if (progress.failed) {
                synchronized(recoveryEventBuffer) { recoveryEventBuffer.clear() }
                _connection.value = PiConnectionState.Failed("Pi session recovery failed", retryable = true)
                socket?.close(1011, "recovery failed")
            } else {
                val lease = activeLease
                if (lease != null && socketGenerations.isCurrent(generation)) {
                    recoveryMessagesResponse?.let {
                        _events.emit(PiEvent.SessionRecovered(it, it.rawJson))
                    }
                    val buffered = synchronized(recoveryEventBuffer) {
                        recoveryEventBuffer.toList().also { recoveryEventBuffer.clear() }
                    }
                    for (event in buffered) _events.emit(event)
                    // Opening a socket is not enough: only a complete native Pi recovery proves
                    // that this lease is usable again.
                    reconnectAttempt = 0
                    leaseReacquireCycle = 0
                    _connection.value = PiConnectionState.Connected(lease.sessionId, lease.leaseId)
                }
            }
            recoveryMessagesResponse = null
        }
    }

    private suspend fun handleEvent(event: PiEvent) {
        when (event) {
            is PiEvent.AgentStart -> {
                _agentActive.value = true
                promptGate.onAgentStart()
                if (_connection.value is PiConnectionState.Recovering) recoveryLifecycleOverride = true
            }
            is PiEvent.AgentEnd -> {
                _agentActive.value = false
                promptGate.onAgentEnd()
                if (_connection.value is PiConnectionState.Recovering) recoveryLifecycleOverride = false
            }
            else -> Unit
        }
        if (_connection.value is PiConnectionState.Recovering) {
            synchronized(recoveryEventBuffer) { recoveryEventBuffer += event }
            return
        }
        // Suspend behind slow collectors instead of dropping text/tool/agent_end frames.
        _events.emit(event)
    }

    private fun scheduleReconnect(
        lease: PiLease,
        failedGeneration: Long,
        epoch: Long,
        responseCode: Int? = null,
        closeCode: Int? = null,
    ) {
        if (explicitClose || !socketGenerations.isCurrent(failedGeneration) || epoch != sessionEpoch.get()) return
        if (reconnectJob?.isActive == true) return
        if (LeaseRecoveryPolicy.leaseIsInvalid(responseCode, closeCode) ||
            reconnectAttempt >= LeaseRecoveryPolicy.MAX_SOCKET_RECONNECT_ATTEMPTS
        ) {
            scheduleLeaseReacquire(lease, failedGeneration, epoch)
            return
        }
        reconnectAttempt += 1
        val attempt = reconnectAttempt
        reconnectJob = scope.launch {
            _connection.value = PiConnectionState.Reconnecting(lease.sessionId, attempt)
            delay(LeaseRecoveryPolicy.reconnectDelayMillis(attempt))
            if (explicitClose || epoch != sessionEpoch.get() || !socketGenerations.isCurrent(failedGeneration)) {
                return@launch
            }
            reconnectJob = null
            openSocket(lease, reconnecting = true, epoch = epoch)
        }
    }

    private fun scheduleLeaseReacquire(lease: PiLease, failedGeneration: Long, epoch: Long) {
        if (explicitClose || !socketGenerations.isCurrent(failedGeneration) || epoch != sessionEpoch.get()) return
        if (reconnectJob?.isActive == true) return
        if (leaseReacquireCycle >= LeaseRecoveryPolicy.MAX_LEASE_REACQUIRE_CYCLES) {
            failConnection(
                failedGeneration,
                epoch,
                "Pi session recovery failed after reacquiring its lease",
                retryable = true,
            )
            return
        }
        leaseReacquireCycle += 1

        // Retire the old generation before the management request. Late close/failure callbacks
        // from that lease can no longer start another reconnect loop.
        socketGenerations.invalidate()
        val oldSocket = socket
        socket = null
        activeLease = null
        recovery = null
        recoveryLifecycleOverride = null
        recoveryMessagesResponse = null
        synchronized(recoveryEventBuffer) { recoveryEventBuffer.clear() }
        oldSocket?.close(1000, "reacquiring lease")
        failPending(IOException("Pi RPC lease is no longer usable"))

        reconnectJob = scope.launch {
            var acquireAttempt = 0
            while (!explicitClose && epoch == sessionEpoch.get()) {
                acquireAttempt += 1
                _connection.value = PiConnectionState.AcquiringLease(lease.sessionId)
                when (val result = admin.acquireLease(lease.sessionId, takeover = false)) {
                    is LeaseResult.Acquired -> {
                        if (explicitClose || epoch != sessionEpoch.get()) return@launch
                        activeLease = result.lease
                        reconnectJob = null
                        // onOpen always performs get_state + get_messages before Connected.
                        openSocket(result.lease, reconnecting = false, epoch = epoch)
                        return@launch
                    }
                    is LeaseResult.Conflict -> {
                        _connection.value = PiConnectionState.LeaseConflict(
                            result.sessionId,
                            result.ownerClientId,
                            result.message,
                        )
                        reconnectJob = null
                        return@launch
                    }
                    is LeaseResult.Failure -> {
                        if (!result.retryable || acquireAttempt >= LeaseRecoveryPolicy.MAX_LEASE_ACQUIRE_ATTEMPTS) {
                            _connection.value = PiConnectionState.Failed(result.message, result.retryable)
                            reconnectJob = null
                            return@launch
                        }
                        delay(LeaseRecoveryPolicy.leaseAcquireDelayMillis(acquireAttempt))
                    }
                }
            }
        }
    }

    private fun failConnection(
        failedGeneration: Long,
        epoch: Long,
        message: String,
        retryable: Boolean,
    ) {
        if (explicitClose || !socketGenerations.isCurrent(failedGeneration) || epoch != sessionEpoch.get()) return
        socketGenerations.invalidate()
        val oldSocket = socket
        socket = null
        activeLease = null
        recovery = null
        recoveryLifecycleOverride = null
        recoveryMessagesResponse = null
        synchronized(recoveryEventBuffer) { recoveryEventBuffer.clear() }
        oldSocket?.close(1000, "connection rejected")
        failPending(IOException(message))
        _connection.value = PiConnectionState.Failed(message, retryable)
    }

    private fun invalidateSocket(reason: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        recovery = null
        recoveryLifecycleOverride = null
        recoveryMessagesResponse = null
        synchronized(recoveryEventBuffer) { recoveryEventBuffer.clear() }
        socketGenerations.invalidate()
        val old = socket
        socket = null
        old?.close(1000, reason)
        failPending(CancellationException(reason))
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        explicitClose = true
        sessionEpoch.incrementAndGet()
        invalidateSocket("client closing")
        inbound.close()
        _connection.value = PiConnectionState.Disconnected
        if (ownsScope) scope.cancel()
    }
}
