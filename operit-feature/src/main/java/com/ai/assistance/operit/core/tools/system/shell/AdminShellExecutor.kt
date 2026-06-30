package com.ai.assistance.operit.core.tools.system.shell

import android.content.ComponentName
import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShellIdentity

/** 基于设备管理员的Shell命令执行器 实现ADMIN权限级别的命令执行 */
class AdminShellExecutor(_context: Context) : ShellExecutor {
    companion object {
        private const val UNSUPPORTED_REASON =
                "Device-admin shell execution is unsupported in hosted Operit. Use SmallPhoneAI Termux host for short commands or service-manager for long-running tasks."
        private var adminComponentName: ComponentName? = null

        /**
         * 设置设备管理员组件名称
         * @param componentName 设备管理员组件名称
         */
        fun setAdminComponentName(componentName: ComponentName) {
            adminComponentName = componentName
        }
    }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.ADMIN

    override fun isAvailable(): Boolean = false

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        return ShellExecutor.PermissionStatus.denied(UNSUPPORTED_REASON)
    }

    override fun initialize() {
        // 设备管理员初始化由系统控制，此处无需额外操作
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult = hostedShellUnsupported(UNSUPPORTED_REASON)

    override suspend fun startProcess(command: String): ShellProcess {
        return UnsupportedShellProcess(UNSUPPORTED_REASON)
    }
}
