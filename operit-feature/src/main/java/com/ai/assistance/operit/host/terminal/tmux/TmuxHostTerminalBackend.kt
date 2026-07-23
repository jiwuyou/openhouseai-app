package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTerminalExecutingCommand
import com.ai.assistance.operit.host.terminal.HostTerminalHiddenResult
import com.ai.assistance.operit.host.terminal.HostTerminalScreenSnapshot
import com.ai.assistance.operit.host.terminal.HostTerminalSession
import com.ai.assistance.operit.host.terminal.HostTerminalSessionBackend
import com.ai.assistance.operit.host.terminal.HostTerminalState
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared real-session backend used by both RUN_COMMAND and embedded Termux transports. */
class TmuxHostTerminalBackend(
    transport: TermuxSessionTransport,
) : HostTerminalSessionBackend {
    private val manager = TermuxSessionManager(transport)
    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val ownedSessionIds = ConcurrentHashMap.newKeySet<String>()
    private val _terminalState = MutableStateFlow(HostTerminalState())

    @Volatile
    private var connected = false

    override val terminalState: StateFlow<HostTerminalState> = _terminalState

    override suspend fun initialize(): Boolean {
        connected = manager.initialize()
        return connected
    }

    override suspend fun destroy() {
        var firstFailure: Throwable? = null
        ownedSessionIds.toList().forEach { sessionId ->
            runCatching { manager.closeIfExists(sessionId) }
                .onFailure { error -> if (firstFailure == null) firstFailure = error }
        }
        ownedSessionIds.clear()
        sessions.clear()
        connected = false
        refreshState()
        firstFailure?.let { error ->
            throw IllegalStateException("Failed to close all owned tmux sessions", error)
        }
    }

    override suspend fun createSession(title: String): String {
        val creation = manager.createOrGet(title)
        ownedSessionIds += creation.sessionId
        sessions[creation.sessionId] =
            SessionRecord(
                id = creation.sessionId,
                title = creation.sessionName,
                requestedTarget = HostTerminalTarget.UBUNTU,
                resolvedTarget = creation.target,
            )
        connected = true
        refreshState()
        return creation.sessionId
    }

    override suspend fun closeSession(sessionId: String) {
        manager.close(sessionId)
        ownedSessionIds.remove(sessionId)
        sessions.remove(sessionId)
        refreshState()
    }

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        onOutput: suspend (String) -> Unit,
    ): HostTerminalHiddenResult {
        val session = sessions[sessionId] ?: error("Terminal session does not exist: $sessionId")
        session.isExecuting = true
        refreshState()
        return try {
            val execution = manager.execute(sessionId, command, timeoutMs) { chunk ->
                session.lastOutput = (session.lastOutput + chunk).takeLast(MAX_SCREEN_CHARS)
                refreshState()
                onOutput(chunk)
            }
            session.lastOutput = execution.output.takeLast(MAX_SCREEN_CHARS)
            execution.toHiddenResult()
        } catch (error: Exception) {
            failure(command, session.resolvedTarget, error)
        } finally {
            session.isExecuting = false
            refreshState()
        }
    }

    override suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long,
    ): HostTerminalHiddenResult {
        val sessionId = TermuxSessionProtocol.hiddenSessionIdForKey(executorKey)
        ownedSessionIds += sessionId
        return try {
            manager.executeHidden(executorKey, command, timeoutMs).toHiddenResult()
        } catch (error: Exception) {
            failure(command, HostTerminalTarget.UBUNTU, error)
        }
    }

    override suspend fun sendInput(
        sessionId: String,
        hasInput: Boolean,
        input: String,
        control: String?,
    ): Int = manager.input(sessionId, hasInput, input, control)

    override suspend fun getSessionScreen(sessionId: String): HostTerminalScreenSnapshot {
        val screen = manager.screen(sessionId)
        sessions[sessionId]?.let { session ->
            session.lastOutput = screen.content.takeLast(MAX_SCREEN_CHARS)
            refreshState()
        }
        return HostTerminalScreenSnapshot(screen.sessionId, screen.rows, screen.cols, screen.content)
    }

    override fun isConnected(): Boolean = connected

    private fun TermuxSessionExecution.toHiddenResult(): HostTerminalHiddenResult {
        val state =
            when {
                timedOut -> HostTerminalHiddenResult.State.TIMEOUT
                exitCode == 0 -> HostTerminalHiddenResult.State.SUCCESS
                else -> HostTerminalHiddenResult.State.FAILED
            }
        return HostTerminalHiddenResult(
            state = state,
            exitCode = exitCode,
            output = output,
            error = if (exitCode == 0 || timedOut) "" else "Command exited with code $exitCode",
            rawOutputPreview = output.takeLast(4_000),
            target = target,
        )
    }

    private fun failure(
        command: String,
        target: HostTerminalTarget,
        error: Exception,
    ): HostTerminalHiddenResult {
        val message = error.message ?: error::class.java.simpleName
        return HostTerminalHiddenResult(
            state = HostTerminalHiddenResult.State.FAILED,
            exitCode = -1,
            output = "",
            error = message,
            rawOutputPreview = "$command\n$message".takeLast(4_000),
            target = target,
        )
    }

    private fun refreshState() {
        _terminalState.update {
            HostTerminalState(
                sessions =
                    sessions.values.sortedBy { record -> record.title }.map { record ->
                        HostTerminalSession(
                            id = record.id,
                            title = record.title,
                            target = record.requestedTarget,
                            lastOutput = record.lastOutput,
                            currentExecutingCommand =
                                HostTerminalExecutingCommand(record.isExecuting),
                        )
                    },
            )
        }
    }

    private data class SessionRecord(
        val id: String,
        val title: String,
        val requestedTarget: HostTerminalTarget,
        val resolvedTarget: HostTerminalTarget,
        var lastOutput: String = "",
        var isExecuting: Boolean = false,
    )

    private companion object {
        const val MAX_SCREEN_CHARS = 32_000
    }
}
