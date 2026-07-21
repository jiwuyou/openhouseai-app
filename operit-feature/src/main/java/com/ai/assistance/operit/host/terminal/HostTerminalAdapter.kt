package com.ai.assistance.operit.host.terminal

import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.OperitHostServiceManagerRecoveryAction
import com.ai.assistance.operit.host.executeServiceManagerRecoveryCommand
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class HostTerminalAdapter {
    private val sessions = ConcurrentHashMap<String, MutableSession>()
    private val _terminalState = MutableStateFlow(HostTerminalState())

    val terminalState: MutableStateFlow<HostTerminalState> = _terminalState

    suspend fun initialize(): Boolean {
        if (OperitHostProvider.currentOperationsOrNull() == null) {
            return false
        }
        refreshState()
        return true
    }

    fun destroy() {
        sessions.clear()
        refreshState()
    }

    suspend fun createSession(
        title: String? = null,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT
    ): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = MutableSession(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: "${target.sessionPrefix}-$id",
            target = target
        )
        refreshState()
        return id
    }

    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)
        refreshState()
    }

    suspend fun executeCommand(sessionId: String, command: String): String? {
        val result = execute(sessionId, command, DEFAULT_TIMEOUT_MS)
        return result.output.ifBlank { result.error }
    }

    suspend fun executeCommandResult(
        sessionId: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HostTerminalHiddenResult {
        return execute(sessionId, command, timeoutMs)
    }

    fun executeCommandFlow(
        sessionId: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<HostTerminalCommandEvent> = flow {
        val commandId = UUID.randomUUID().toString()
        val result = execute(sessionId, command, timeoutMs)
        val output = buildOutput(result)
        if (output.isNotEmpty()) {
            emit(
                HostTerminalCommandEvent(
                    sessionId = sessionId,
                    commandId = commandId,
                    outputChunk = output,
                    isCompleted = false,
                    exitCode = result.exitCode,
                    target = result.target
                )
            )
        }
        emit(
            HostTerminalCommandEvent(
                sessionId = sessionId,
                commandId = commandId,
                outputChunk = output,
                isCompleted = true,
                exitCode = result.exitCode,
                target = result.target
            )
        )
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT
    ): HostTerminalHiddenResult {
        val sessionId = "hidden-${target.wireName}-$executorKey"
        sessions.putIfAbsent(
            sessionId,
            MutableSession(sessionId, "hidden-${target.displayName}-$executorKey", target)
        )
        return execute(sessionId, command, timeoutMs)
    }

    fun sendInput(sessionId: String, input: String) {
        appendOutput(sessionId, input)
    }

    fun sendInterruptSignal(sessionId: String) {
        appendOutput(sessionId, "^C")
    }

    fun isConnected(): Boolean = OperitHostProvider.currentOperationsOrNull() != null

    private suspend fun execute(
        sessionId: String,
        rawCommand: String,
        timeoutMs: Long
    ): HostTerminalHiddenResult {
        val session = sessions.getOrPut(sessionId) { MutableSession(sessionId, sessionId) }
        val command = rawCommand.trim()
        executeServiceManagerRecovery(session, command)?.let { return it }
        HostTerminalPolicy.rejectionReason(command)?.let { reason ->
            val result =
                HostTerminalHiddenResult(
                    state = HostTerminalHiddenResult.State.REJECTED,
                    exitCode = -2,
                    output = "",
                    error = reason,
                    rawOutputPreview = reason,
                    target = session.target
                )
            completeSession(session, command, result)
            return result
        }

        val commandToRun = buildSessionCommand(session, command)
        session.isExecuting = true
        refreshState()
        val effectiveTimeoutMs = normalizeTimeoutMs(timeoutMs)
        val execution =
            withContext(Dispatchers.IO) {
                runCatching {
                    executeWithTargetFallback(session.target, commandToRun, effectiveTimeoutMs)
                }
                    .getOrElse { throwable ->
                        HostExecution(
                            result =
                                OperitHostCommandResult(
                                    command = commandToRun,
                                    exitCode = -1,
                                    stdout = "",
                                    stderr = "",
                                    error =
                                        "${session.target.displayName} host terminal unavailable: " +
                                            (throwable.message ?: throwable::class.java.simpleName),
                                    timedOut = false,
                                    durationMs = 0L
                                ),
                            resolvedTarget = session.target
                        )
                    }
            }
        val hostResult = execution.result

        applyDirectoryMutation(session, command)

        val state =
            when {
                hostResult.timedOut -> HostTerminalHiddenResult.State.TIMEOUT
                hostResult.isSuccess -> HostTerminalHiddenResult.State.SUCCESS
                else -> HostTerminalHiddenResult.State.FAILED
            }
        val result =
            HostTerminalHiddenResult(
                state = state,
                exitCode = hostResult.exitCode,
                output = hostResult.stdout,
                error = listOf(hostResult.error, hostResult.stderr).filter { it.isNotBlank() }.joinToString("\n"),
                rawOutputPreview = buildString {
                    append(hostResult.stdout)
                    if (hostResult.stderr.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(hostResult.stderr)
                    }
                    if (hostResult.error.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(hostResult.error)
                    }
                }.takeLast(4000),
                target = execution.resolvedTarget
            )
        completeSession(session, command, result)
        return result
    }

    private suspend fun executeServiceManagerRecovery(
        session: MutableSession,
        command: String
    ): HostTerminalHiddenResult? {
        if (OperitHostServiceManagerRecoveryAction.fromCommand(command) == null) {
            return null
        }

        session.isExecuting = true
        refreshState()
        val startedAtMs = System.currentTimeMillis()
        val hostResult =
            withContext(Dispatchers.IO) {
                runCatching {
                        OperitHostProvider
                            .requireHost()
                            .executeServiceManagerRecoveryCommand(
                                command = command,
                                reason = "host-terminal:${session.target.wireName}"
                            )
                    }
                    .getOrElse { throwable ->
                        OperitHostCommandResult(
                            command = command,
                            exitCode = -1,
                            stdout = "",
                            stderr = "",
                            error =
                                "${session.target.displayName} host terminal unavailable: " +
                                    (throwable.message ?: throwable::class.java.simpleName),
                            timedOut = false,
                            durationMs = System.currentTimeMillis() - startedAtMs
                        )
                    }
            }
                ?: OperitHostCommandResult(
                    command = command,
                    exitCode = 1,
                    stdout = "",
                    stderr = "",
                    error = "Command is not a service-manager recovery request.",
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - startedAtMs
                )

        val state =
            when {
                hostResult.timedOut -> HostTerminalHiddenResult.State.TIMEOUT
                hostResult.isSuccess -> HostTerminalHiddenResult.State.SUCCESS
                else -> HostTerminalHiddenResult.State.FAILED
            }
        val result =
            HostTerminalHiddenResult(
                state = state,
                exitCode = hostResult.exitCode,
                output = hostResult.stdout,
                error = listOf(hostResult.error, hostResult.stderr).filter { it.isNotBlank() }.joinToString("\n"),
                rawOutputPreview = buildString {
                    append(hostResult.stdout)
                    if (hostResult.stderr.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(hostResult.stderr)
                    }
                    if (hostResult.error.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(hostResult.error)
                    }
                }.takeLast(4000),
                target = HostTerminalTarget.HOST
            )
        completeSession(session, command, result)
        return result
    }

    private suspend fun executeWithTargetFallback(
        requestedTarget: HostTerminalTarget,
        command: String,
        timeoutMs: Long
    ): HostExecution {
        val result = OperitHostProvider.operationsOrUnsupported().executeCommand(
            command = command,
            target = requestedTarget,
            timeoutMs = timeoutMs,
        )
        return HostExecution(result, requestedTarget)
    }

    private fun buildSessionCommand(session: MutableSession, command: String): String {
        val cdPrefix = session.currentDirectory?.takeIf { it.isNotBlank() }?.let {
            "cd ${HostTerminalPolicy.shellQuote(it)} && "
        } ?: ""
        val environmentPrefix =
            session.environmentCommands
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = " && ", postfix = " && ")
                ?: ""
        return "$cdPrefix$environmentPrefix$command"
    }

    private fun normalizeTimeoutMs(timeoutMs: Long): Long {
        if (timeoutMs <= 0L) {
            return DEFAULT_TIMEOUT_MS
        }
        return timeoutMs.coerceAtMost(MAX_HOSTED_TIMEOUT_MS)
    }

    private fun applyDirectoryMutation(session: MutableSession, command: String) {
        val cdTarget =
            Regex("""^\s*cd\s+(.+?)\s*$""")
                .matchEntire(command)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim('"', '\'')
        if (cdTarget != null) {
            session.currentDirectory = cdTarget
            return
        }

        val sourceCommand =
            Regex("""^\s*(source|\.)\s+(.+?)\s*$""")
                .matchEntire(command)
                ?.value
                ?.trim()
                ?: return
        if (session.environmentCommands.none { it == sourceCommand }) {
            session.environmentCommands += sourceCommand
        }
    }

    private fun completeSession(
        session: MutableSession,
        command: String,
        result: HostTerminalHiddenResult
    ) {
        session.isExecuting = false
        val output = buildOutput(result)
        session.lastOutput =
            buildString {
                if (session.lastOutput.isNotBlank()) {
                    append(session.lastOutput)
                    append('\n')
                }
                append("[")
                append(targetLabel(session.target, result.target))
                append("] $ ")
                append(command)
                if (output.isNotBlank()) {
                    append('\n')
                    append(output)
                }
            }.takeLast(MAX_SCREEN_CHARS)
        refreshState()
    }

    private fun targetLabel(requestedTarget: HostTerminalTarget, resolvedTarget: HostTerminalTarget): String {
        return if (requestedTarget == HostTerminalTarget.AUTO && resolvedTarget != HostTerminalTarget.AUTO) {
            "${requestedTarget.wireName}->${resolvedTarget.wireName}"
        } else {
            resolvedTarget.wireName
        }
    }

    private fun appendOutput(sessionId: String, output: String) {
        val session = sessions[sessionId] ?: return
        session.lastOutput = (session.lastOutput + output).takeLast(MAX_SCREEN_CHARS)
        refreshState()
    }

    private fun buildOutput(result: HostTerminalHiddenResult): String {
        return buildString {
            if (result.output.isNotBlank()) append(result.output)
            if (result.error.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(result.error)
            }
        }
    }

    private fun refreshState() {
        _terminalState.update {
            HostTerminalState(
                sessions =
                    sessions.values
                        .sortedBy { it.title }
                        .map {
                            HostTerminalSession(
                                id = it.id,
                                title = it.title,
                                target = it.target,
                                currentDirectory = it.currentDirectory,
                                lastOutput = it.lastOutput,
                                currentExecutingCommand =
                                    HostTerminalExecutingCommand(it.isExecuting)
                            )
                        }
            )
        }
    }

    private data class MutableSession(
        val id: String,
        val title: String,
        val target: HostTerminalTarget = HostTerminalTarget.DEFAULT,
        var currentDirectory: String? = null,
        val environmentCommands: MutableList<String> = mutableListOf(),
        var lastOutput: String = "",
        var isExecuting: Boolean = false
    )

    private data class HostExecution(
        val result: OperitHostCommandResult,
        val resolvedTarget: HostTerminalTarget
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_HOSTED_TIMEOUT_MS = 60_000L
        private const val MAX_SCREEN_CHARS = 32_000
    }
}
