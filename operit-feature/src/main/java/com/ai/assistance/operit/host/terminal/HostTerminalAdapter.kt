package com.ai.assistance.operit.host.terminal

import com.ai.assistance.operit.host.OperitHostProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Ubuntu-session and managed Termux unified-exec facade. Raw one-shot commands stay explicit. */
class HostTerminalAdapter(
    private val backendProvider: () -> HostTerminalSessionBackend? = {
        OperitHostProvider.currentOperationsOrNull()?.terminalSessionBackend
    },
) {
    private val _terminalState = MutableStateFlow(HostTerminalState())
    private var stateScope: CoroutineScope? = null

    val terminalState: MutableStateFlow<HostTerminalState> = _terminalState

    suspend fun initialize(): Boolean {
        val backend = currentBackend() ?: return false
        val initialized = backend.initialize()
        mirrorBackendState(backend)
        return initialized
    }

    suspend fun destroy() {
        stateScope?.cancel()
        stateScope = null
        currentBackend()?.destroy()
        _terminalState.value = HostTerminalState()
    }

    suspend fun createSession(
        title: String? = null,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT,
    ): String {
        requireUbuntuTarget(target)
        val displayTitle = title?.takeIf { it.isNotBlank() } ?: "ubuntu-${UUID.randomUUID()}"
        val backend = requireBackend()
        ensureBackendMirrored(backend)
        return backend.createSession(displayTitle)
    }

    fun closeSession(sessionId: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { closeSessionAndWait(sessionId) }
        }
    }

    suspend fun closeSessionAndWait(sessionId: String) {
        requireBackend().closeSession(sessionId)
    }

    suspend fun executeCommand(sessionId: String, command: String): String? {
        val result = execute(sessionId, command, DEFAULT_TIMEOUT_MS)
        return result.output.ifBlank { result.error }
    }

    suspend fun executeCommandResult(
        sessionId: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): HostTerminalHiddenResult = execute(sessionId, command, timeoutMs)

    fun executeCommandFlow(
        sessionId: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Flow<HostTerminalCommandEvent> = flow {
        val commandId = UUID.randomUUID().toString()
        val result = execute(sessionId, command, timeoutMs) { chunk ->
            emit(
                HostTerminalCommandEvent(
                    sessionId = sessionId,
                    commandId = commandId,
                    outputChunk = chunk,
                    isCompleted = false,
                    exitCode = 0,
                    target = HostTerminalTarget.UBUNTU,
                ),
            )
        }
        emit(
            HostTerminalCommandEvent(
                sessionId = sessionId,
                commandId = commandId,
                outputChunk = buildOutput(result),
                isCompleted = true,
                exitCode = result.exitCode,
                target = HostTerminalTarget.UBUNTU,
                timedOut = result.state == HostTerminalHiddenResult.State.TIMEOUT,
            ),
        )
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT,
    ): HostTerminalHiddenResult {
        requireUbuntuTarget(target)
        HostTerminalPolicy.rejectionReason(command)?.let { reason -> return rejected(reason) }
        val backend = requireBackend()
        ensureBackendMirrored(backend)
        return backend.executeHiddenCommand(
            command = command.trim(),
            executorKey = executorKey,
            timeoutMs = normalizeTimeoutMs(timeoutMs),
        )
    }

    fun sendInput(sessionId: String, input: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { sendInputAndWait(sessionId, true, input, null) }
        }
    }

    suspend fun sendInputAndWait(
        sessionId: String,
        hasInput: Boolean,
        input: String,
        control: String?,
    ): Int = requireBackend().sendInput(sessionId, hasInput, input, control)

    fun sendInterruptSignal(sessionId: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { sendInterruptSignalAndWait(sessionId) }
        }
    }

    suspend fun sendInterruptSignalAndWait(sessionId: String) {
        requireBackend().sendInput(sessionId, hasInput = true, input = "c", control = "ctrl")
    }

    suspend fun getSessionScreen(sessionId: String): HostTerminalScreenSnapshot =
        requireBackend().getSessionScreen(sessionId)

    suspend fun executeTermuxCommand(
        command: String,
        workingDirectory: String? = null,
        yieldTimeMs: Long = DEFAULT_TERMUX_YIELD_MS,
        sessionName: String? = null,
    ): HostTermuxExecResult {
        require(command.isNotBlank()) { "command must not be blank" }
        val backend = requireBackend()
        ensureBackendMirrored(backend)
        return backend.executeTermuxCommand(
            command = command,
            workingDirectory = workingDirectory,
            yieldTimeMs = normalizeYieldTimeMs(yieldTimeMs),
            sessionName = sessionName,
        )
    }

    suspend fun writeTermuxStdin(
        sessionId: String,
        chars: String = "",
        control: String? = null,
        yieldTimeMs: Long = DEFAULT_TERMUX_YIELD_MS,
        afterCursor: Long? = null,
    ): HostTermuxExecResult = requireBackend().writeTermuxStdin(
        sessionId = sessionId,
        chars = chars,
        control = control,
        yieldTimeMs = normalizeYieldTimeMs(yieldTimeMs),
        afterCursor = afterCursor,
    )

    suspend fun listTermuxSessions(
        includeCompleted: Boolean = false,
    ): List<HostTermuxExecSession> = requireBackend().listTermuxSessions(includeCompleted)

    fun isConnected(): Boolean = currentBackend()?.isConnected() == true

    private suspend fun execute(
        sessionId: String,
        rawCommand: String,
        timeoutMs: Long,
        onOutput: suspend (String) -> Unit = {},
    ): HostTerminalHiddenResult {
        val command = rawCommand.trim()
        HostTerminalPolicy.rejectionReason(command)?.let { return rejected(it) }
        val backend = requireBackend()
        ensureBackendMirrored(backend)
        return backend.executeCommand(
            sessionId = sessionId,
            command = command,
            timeoutMs = normalizeTimeoutMs(timeoutMs),
            onOutput = onOutput,
        )
    }

    private fun currentBackend(): HostTerminalSessionBackend? = backendProvider()

    private fun requireBackend(): HostTerminalSessionBackend =
        currentBackend()
            ?: error(
                "Hosted terminal backend is unavailable. The active host must provide a tmux-backed Termux transport.",
            )

    private fun requireUbuntuTarget(target: HostTerminalTarget) {
        require(target == HostTerminalTarget.UBUNTU) {
            "Terminal sessions support UBUNTU only; use execute_android_command or execute_termux_command for one-shot commands"
        }
    }

    private fun rejected(reason: String) =
        HostTerminalHiddenResult(
            state = HostTerminalHiddenResult.State.REJECTED,
            exitCode = -2,
            output = "",
            error = reason,
            rawOutputPreview = reason,
            target = HostTerminalTarget.UBUNTU,
        )

    private fun ensureBackendMirrored(backend: HostTerminalSessionBackend) {
        if (stateScope == null) mirrorBackendState(backend)
    }

    private fun mirrorBackendState(backend: HostTerminalSessionBackend) {
        stateScope?.cancel()
        stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            scope.launch {
                backend.terminalState.collectLatest { state -> _terminalState.value = state }
            }
        }
        _terminalState.value = backend.terminalState.value
    }

    private fun normalizeTimeoutMs(timeoutMs: Long): Long =
        timeoutMs.takeIf { it > 0L }?.coerceAtMost(MAX_HOSTED_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS

    private fun normalizeYieldTimeMs(yieldTimeMs: Long): Long =
        yieldTimeMs.coerceIn(MIN_TERMUX_YIELD_MS, MAX_TERMUX_YIELD_MS)

    private fun buildOutput(result: HostTerminalHiddenResult): String =
        buildString {
            if (result.output.isNotBlank()) append(result.output)
            if (result.error.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(result.error)
            }
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_HOSTED_TIMEOUT_MS = 86_400_000L
        const val DEFAULT_TERMUX_YIELD_MS = 10_000L
        const val MIN_TERMUX_YIELD_MS = 0L
        const val MAX_TERMUX_YIELD_MS = 300_000L
    }
}
