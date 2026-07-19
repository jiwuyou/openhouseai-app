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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PiServiceConfig(val baseUrl: HttpUrl) {
    init {
        requireLoopbackService(baseUrl)
    }
}

sealed interface PiConnectionState {
    data object Disconnected : PiConnectionState
    data object Connecting : PiConnectionState
    data class Recovering(val sessionId: String, val sessionPath: String) : PiConnectionState
    data class Connected(val sessionId: String? = null, val sessionPath: String? = null) : PiConnectionState
    data class Reconnecting(val attempt: Int) : PiConnectionState
    data class Failed(val message: String, val retryable: Boolean) : PiConnectionState
}

private data class InboundFrame(val socketGeneration: Long, val text: String)

private data class PendingCommand(
    val command: String,
    val deferred: CompletableDeferred<PiResponse>,
)

internal object ReconnectPolicy {
    const val MAX_ATTEMPTS = 8

    fun delayMillis(attempt: Int): Long =
        (250L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(8_000L)
}

internal val PROMPT_ACCEPT_TIMEOUT_MILLIS: Long? = null

/**
 * Direct client for WuxianPi's embedded Node Pi SDK service.
 *
 * This class only correlates commands and forwards SDK events. Pi owns the agent loop, tools,
 * retries, compaction, and provider behavior.
 */
class WuxianPiClient(
    private val config: PiServiceConfig,
    private val http: OkHttpClient = OkHttpClient(),
    parentScope: CoroutineScope? = null,
) : AutoCloseable {
    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownsScope = parentScope == null
    private val requestCounter = AtomicLong(0)
    private val socketGenerations = SocketGenerationGate()
    private val pending = ConcurrentHashMap<String, PendingCommand>()
    private val promptGate = PromptGate()
    private val activationMutex = Mutex()
    private val activationInFlight = AtomicBoolean(false)
    private val _events = MutableSharedFlow<PiEvent>(extraBufferCapacity = 64)
    private val _responses = MutableSharedFlow<PiResponse>(extraBufferCapacity = 64)
    private val _connection = MutableStateFlow<PiConnectionState>(PiConnectionState.Disconnected)
    private val _agentActive = MutableStateFlow(false)
    private val inbound = OrderedQueue<InboundFrame>(scope, consume = ::handleFrame)
    private val lastSequence = ConcurrentHashMap<String, Long>()

    val events: SharedFlow<PiEvent> = _events.asSharedFlow()
    val responses: SharedFlow<PiResponse> = _responses.asSharedFlow()
    val connection: StateFlow<PiConnectionState> = _connection.asStateFlow()
    val agentActive: StateFlow<Boolean> = _agentActive.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var explicitClose = false
    @Volatile private var activeSession: PiSessionRef? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    suspend fun connect(timeoutMillis: Long = 15_000) {
        if (_connection.value is PiConnectionState.Connected) return
        explicitClose = false
        reconnectJob?.cancel()
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        openSocket(reconnecting = false)
        withTimeout(timeoutMillis) { waiter.await() }
    }

    suspend fun runtimeStatus(): PiResponse = command("runtime.status", includeSession = false)

    suspend fun modelStatus(provider: String? = null): PiModelStatus {
        val response = command(
            "model.status",
            JSONObject().apply { if (!provider.isNullOrBlank()) put("provider", provider) },
            includeSession = false,
        )
        return PiModelStatus.from(response.requireSuccess())
    }

    suspend fun loginModelProvider(provider: String, apiKey: String): PiModelLoginResult {
        require(provider.isNotBlank()) { "Provider is required" }
        require(apiKey.isNotBlank()) { "API key is required" }
        val response = command(
            "model.login",
            JSONObject()
                .put("provider", provider)
                .put("method", "api_key")
                .put("apiKey", apiKey),
            timeoutMillis = 60_000,
            includeSession = false,
        )
        return PiModelLoginResult.from(response.requireSuccess())
    }

    suspend fun logoutModelProvider(provider: String): PiModelLoginResult {
        require(provider.isNotBlank()) { "Provider is required" }
        val response = command(
            "model.logout",
            JSONObject().put("provider", provider),
            includeSession = false,
        )
        return PiModelLoginResult.from(response.requireSuccess())
    }

    suspend fun testModel(provider: String, modelId: String, timeoutMillis: Long = 30_000): PiModelTestResult {
        require(provider.isNotBlank()) { "Provider is required" }
        require(modelId.isNotBlank()) { "Model is required" }
        val response = command(
            "model.test",
            JSONObject()
                .put("provider", provider)
                .put("modelId", modelId)
                .put("timeoutMs", timeoutMillis),
            timeoutMillis = timeoutMillis + 5_000,
            includeSession = false,
        )
        return PiModelTestResult.from(response.requireSuccess())
    }

    suspend fun reloadModels(): PiModelStatus {
        val response = command("model.reload", includeSession = false, timeoutMillis = 60_000)
        return PiModelStatus.from(response.requireSuccess())
    }

    suspend fun setDefaultModel(provider: String, modelId: String): PiSetDefaultResult {
        require(provider.isNotBlank()) { "Provider is required" }
        require(modelId.isNotBlank()) { "Model is required" }
        check(!_agentActive.value) { "Wait for the active Pi turn to finish before switching models" }
        val response = executeCommand(
            type = "model.setDefault",
            payload = JSONObject().put("provider", provider).put("modelId", modelId),
            timeoutMillis = 60_000,
            includeSession = false,
            isPrompt = false,
            onQueued = null,
            sessionIdOverride = activeSession?.sessionId,
        )
        return PiSetDefaultResult.from(response.requireSuccess())
    }

    suspend fun listSessions(
        cwd: String? = null,
        all: Boolean = true,
        offset: Int = 0,
        limit: Int = 100,
    ): PiResponse = command(
        "session.list",
        JSONObject()
            .apply { if (!cwd.isNullOrBlank()) put("cwd", cwd) }
            .put("all", all)
            .put("offset", offset.coerceAtLeast(0))
            .put("limit", limit.coerceIn(1, 500)),
        includeSession = false,
    )

    suspend fun createSession(cwd: String? = null): PiSessionRef {
        return replaceSession {
            command(
                "session.create",
                JSONObject().apply { if (!cwd.isNullOrBlank()) put("cwd", cwd) },
                includeSession = false,
            )
        }
    }

    suspend fun newSession(): PiSessionRef = replaceSession { command("session.new") }

    suspend fun openSession(sessionPath: String): PiSessionRef {
        require(sessionPath.isNotBlank()) { "sessionPath is required" }
        return replaceSession {
            command(
                "session.open",
                JSONObject().put("sessionPath", sessionPath),
                includeSession = false,
            )
        }
    }

    suspend fun history(
        sessionPath: String? = activeSession?.sessionPath,
        offset: Int = 0,
        limit: Int = 500,
    ): PiResponse = command(
        "session.history",
        JSONObject()
            .apply { if (!sessionPath.isNullOrBlank()) put("sessionPath", sessionPath) }
            .put("offset", offset.coerceAtLeast(0))
            .put("limit", limit.coerceIn(1, 2_000)),
        includeSession = activeSession != null,
    )

    suspend fun prompt(message: String, onQueued: () -> Unit = {}): PiResponse = executeCommand(
        type = "session.prompt",
        payload = JSONObject().put("message", message),
        timeoutMillis = PROMPT_ACCEPT_TIMEOUT_MILLIS,
        includeSession = true,
        isPrompt = true,
        onQueued = onQueued,
        sessionIdOverride = null,
    )

    suspend fun steer(message: String): PiResponse =
        command("session.steer", JSONObject().put("message", message))

    suspend fun followUp(message: String): PiResponse =
        command("session.followUp", JSONObject().put("message", message))

    suspend fun abort(): PiResponse = command("session.abort")

    suspend fun compact(customInstructions: String? = null): PiResponse = command(
        "session.compact",
        JSONObject().apply {
            if (!customInstructions.isNullOrBlank()) put("customInstructions", customInstructions)
        },
        timeoutMillis = 120_000,
    )

    suspend fun sessionState(): PiResponse = command("session.state")

    suspend fun fork(entryId: String, position: String? = null): PiSessionRef {
        require(position == null || position == "before" || position == "at") {
            "Fork position must be before or at"
        }
        return replaceSession {
            command(
                "session.fork",
                JSONObject().put("entryId", entryId).apply { if (position != null) put("position", position) },
                timeoutMillis = 60_000,
            )
        }
    }

    suspend fun switchSession(sessionPath: String): PiSessionRef {
        return replaceSession {
            command(
                "session.switch",
                JSONObject().put("sessionPath", sessionPath),
                timeoutMillis = 60_000,
            )
        }
    }

    suspend fun importSession(inputPath: String, cwd: String? = null): PiSessionRef {
        return replaceSession {
            command(
                "session.import",
                JSONObject().put("inputPath", inputPath).apply { if (!cwd.isNullOrBlank()) put("cwd", cwd) },
                timeoutMillis = 120_000,
                includeSession = true,
            )
        }
    }

    suspend fun closeSession(): PiResponse {
        val response = command("session.close")
        if (response.success) {
            activeSession = null
            _agentActive.value = false
            promptGate.onRecovered(false)
            _connection.value = PiConnectionState.Connected()
        }
        return response
    }

    suspend fun reloadExtensions(): PiResponse = command("extension.reload")

    suspend fun extensionUiResponse(requestId: String, response: Any?, sessionId: String? = null): PiResponse {
        val payload = JSONObject().put("requestId", requestId)
        when (response) {
            null -> payload.put("cancelled", true)
            is Boolean -> payload.put("confirmed", response)
            else -> payload.put("value", response)
        }
        return executeCommand(
            type = "extension.uiResponse",
            payload = payload,
            timeoutMillis = 30_000,
            includeSession = true,
            isPrompt = false,
            onQueued = null,
            sessionIdOverride = sessionId,
        )
    }

    suspend fun command(
        type: String,
        payload: JSONObject = JSONObject(),
        timeoutMillis: Long = 30_000,
        includeSession: Boolean = true,
    ): PiResponse = executeCommand(type, payload, timeoutMillis, includeSession, false, null, null)

    private suspend fun executeCommand(
        type: String,
        payload: JSONObject,
        timeoutMillis: Long?,
        includeSession: Boolean,
        isPrompt: Boolean,
        onQueued: (() -> Unit)?,
        sessionIdOverride: String?,
    ): PiResponse {
        val sessionId = sessionIdOverride ?: if (includeSession) {
            activeSession?.sessionId ?: throw IllegalStateException("No Pi session is open")
        } else {
            null
        }
        if (isPrompt && !promptGate.tryBegin(
                connected = _connection.value is PiConnectionState.Connected,
                isStreaming = _agentActive.value,
            )
        ) {
            throw IllegalStateException("A Pi turn is active or session recovery is incomplete")
        }

        val id = "android-${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
        val deferred = CompletableDeferred<PiResponse>()
        pending[id] = PendingCommand(type, deferred)
        val current = socket
        if (current == null || !current.send(PiProtocol.request(id, type, sessionId, payload))) {
            pending.remove(id)
            if (isPrompt) promptGate.onPromptRejected()
            throw IOException("WuxianPi service is not connected")
        }
        onQueued?.invoke()
        return try {
            val response = if (timeoutMillis == null) deferred.await() else withTimeout(timeoutMillis) {
                deferred.await()
            }
            if (isPrompt && !response.success) promptGate.onPromptRejected()
            response
        } finally {
            // A timed-out accepted prompt stays gated until agent_settled or authoritative recovery.
            pending.remove(id)
        }
    }

    private fun openSocket(reconnecting: Boolean) {
        if (explicitClose) return
        _connection.value = if (reconnecting) {
            PiConnectionState.Reconnecting(reconnectAttempt)
        } else {
            PiConnectionState.Connecting
        }
        val generation = socketGenerations.next()
        val request = Request.Builder().url(resolveServiceWebSocketUrl(config.baseUrl)).build()
        socket = http.newWebSocket(request, SocketListener(generation))
    }

    private inner class SocketListener(private val generation: Long) : WebSocketListener() {
        private fun current(): Boolean = socketGenerations.isCurrent(generation) && !explicitClose

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current()) {
                webSocket.close(1000, "superseded")
                return
            }
            socket = webSocket
            // A service restart starts event sequence numbers again.
            lastSequence.clear()
            reconnectJob = null
            reconnectAttempt = 0
            val restore = activeSession
            if (restore == null) {
                _connection.value = PiConnectionState.Connected()
                connectWaiter?.complete(Unit)
                connectWaiter = null
            } else {
                _connection.value = PiConnectionState.Recovering(restore.sessionId, restore.sessionPath)
                scope.launch { recoverSession(generation, restore) }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (current() && inbound.offer(InboundFrame(generation, text)) ==
                QueueOfferResult.OVERFLOW_REQUIRES_RECOVERY
            ) {
                if (socket === webSocket) socket = null
                failPending(IOException("WuxianPi inbound queue overflow"))
                webSocket.close(1013, "inbound queue overflow")
                scheduleReconnect(generation)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return
            if (socket === webSocket) socket = null
            failPending(IOException("WuxianPi connection closed ($code): $reason"))
            scheduleReconnect(generation)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            if (socket === webSocket) socket = null
            failPending(IOException("WuxianPi connection lost", t))
            scheduleReconnect(generation)
        }
    }

    private suspend fun recoverSession(generation: Long, previous: PiSessionRef) {
        runCatching {
            val restored = openSession(previous.sessionPath)
            if (!socketGenerations.isCurrent(generation)) return
            _agentActive.value = restored.isRunning ?: _agentActive.value
            promptGate.onRecovered(_agentActive.value)
            val history = history(restored.sessionPath)
            if (!socketGenerations.isCurrent(generation)) return
            _events.emit(
                PiEvent.SessionRecovered(
                    response = history,
                    sessionId = restored.sessionId,
                    sessionPath = restored.sessionPath,
                    rawJson = history.rawJson,
                ),
            )
            _connection.value = PiConnectionState.Connected(restored.sessionId, restored.sessionPath)
            connectWaiter?.complete(Unit)
            connectWaiter = null
        }.onFailure { error ->
            if (!socketGenerations.isCurrent(generation)) return@onFailure
            _connection.value = PiConnectionState.Failed(
                error.message ?: "Pi session recovery failed",
                retryable = true,
            )
            connectWaiter?.completeExceptionally(error)
            connectWaiter = null
        }
    }

    private suspend fun handleFrame(frame: InboundFrame) {
        if (!socketGenerations.isCurrent(frame.socketGeneration)) return
        when (val parsed = PiProtocol.parse(frame.text)) {
            is ParsedPiFrame.Response -> handleResponse(parsed.value)
            is ParsedPiFrame.Event -> handleEvent(parsed.value)
        }
    }

    private suspend fun handleResponse(response: PiResponse) {
        val matched = pending.remove(response.id)
        val correlated = if (response.command == null && matched != null) {
            response.copy(command = matched.command)
        } else {
            response
        }
        matched?.deferred?.complete(correlated)
        _responses.emit(correlated)
        if (!correlated.success) {
            _events.emit(
                PiEvent.CommandError(
                    response = correlated,
                    sessionId = activeSession?.sessionId,
                    sessionPath = activeSession?.sessionPath,
                    rawJson = correlated.rawJson,
                ),
            )
        }
    }

    private suspend fun handleEvent(event: PiEvent) {
        if (!eventBelongsToActiveSession(
                activeSession?.sessionId,
                event.sessionId,
                activationInFlight.get(),
            )
        ) return
        if (!acceptSequence(event)) return
        when (event) {
            is PiEvent.AgentStart -> {
                _agentActive.value = true
                promptGate.onAgentStart()
            }
            is PiEvent.AgentSettled -> {
                _agentActive.value = false
                promptGate.onAgentSettled()
            }
            is PiEvent.PromptCompleted -> {
                if (event.handledWithoutAgent && !event.isRunning) {
                    promptGate.onPromptCompletedWithoutAgent()
                }
            }
            else -> Unit
        }
        _events.emit(event)
    }

    private fun acceptSequence(event: PiEvent): Boolean {
        val id = event.sessionId ?: return true
        val sequence = event.sequence ?: return true
        var accepted = false
        lastSequence.compute(id) { _, previous ->
            if (previous == null || sequence > previous) {
                accepted = true
                sequence
            } else {
                previous
            }
        }
        return accepted
    }

    private fun activate(session: PiSessionRef) {
        activeSession = session
        session.isRunning?.let {
            _agentActive.value = it
            promptGate.onRecovered(it)
        }
        _connection.value = PiConnectionState.Connected(session.sessionId, session.sessionPath)
    }

    private suspend fun replaceSession(block: suspend () -> PiResponse): PiSessionRef =
        activationMutex.withLock {
            activationInFlight.set(true)
            try {
                block().requireSuccess().let(PiSessionRef::from).also(::activate)
            } finally {
                activationInFlight.set(false)
            }
        }

    private fun scheduleReconnect(failedGeneration: Long) {
        if (explicitClose || !socketGenerations.isCurrent(failedGeneration)) return
        if (reconnectJob?.isActive == true) return
        reconnectAttempt += 1
        if (reconnectAttempt > ReconnectPolicy.MAX_ATTEMPTS) {
            _connection.value = PiConnectionState.Failed("WuxianPi service is unavailable", retryable = true)
            connectWaiter?.completeExceptionally(IOException("WuxianPi service is unavailable"))
            connectWaiter = null
            return
        }
        val attempt = reconnectAttempt
        reconnectJob = scope.launch {
            _connection.value = PiConnectionState.Reconnecting(attempt)
            delay(ReconnectPolicy.delayMillis(attempt))
            if (explicitClose || !socketGenerations.isCurrent(failedGeneration)) return@launch
            reconnectJob = null
            openSocket(reconnecting = true)
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.deferred.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        explicitClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        socketGenerations.invalidate()
        val old = socket
        socket = null
        old?.close(1000, "client closing")
        failPending(CancellationException("client closing"))
        connectWaiter?.cancel()
        connectWaiter = null
        inbound.close()
        _connection.value = PiConnectionState.Disconnected
        if (ownsScope) scope.cancel()
    }
}

internal fun eventBelongsToActiveSession(
    activeSessionId: String?,
    eventSessionId: String?,
    activationInFlight: Boolean,
): Boolean = eventSessionId == null || activationInFlight ||
    (activeSessionId != null && activeSessionId == eventSessionId)

private fun PiResponse.requireSuccess(): Any? {
    if (!success) throw IOException(error ?: "Pi command failed")
    return data
}
