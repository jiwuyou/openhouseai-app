package com.ai.assistance.operit.host.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * Host-owned persistent terminal implementation used by the shared Operit terminal tools.
 *
 * This contract is Ubuntu-only. Native hosts back it with RUN_COMMAND -> Termux tmux -> Ubuntu;
 * the embedded Termux host executes the same protocol directly. It must never fall back to either
 * Android shell or a plain Termux shell.
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

    fun isConnected(): Boolean
}

data class HostTerminalScreenSnapshot(
    val sessionId: String,
    val rows: Int,
    val cols: Int,
    val content: String,
)
