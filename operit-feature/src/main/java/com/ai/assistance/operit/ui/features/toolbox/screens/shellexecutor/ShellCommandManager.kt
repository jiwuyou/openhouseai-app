package com.ai.assistance.operit.ui.features.toolbox.screens.shellexecutor

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Shell命令管理器 - 负责命令执行、历史记录管理等
 */
class ShellCommandManager(private val context: Context) {
    
    private val PREFS_NAME = "shell_executor_prefs"
    private val KEY_COMMAND_HISTORY = "command_history"
    private val MAX_HISTORY_SIZE = 100
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 获取预设命令列表
     */
    fun getPresetCommands(): List<PresetCommand> {
        return listOf(
            PresetCommand(
                name = context.getString(R.string.shell_cmd_test),
                command = context.getString(R.string.shell_cmd_test_command),
                description = context.getString(R.string.shell_cmd_test_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Check
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_sys_info),
                command = context.getString(R.string.shell_cmd_sys_info_cmd),
                description = context.getString(R.string.shell_cmd_sys_info_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Info
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_disk),
                command = context.getString(R.string.shell_cmd_disk_cmd),
                description = context.getString(R.string.shell_cmd_disk_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Storage
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_memory),
                command = context.getString(R.string.shell_cmd_memory_cmd),
                description = context.getString(R.string.shell_cmd_memory_desc),
                category = CommandCategory.HARDWARE,
                icon = Icons.Default.Memory
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_cpu),
                command = context.getString(R.string.shell_cmd_cpu_cmd),
                description = context.getString(R.string.shell_cmd_cpu_desc),
                category = CommandCategory.HARDWARE,
                icon = Icons.Default.SettingsApplications
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_network),
                command = context.getString(R.string.shell_cmd_network_cmd),
                description = context.getString(R.string.shell_cmd_network_desc),
                category = CommandCategory.NETWORK,
                icon = Icons.Default.Wifi
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_route),
                command = context.getString(R.string.shell_cmd_route_cmd),
                description = context.getString(R.string.shell_cmd_route_desc),
                category = CommandCategory.NETWORK,
                icon = Icons.Default.Router
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_connection),
                command = context.getString(R.string.shell_cmd_connection_cmd),
                description = context.getString(R.string.shell_cmd_connection_desc),
                category = CommandCategory.NETWORK,
                icon = Icons.Default.NetworkCheck
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_installed_apps),
                command = context.getString(R.string.shell_cmd_installed_apps_cmd),
                description = context.getString(R.string.shell_cmd_installed_apps_desc),
                category = CommandCategory.PACKAGE,
                icon = Icons.Default.Apps
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_system_apps),
                command = context.getString(R.string.shell_cmd_system_apps_cmd),
                description = context.getString(R.string.shell_cmd_system_apps_desc),
                category = CommandCategory.PACKAGE,
                icon = Icons.Default.Android
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_3rd_apps),
                command = context.getString(R.string.shell_cmd_3rd_apps_cmd),
                description = context.getString(R.string.shell_cmd_3rd_apps_desc),
                category = CommandCategory.PACKAGE,
                icon = Icons.Default.AppShortcut
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_current_dir),
                command = context.getString(R.string.shell_cmd_current_dir_cmd),
                description = context.getString(R.string.shell_cmd_current_dir_desc),
                category = CommandCategory.FILE,
                icon = Icons.Default.Folder
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_root_dir),
                command = context.getString(R.string.shell_cmd_root_dir_cmd),
                description = context.getString(R.string.shell_cmd_root_dir_desc),
                category = CommandCategory.FILE,
                icon = Icons.Default.FolderOpen
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_storage),
                command = context.getString(R.string.shell_cmd_storage_cmd),
                description = context.getString(R.string.shell_cmd_storage_desc),
                category = CommandCategory.FILE,
                icon = Icons.Default.SdCard
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_process),
                command = context.getString(R.string.shell_cmd_process_cmd),
                description = context.getString(R.string.shell_cmd_process_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.ViewList
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_properties),
                command = context.getString(R.string.shell_cmd_properties_cmd),
                description = context.getString(R.string.shell_cmd_properties_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Settings
            )
        )
    }
    
    /**
     * 添加自定义预设命令
     */
    suspend fun addCustomPresetCommand(command: PresetCommand) {
        // 实现保存自定义预设命令的逻辑
    }
    
    /**
     * 获取命令历史记录
     */
    fun getCommandHistory(): List<CommandRecord> {
        val historyJson = prefs.getString(KEY_COMMAND_HISTORY, null) ?: return emptyList()
        return try {
            // 在实际实现中，使用JSON解析库如Gson或Moshi来解析历史记录
            // 此处为简化，返回空列表
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 执行Shell命令
     */
    suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT
    ): CommandRecord {
        val result =
            withContext(Dispatchers.IO) {
                executeWithHostTerminal(command, target)
            }
        
        val record = CommandRecord(
            command = command,
            result = result,
            target = target,
            timestamp = System.currentTimeMillis()
        )
        
        // 保存到历史记录
        saveCommandToHistory(record)
        
        return record
    }
    
    /**
     * 保存命令到历史记录
     */
    private fun saveCommandToHistory(record: CommandRecord) {
        val history = getCommandHistory().toMutableList()
        
        // 如果已存在相同命令，移除旧记录
        history.removeAll { it.command == record.command }
        
        // 添加新记录到列表头部
        history.add(0, record)
        
        // 限制历史记录大小
        val trimmedHistory = history.take(MAX_HISTORY_SIZE)
        
        // 保存到SharedPreferences
        // 在实际实现中，使用JSON序列化库将历史记录转换为JSON字符串
        // prefs.edit().putString(KEY_COMMAND_HISTORY, jsonString).apply()
    }
    
    /**
     * 清除命令历史
     */
    fun clearCommandHistory() {
        prefs.edit().remove(KEY_COMMAND_HISTORY).apply()
    }
    
    /**
     * 从历史记录中移除指定命令
     */
    fun removeCommandFromHistory(command: String) {
        val history = getCommandHistory().toMutableList()
        history.removeAll { it.command == command }
        
        // 保存到SharedPreferences
        // 在实际实现中，使用JSON序列化库将历史记录转换为JSON字符串
        // prefs.edit().putString(KEY_COMMAND_HISTORY, jsonString).apply()
    }
    
    /**
     * 获取建议的命令列表（基于历史记录和输入的前缀）
     */
    fun getSuggestedCommands(prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        
        val history = getCommandHistory()
        return history
            .map { it.command }
            .distinct()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(5)
    }

    private suspend fun executeWithHostTerminal(
        command: String,
        target: HostTerminalTarget
    ): ShellCommandResult {
        val blockedReason = terminalCommandBlockReason(command)
        if (blockedReason != null) {
            return ShellCommandResult(
                success = false,
                stdout = "",
                stderr = blockedReason,
                exitCode = -1,
                target = target
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ShellCommandResult(
                success = false,
                stdout = "",
                stderr = "Android 8.0+ required for hosted terminal adapter",
                exitCode = -1,
                target = target
            )
        }

        return try {
            val terminal = Terminal.getInstance(context)
            val result =
                terminal.executeHiddenCommand(
                    command = command,
                    executorKey = "shell-executor",
                    target = target
                )
            ShellCommandResult(
                success = result.isOk,
                stdout = result.output,
                stderr =
                    if (result.isOk) {
                        ""
                    } else {
                        result.error.ifBlank { result.rawOutputPreview }
                    },
                exitCode = result.exitCode,
                target = target
            )
        } catch (e: Exception) {
            ShellCommandResult(
                success = false,
                stdout = "",
                stderr = e.message ?: "${target.displayName} host terminal execution failed",
                exitCode = -1,
                target = target
            )
        }
    }

    private fun terminalCommandBlockReason(command: String): String? {
        val normalized = command.trim().lowercase(Locale.ROOT)
        val backgroundPatterns =
            listOf(
                Regex("""(^|[\s;])nohup([\s;]|$)"""),
                Regex("""(^|[\s;])setsid([\s;]|$)"""),
                Regex("""(^|[\s;])disown([\s;]|$)"""),
                Regex("""(^|[\s;])daemon(ize)?([\s;]|$)"""),
                Regex("""(^|[\s;])supervisord?([\s;]|$)"""),
                Regex("""(^|[\s;])pm2\s+(start|restart|resurrect)\b"""),
                Regex("""(^|[\s;])systemctl\s+(start|restart|enable|daemon-reload)\b"""),
                Regex("""(^|[\s;])service\s+\S+\s+(start|restart|enable)\b"""),
                Regex("""(^|[\s;])service-manager\b""")
            )

        if (normalized.endsWith("&") || backgroundPatterns.any { it.containsMatchIn(normalized) }) {
            return "Long-running/background commands must be managed by SmallPhoneAI service-manager."
        }

        return null
    }
} 
