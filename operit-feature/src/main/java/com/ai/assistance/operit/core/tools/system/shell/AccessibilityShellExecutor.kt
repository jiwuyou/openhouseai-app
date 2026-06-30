package com.ai.assistance.operit.core.tools.system.shell

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShellIdentity

/**
 * 基于无障碍服务的Shell命令执行器 实现ACCESSIBILITY权限级别的命令执行
 *
 * 注意：无障碍服务不是真正的shell执行方式，但可用于模拟某些操作
 */
class AccessibilityShellExecutor(_context: Context) : ShellExecutor {
    companion object {
        private const val UNSUPPORTED_REASON =
                "Accessibility shell execution is unsupported in hosted Operit. Use SmallPhoneAI Termux host for short commands or service-manager for long-running tasks."
        private var accessibilityService: AccessibilityService? = null

        /**
         * 设置全局无障碍服务引用
         * @param service 无障碍服务实例
         */
        fun setAccessibilityService(service: AccessibilityService?) {
            accessibilityService = service
        }
    }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.ACCESSIBILITY

    override fun isAvailable(): Boolean = false

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        return ShellExecutor.PermissionStatus.denied(UNSUPPORTED_REASON)
    }

    override fun initialize() {
        // 无障碍服务初始化由系统控制，此处无需额外操作
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    override suspend fun startProcess(command: String): ShellProcess {
        return UnsupportedShellProcess(UNSUPPORTED_REASON)
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult = hostedShellUnsupported(UNSUPPORTED_REASON)
}
