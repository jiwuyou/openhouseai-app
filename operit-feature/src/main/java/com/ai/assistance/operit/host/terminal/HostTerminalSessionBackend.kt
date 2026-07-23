package com.ai.assistance.operit.host.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * Host-owned persistent terminal implementation used by the shared Operit terminal tools.
 *
 * Ubuntu interactive sessions and managed Termux unified-exec sessions share this host boundary.
 * Native hosts back it with RUN_COMMAND -> Termux tmux; embedded hosts execute the same protocol
 * directly. Neither path may fall back to the Android shell.
 */
interface HostTerminalSessionBackend {
    val terminalState: StateFlow<HostTerminalState>

    suspend fun initialize(): Boolean

    suspend fun destroy()

    suspend fun createSession(title: String): String

    suspend fun closeSession(sessionId: String)

    suspend fun executeCommand(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        onOutput: suspend (String) -> Unit = {},
    ): HostTerminalHiddenResult

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long,
    ): HostTerminalHiddenResult

    suspend fun sendInput(
        sessionId: String,
        hasInput: Boolean,
        input: String,
        control: String? = null,
    ): Int

    suspend fun getSessionScreen(sessionId: String): HostTerminalScreenSnapshot

    suspend fun executeTermuxCommand(
        command: String,
        workingDirectory: String?,
        yieldTimeMs: Long,
        sessionName: String?,
    ): HostTermuxExecResult

    suspend fun writeTermuxStdin(
        sessionId: String,
        chars: String,
        control: String?,
        yieldTimeMs: Long,
        afterCursor: Long?,
    ): HostTermuxExecResult

    suspend fun listTermuxSessions(includeCompleted: Boolean): List<HostTermuxExecSession>

    fun isConnected(): Boolean
}

data class HostTerminalScreenSnapshot(
    val sessionId: String,
    val rows: Int,
    val cols: Int,
    val content: String,
)
