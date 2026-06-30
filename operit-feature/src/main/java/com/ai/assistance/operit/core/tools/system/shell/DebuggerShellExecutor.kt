package com.ai.assistance.operit.core.tools.system.shell

import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.core.tools.system.ShellIdentity

/**
 * DEBUGGER shell execution is intentionally disabled in hosted Operit.
 *
 * The original implementation used Shizuku to spawn Android shell processes directly. For the
 * SmallPhoneAI merge, executable shell commands must use the Termux host adapter; privileged Android
 * automation should be implemented as separate host-gated operations instead of hidden shell paths.
 */
class DebuggerShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val UNSUPPORTED_REASON =
                "Debugger shell execution is unsupported in hosted Operit. Use SmallPhoneAI Termux host for short commands or service-manager for long-running tasks."

        fun addStateChangeListener(listener: () -> Unit) {
            ShizukuAuthorizer.addStateChangeListener(listener)
        }

        fun removeStateChangeListener(listener: () -> Unit) {
            ShizukuAuthorizer.removeStateChangeListener(listener)
        }

        fun getShizukuStartupInstructions(context: Context): String {
            return ShizukuAuthorizer.getShizukuStartupInstructions(context)
        }
    }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.DEBUGGER

    override fun isAvailable(): Boolean = false

    override fun hasPermission(): ShellExecutor.PermissionStatus =
            ShellExecutor.PermissionStatus.denied(UNSUPPORTED_REASON)

    override fun initialize() {
        // Direct Shizuku shell runtime is intentionally not initialized in hosted mode.
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    fun isShizukuInstalled(): Boolean {
        return ShizukuAuthorizer.isShizukuInstalled(context)
    }

    override suspend fun executeCommand(
            command: String,
            identity: ShellIdentity
    ): ShellExecutor.CommandResult = hostedShellUnsupported(UNSUPPORTED_REASON)

    override suspend fun startProcess(command: String): ShellProcess =
            UnsupportedShellProcess(UNSUPPORTED_REASON)
}
