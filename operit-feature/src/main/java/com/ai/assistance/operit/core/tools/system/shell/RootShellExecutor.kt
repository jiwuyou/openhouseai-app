package com.ai.assistance.operit.core.tools.system.shell

import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShellIdentity

/**
 * Hosted Operit does not run root shell commands directly.
 *
 * Root execution from the original Operit app depended on local su/libsu paths and a bundled shell
 * launcher. In SmallPhoneAI-hosted mode, command execution must go through the Termux host adapter
 * or SmallPhoneAI service-manager, so root semantics are reported as unsupported instead of falling
 * back to a private runtime.
 */
class RootShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val UNSUPPORTED_REASON =
                "Root shell execution is unsupported in hosted Operit. Use SmallPhoneAI Termux host for short commands or service-manager for long-running tasks."
    }

    fun setUseExecMode(useExec: Boolean) {
        // Retained for compatibility with RootAuthorizer preferences.
    }

    fun setExecSuCommand(command: String?) {
        // Retained for compatibility with RootAuthorizer preferences.
    }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.ROOT

    override fun isAvailable(): Boolean = false

    override fun hasPermission(): ShellExecutor.PermissionStatus =
            ShellExecutor.PermissionStatus.denied(UNSUPPORTED_REASON)

    override fun initialize() {
        // Root shell runtime is intentionally not initialized in hosted mode.
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    override suspend fun executeCommand(
            command: String,
            identity: ShellIdentity
    ): ShellExecutor.CommandResult = hostedShellUnsupported(UNSUPPORTED_REASON)

    override suspend fun startProcess(command: String): ShellProcess =
            UnsupportedShellProcess(UNSUPPORTED_REASON)
}
