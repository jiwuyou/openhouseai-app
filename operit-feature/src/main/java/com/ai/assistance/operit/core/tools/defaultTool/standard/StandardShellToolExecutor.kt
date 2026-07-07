package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.ADBResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import com.ai.assistance.operit.host.terminal.HostTerminalPolicy
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import kotlinx.coroutines.runBlocking

/**
 * Tool for Android/Termux-oriented shell commands.
 *
 * This path is intentionally pinned to TERMUX instead of DEFAULT because commands such as pm,
 * settings, input and getprop target the Android-side shell surface, not the Ubuntu plugin runtime.
 */
open class StandardShellToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "StandardShellToolExecutor"
        private const val DEFAULT_TIMEOUT = 15000L // 15 seconds
    }

    fun invoke(tool: AITool): ToolResult {
        // Validate parameters
        val validationResult = validateParameters(tool)
        if (!validationResult.valid) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = validationResult.errorMessage
            )
        }

        val command = tool.parameters.find { it.name == "command" }?.value ?: ""
        val target = HostTerminalTarget.TERMUX

        HostTerminalPolicy.rejectionReason(command)?.let { reason ->
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = reason
            )
        }

        return try {
            val result =
                    runBlocking {
                        Terminal.getInstance(context).executeHiddenCommand(
                                command = command,
                                executorKey = "execute-shell-termux",
                                timeoutMs = DEFAULT_TIMEOUT,
                                target = target
                        )
                    }

            if (result.isOk) {
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                ADBResultData(
                                        command = command,
                                        output = result.output,
                                        exitCode = result.exitCode
                                )
                )
            } else {
                // Combine stdout and stderr for error reporting
                val errorOutput =
                        result.error.ifBlank { result.rawOutputPreview }.trim()

                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error =
                                "Termux host shell command failed (exit code: ${result.exitCode}): $errorOutput"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing Termux host shell command", e)
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Termux host shell command failed: ${e.message}"
            )
        }
    }

    /** Validates the parameters for the Android/Termux shell tool. */
    fun validateParameters(tool: AITool): ToolValidationResult {
        val command = tool.parameters.find { it.name == "command" }?.value

        return when {
            command.isNullOrBlank() -> {
                ToolValidationResult(valid = false, errorMessage = "Command parameter is required")
            }
            command.contains("rm -rf") || command.contains("format") -> {
                ToolValidationResult(
                        valid = false,
                        errorMessage = "Potentially dangerous command detected"
                )
            }
            else -> {
                ToolValidationResult(valid = true)
            }
        }
    }
}
