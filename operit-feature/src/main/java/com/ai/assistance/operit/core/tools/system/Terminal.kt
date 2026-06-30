package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.host.terminal.HostTerminalCommandEvent
import com.ai.assistance.operit.host.terminal.HostTerminalHiddenResult
import com.ai.assistance.operit.host.terminal.HostTerminalState
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.TermuxHostTerminalAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Operit terminal facade backed by the SmallPhoneAI/Termux host.
 *
 * This keeps Operit tools compatible with their original terminal-facing API
 * without importing Operit's original standalone terminal runtime or bundled Linux image.
 */
class Terminal private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: Terminal? = null

        fun getInstance(context: Context): Terminal {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Terminal(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val adapter = TermuxHostTerminalAdapter()

    val terminalState: StateFlow<HostTerminalState> = adapter.terminalState

    suspend fun initialize(): Boolean = adapter.initialize()

    fun destroy() {
        adapter.destroy()
    }

    suspend fun createSession(
        title: String? = null,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT
    ): String = adapter.createSession(title, target)

    fun switchToSession(sessionId: String) {
        // Host-backed sessions are logical only; command calls address a session explicitly.
    }

    fun closeSession(sessionId: String) {
        adapter.closeSession(sessionId)
    }

    suspend fun executeCommand(sessionId: String, command: String): String? {
        return adapter.executeCommand(sessionId, command)
    }

    suspend fun executeCommandResult(
        sessionId: String,
        command: String,
        timeoutMs: Long = 120000L
    ): HostTerminalHiddenResult {
        return adapter.executeCommandResult(sessionId, command, timeoutMs)
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT
    ): HostTerminalHiddenResult {
        return adapter.executeHiddenCommand(command, executorKey, timeoutMs, target)
    }

    fun executeCommandFlow(sessionId: String, command: String): Flow<HostTerminalCommandEvent> {
        return adapter.executeCommandFlow(sessionId, command)
    }

    fun sendInput(sessionId: String, input: String) {
        adapter.sendInput(sessionId, input)
    }

    fun sendInterruptSignal(sessionId: String) {
        adapter.sendInterruptSignal(sessionId)
    }

    fun isConnected(): Boolean = adapter.isConnected()
}
