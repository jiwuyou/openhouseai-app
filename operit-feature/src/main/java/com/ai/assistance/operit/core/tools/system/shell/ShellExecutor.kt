package com.ai.assistance.operit.core.tools.system.shell

import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShellIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal const val HOSTED_SHELL_UNSUPPORTED_EXIT_CODE = -2

internal fun hostedShellUnsupported(reason: String): ShellExecutor.CommandResult =
        ShellExecutor.CommandResult(false, "", reason, HOSTED_SHELL_UNSUPPORTED_EXIT_CODE)

/** 通用Shell命令执行接口 定义了不同权限级别Shell操作的统一接口 */
interface ShellExecutor {
    /**
     * 执行Shell命令
     * @param command 要执行的Shell命令
     * @return 命令执行结果
     */
    suspend fun executeCommand(command: String, identity: ShellIdentity = ShellIdentity.DEFAULT): CommandResult

    /**
     * 获取当前执行器的权限级别
     * @return 当前执行器的权限级别
     */
    fun getPermissionLevel(): AndroidPermissionLevel

    /**
     * 检查执行器是否可用
     * @return 执行器是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 请求执行器所需的权限
     * @param onResult 权限请求结果回调
     */
    fun requestPermission(onResult: (Boolean) -> Unit)

    /**
     * 检查是否已有执行器所需的权限
     * @return 权限状态，包含是否有权限及详细的错误原因
     */
    fun hasPermission(): PermissionStatus

    /** 初始化执行器 */
    fun initialize()

    /**
     * 执行一个长期运行的命令，并提供一个交互式进程对象。
     * 主要用于需要持续读取输出流的命令，例如 'logcat' 或 'getevent'。
     *
     * @param command 要执行的命令。
     * @return 一个 ShellProcess 对象，允许管理进程并读取其输出流。
     * @throws UnsupportedOperationException 如果执行器不支持启动持久进程。
     */
    suspend fun startProcess(command: String): ShellProcess

    /** 命令执行结果数据类 */
    data class CommandResult(
            val success: Boolean,
            val stdout: String,
            val stderr: String = "",
            val exitCode: Int = -1
    )

    /** 权限状态数据类 包含权限检查结果和失败原因描述 */
    data class PermissionStatus(
            val granted: Boolean,
            val reason: String = if (granted) "Permission granted" else "Permission denied"
    ) {
        companion object {
            fun granted() = PermissionStatus(true)
            fun denied(reason: String) = PermissionStatus(false, reason)
        }
    }
}

/**
 * 代表一个正在运行的、可交互的Shell进程。
 */
interface ShellProcess {
    /** 从标准输出流读取的文本行 Flow。 */
    val stdout: Flow<String>

    /** 从标准错误流读取的文本行 Flow。 */
    val stderr: Flow<String>

    /**
     * 强行终止进程。
     */
    fun destroy()

    /**
     * 挂起直到进程执行完毕，并返回其退出码。
     */
    suspend fun waitFor(): Int

    /**
     * 检查进程是否仍在运行。
     * @return 如果进程是活跃的，则返回true。
     */
    val isAlive: Boolean
}

internal class UnsupportedShellProcess(private val reason: String) : ShellProcess {
    override val stdout: Flow<String> = flowOf()
    override val stderr: Flow<String> = flowOf(reason)
    override val isAlive: Boolean = false

    override fun destroy() {
        // No process was started.
    }

    override suspend fun waitFor(): Int = HOSTED_SHELL_UNSUPPORTED_EXIT_CODE
}
