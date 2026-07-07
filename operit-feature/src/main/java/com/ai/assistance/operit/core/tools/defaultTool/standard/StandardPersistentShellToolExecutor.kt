package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.tools.PersistentShellCommandResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.executePersistentShellCommand
import com.ai.assistance.operit.host.terminal.HostTerminalPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Executes explicit last-resort long-timeout shell commands through the host. */
class StandardPersistentShellToolExecutor {

    fun execute(tool: AITool): ToolResult {
        val command = tool.parameters.find { it.name == "command" }?.value.orEmpty()
        if (command.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Missing required parameter: command"
            )
        }
        HostTerminalPolicy.rejectionReason(command)?.let { reason ->
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error =
                    "$reason Register long-running services with SmallPhoneAI service-manager instead of using persistent shell execution."
            )
        }

        val timeoutMs =
            tool.parameters
                .find { it.name == "timeout_ms" }
                ?.value
                ?.toLongOrNull()
                ?: DEFAULT_TIMEOUT_MS

        return runBlocking(Dispatchers.IO) {
            try {
                val host = OperitHostProvider.requireHost()
                val result = host.executePersistentShellCommand(command, timeoutMs)
                ToolResult(
                    toolName = tool.name,
                    success = result.isSuccess,
                    result =
                        PersistentShellCommandResultData(
                            command = result.command,
                            stdout = result.stdout,
                            stderr = result.stderr,
                            error = result.error,
                            exitCode = result.exitCode,
                            timeoutMs = timeoutMs,
                            timedOut = result.timedOut,
                            durationMs = result.durationMs
                        ),
                    error = result.error.ifBlank { null }
                )
            } catch (e: Exception) {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = e.message ?: e::class.java.simpleName
                )
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 600_000L
    }
}
