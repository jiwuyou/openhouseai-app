package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AndroidCommandResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.terminal.HostTerminalPolicy
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import kotlinx.coroutines.runBlocking

/**
 * Tool for host-oriented shell commands.
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
                        OperitHostProvider.operationsOrUnsupported().executeCommand(
                                command = command,
                                target = HostTerminalTarget.ANDROID,
                                timeoutMs = DEFAULT_TIMEOUT
                        )
                    }

            if (result.isSuccess) {
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                AndroidCommandResultData(
                                        command = command,
                                        stdout = result.stdout,
                                        stderr = result.stderr,
                                        exitCode = result.exitCode,
                                        timedOut = result.timedOut,
                                        durationMs = result.durationMs,
                                )
                )
            } else {
                // Combine stdout and stderr for error reporting
                val errorOutput =
                        result.error.ifBlank { result.stderr.ifBlank { result.stdout } }.trim()

                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error =
                                "Android shell command failed (exit code: ${result.exitCode}): $errorOutput"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing Android shell command", e)
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Android shell command failed: ${e.message}"
            )
        }
    }

    /** Validates the parameters for the host shell tool. */
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
