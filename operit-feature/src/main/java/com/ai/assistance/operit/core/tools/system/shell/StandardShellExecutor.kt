package com.ai.assistance.operit.core.tools.system.shell

import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShellIdentity
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.HostTerminalPolicy
import com.ai.assistance.operit.host.terminal.HostTerminalAdapter
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** STANDARD shell executor backed by SmallPhoneAI/Termux host short-command execution. */
class StandardShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val TAG = "StandardShellExecutor"
        private const val COMMAND_TIMEOUT_MS = 30_000L
    }

    private val terminalAdapter = HostTerminalAdapter()

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.STANDARD

    override fun isAvailable(): Boolean = OperitHostProvider.currentOrNull() != null

    override fun hasPermission(): ShellExecutor.PermissionStatus =
        if (isAvailable()) {
            ShellExecutor.PermissionStatus.granted()
        } else {
            ShellExecutor.PermissionStatus.denied("SmallPhoneAI host terminal is not installed.")
        }

    override fun initialize() {
        // Host contract is installed by SmallPhoneAI.
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(isAvailable())
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult =
        withContext(Dispatchers.IO) {
            HostTerminalPolicy.rejectionReason(command)?.let { reason ->
                return@withContext ShellExecutor.CommandResult(false, "", reason, -2)
            }

            try {
                val result =
                    terminalAdapter.executeHiddenCommand(
                        command = command,
                        executorKey = "standard-shell",
                        timeoutMs = COMMAND_TIMEOUT_MS,
                        target = HostTerminalTarget.HOST
                    )
                ShellExecutor.CommandResult(
                    success = result.isOk,
                    stdout = result.output,
                    stderr = result.error,
                    exitCode = result.exitCode
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error executing host terminal command", e)
                ShellExecutor.CommandResult(false, "", "Error: ${e.message}", -1)
            }
        }

    override suspend fun startProcess(command: String): ShellProcess {
        HostTerminalPolicy.rejectionReason(command)?.let { reason ->
            return RejectedShellProcess(reason)
        }
        return RejectedShellProcess(
            "Persistent shell processes must be managed by SmallPhoneAI service-manager."
        )
    }
}

private class RejectedShellProcess(private val reason: String) : ShellProcess {
    override val stdout: Flow<String> = flowOf()
    override val stderr: Flow<String> = flowOf(reason)
    override val isAlive: Boolean = false

    override fun destroy() {
        // No process was started.
    }

    override suspend fun waitFor(): Int = -2
}
