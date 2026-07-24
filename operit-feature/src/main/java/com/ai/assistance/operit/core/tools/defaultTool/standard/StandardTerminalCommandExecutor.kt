package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.host.terminal.HostTerminalChar
import com.ai.assistance.operit.host.terminal.HostTerminalHiddenResult
import com.ai.assistance.operit.host.terminal.HostTerminalPolicy
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.HostTermuxExecResult
import com.ai.assistance.operit.host.terminal.HostTermuxExecSession
import com.ai.assistance.operit.host.terminal.HostTermuxExecState
import com.ai.assistance.operit.host.OperitHostProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/** 终端命令执行工具 - 非流式输出版本 执行终端命令并一次性收集全部输出后返回 */
class StandardTerminalCommandExecutor(private val context: Context) {

    private val TAG = "TerminalCommandExecutor"

    companion object {
        // 用于将会话名称映射到会话ID
        private val sessionNameToIdMap = ConcurrentHashMap<String, String>()
        private const val COMMAND_CANCEL_SETTLE_TIMEOUT_MS = 3_000L
        private const val COMMAND_BACKEND_SETTLE_TIMEOUT_MS = 30_000L
        private const val DEFAULT_TERMUX_TIMEOUT_MS = 120_000L
        private const val MAX_TERMUX_TIMEOUT_MS = 86_400_000L
        private const val DEFAULT_TERMUX_YIELD_MS = 10_000L
        private const val DEFAULT_TERMUX_STDIN_YIELD_MS = 5_000L
        private const val MAX_TERMUX_YIELD_MS = 300_000L
        private const val DEFAULT_TERMUX_PACKAGE = "com.termux"

        internal fun buildManagedTermuxToolResult(
            toolName: String,
            command: String?,
            result: HostTermuxExecResult
        ): ToolResult {
            val setupRequired = result.state == HostTermuxExecState.SETUP_REQUIRED
            val setupCommand =
                if (setupRequired) {
                    result.setupCommand?.takeIf { it.isNotBlank() } ?: "pkg install -y tmux"
                } else {
                    null
                }
            val missingDependencies =
                if (setupRequired) result.missingDependencies.ifEmpty { listOf("tmux") }
                else emptyList()
            val successful =
                when (result.state) {
                    HostTermuxExecState.RUNNING -> true
                    HostTermuxExecState.COMPLETED -> result.exitCode == 0
                    else -> false
                }
            val failure =
                when {
                    successful -> null
                    setupRequired ->
                        buildString {
                            append("setup_required: managed Termux execution cannot start. ")
                            append("missingDependencies=")
                            append(missingDependencies.joinToString(","))
                            append("; setupCommand=")
                            append(setupCommand)
                            append(". Run setupCommand with execute_termux_command, verify tmux, then retry termux_exec_command.")
                            result.error.takeIf { it.isNotBlank() }?.let {
                                append(" Details: ")
                                append(it)
                            }
                        }
                    result.error.isNotBlank() -> result.error
                    result.state == HostTermuxExecState.COMPLETED ->
                        "Termux command exited with code ${result.exitCode ?: -1}"
                    result.state == HostTermuxExecState.LOST -> "Managed Termux session was lost"
                    result.state == HostTermuxExecState.CLOSED -> "Managed Termux session is closed"
                    else -> "Managed Termux execution failed"
                }
            return ToolResult(
                toolName = toolName,
                success = successful,
                result =
                    TermuxExecResultData(
                        command = command,
                        sessionId = result.sessionId,
                        sessionName = result.sessionName,
                        target = result.target.wireName,
                        state = result.state.name.lowercase(),
                        output = result.output,
                        cursor = result.cursor.toString(),
                        exitCode = result.exitCode,
                        persistent = result.persistent,
                        setupRequired = setupRequired,
                        setupCommand = setupCommand,
                        missingDependencies = missingDependencies,
                        error = result.error.takeIf { it.isNotBlank() }
                    ),
                error = failure
            )
        }
    }

    /** Execute a one-shot command in the active host's Termux user space. */
    fun executeTermuxCommand(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        try {
            val command = tool.parameters.find { it.name == "command" }?.value.orEmpty()
            if (command.isBlank()) {
                return@runBlocking ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_missing_command)
                )
            }

            val packageName =
                tool.parameters.find { it.name == "package_name" }?.value?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_TERMUX_PACKAGE
            if (packageName != DEFAULT_TERMUX_PACKAGE) {
                return@runBlocking ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "The active host adapter supports only $DEFAULT_TERMUX_PACKAGE"
                )
            }

            val workingDirectory =
                tool.parameters.find { it.name == "working_directory" }?.value?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val timeoutMs =
                tool.parameters.find { it.name == "timeout_ms" }?.value?.toLongOrNull()
                    ?.takeIf { it in 1_000L..MAX_TERMUX_TIMEOUT_MS }
                    ?: DEFAULT_TERMUX_TIMEOUT_MS
            val commandToRun =
                workingDirectory?.let { "cd ${HostTerminalPolicy.shellQuote(it)} && $command" }
                    ?: command
            val result =
                OperitHostProvider.operationsOrUnsupported().executeCommand(
                    command = commandToRun,
                    target = HostTerminalTarget.TERMUX,
                    timeoutMs = timeoutMs
                )
            val failure =
                listOf(result.transportErrorMessage, result.error, result.stderr)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")

            ToolResult(
                toolName = tool.name,
                success = result.isSuccess,
                result =
                    TermuxCommandResultData(
                        command = command,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        exitCode = result.exitCode,
                        packageName = packageName,
                        errCode = result.transportErrorCode,
                        errmsg = result.transportErrorMessage.ifBlank { result.error },
                        timedOut = result.timedOut,
                        durationMs = result.durationMs
                    ),
                error = failure.takeIf { !result.isSuccess }
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to execute Termux command", error)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = context.getString(R.string.termux_error_execute_command, error.message.orEmpty())
            )
        }
    }

    /** Execute a command in the reconnectable tmux-backed Termux PTY. */
    fun executeManagedTermuxCommand(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        try {
            val command = tool.parameters.find { it.name == "command" }?.value.orEmpty()
            if (command.isBlank()) {
                return@runBlocking ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_missing_command)
                )
            }

            val workingDirectory =
                tool.parameters.find { it.name == "working_directory" }?.value?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val sessionName =
                tool.parameters.find { it.name == "session_name" }?.value?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val yieldTimeMs =
                parseYieldTimeMs(tool, DEFAULT_TERMUX_YIELD_MS)

            val result =
                Terminal.getInstance(context).executeTermuxCommand(
                    command = command,
                    workingDirectory = workingDirectory,
                    yieldTimeMs = yieldTimeMs,
                    sessionName = sessionName
                )
            buildManagedTermuxToolResult(tool.name, command, result)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to execute managed Termux command", error)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = context.getString(R.string.termux_error_execute_command, error.message.orEmpty())
            )
        }
    }

    /** Poll or write to a reconnectable managed Termux PTY. */
    fun writeManagedTermuxStdin(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        val sessionId = tool.parameters.find { it.name == "session_id" }?.value?.trim()
        try {
            if (sessionId.isNullOrEmpty()) {
                return@runBlocking ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_missing_session_id)
                )
            }

            val chars = tool.parameters.find { it.name == "chars" }?.value.orEmpty()
            val control = normalizeControl(tool.parameters.find { it.name == "control" }?.value)
            val afterCursorRaw =
                tool.parameters.find { it.name == "after_cursor" }?.value?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val afterCursor = afterCursorRaw?.toLongOrNull()
            if (afterCursorRaw != null && afterCursor == null) {
                return@runBlocking ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "after_cursor must be a non-negative integer"
                )
            }
            if (afterCursor != null && afterCursor < 0L) {
                return@runBlocking ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "after_cursor must be a non-negative integer"
                )
            }

            val result =
                Terminal.getInstance(context).writeTermuxStdin(
                    sessionId = sessionId,
                    chars = chars,
                    control = control,
                    yieldTimeMs = parseYieldTimeMs(tool, DEFAULT_TERMUX_STDIN_YIELD_MS),
                    afterCursor = afterCursor
                )
            buildManagedTermuxToolResult(tool.name, command = null, result = result)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to access managed Termux session $sessionId", error)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = context.getString(R.string.termux_error_execute_command, error.message.orEmpty())
            )
        }
    }

    /** List managed Termux sessions from the host's persistent tmux state. */
    fun listManagedTermuxSessions(tool: AITool): ToolResult = runBlocking(Dispatchers.IO) {
        try {
            val includeCompleted =
                tool.parameters.find { it.name == "include_completed" }?.value
                    ?.trim()
                    ?.equals("true", ignoreCase = true)
                    ?: false
            val sessions = Terminal.getInstance(context).listTermuxSessions(includeCompleted)
            ToolResult(
                toolName = tool.name,
                success = true,
                result =
                    TermuxExecSessionListResultData(
                        sessions = sessions.map(::toTermuxSessionResultData)
                    )
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to list managed Termux sessions", error)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = context.getString(R.string.termux_error_execute_command, error.message.orEmpty())
            )
        }
    }


    /** 创建或获取一个终端会话 */
    fun createOrGetSession(tool: AITool): ToolResult {
        return runBlocking {
            try {
                val sessionName = tool.parameters.find { it.name == "session_name" }?.value
                if (sessionName.isNullOrBlank()) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_session_name)
                    )
                }

                val target = HostTerminalTarget.UBUNTU
                val terminal = Terminal.getInstance(context)
                val sessionKey = sessionMapKey(target, sessionName)

                // 修正：直接检查 Terminal 单例中是否已存在同名会话，而不是依赖本地缓存
                val existingSession =
                    terminal.terminalState.value.sessions.find {
                        it.title == sessionName && it.target == target
                    }
                if (existingSession != null) {
                    // 如果存在，更新本地缓存并返回该会话
                    sessionNameToIdMap[sessionKey] = existingSession.id
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = TerminalSessionCreationResultData(
                            sessionId = existingSession.id,
                            sessionName = sessionName,
                            isNewSession = false
                        )
                    )
                }

                // 如果 Terminal 中不存在，则创建新会话
                val newSessionId = terminal.createSession(sessionName, target)
                sessionNameToIdMap[sessionKey] = newSessionId

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = TerminalSessionCreationResultData(
                        sessionId = newSessionId,
                        sessionName = sessionName,
                        isNewSession = true
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "创建或获取终端会话时出错", e)
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_create_session, e.message ?: "")
                )
            }
        }
    }

    /** 在指定的终端会话中执行命令 */
    fun executeCommandInSession(tool: AITool): ToolResult {
        return runBlocking {
            try {
                val command = tool.parameters.find { param -> param.name == "command" }?.value ?: ""
                val sessionId = tool.parameters.find { param -> param.name == "session_id" }?.value

                if (sessionId.isNullOrBlank()) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_session_id)
                    )
                }

                val timeout =
                        tool.parameters
                                .find { param -> param.name == "timeout_ms" }
                                ?.value
                                ?.toLongOrNull()
                                ?: 1800000L // 30 分钟

                val terminal = Terminal.getInstance(context)

                // 检查会话是否存在
                if (terminal.terminalState.value.sessions.none { it.id == sessionId }) {
                    // 如果会话不存在，也从我们的映射中移除
                    sessionNameToIdMap.entries.removeIf { it.value == sessionId }
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_session_not_exist, sessionId)
                    )
                }

                val outputFlow = terminal.executeCommandFlow(sessionId, command, timeout)

                if (outputFlow != null) {
                    val events = mutableListOf<String>()
                    var completionOutput: String? = null
                    var exitCode = 0
                    var hasCompleted = false
                    var didTimeout = false

                    try {
                        withTimeout(collectionTimeoutMs(timeout)) {
                            outputFlow.collect { event ->
                                if (event.isCompleted) {
                                    completionOutput = event.outputChunk
                                } else if (event.outputChunk.isNotEmpty()) {
                                    events.add(event.outputChunk)
                                }
                                if (event.isCompleted) {
                                    exitCode = event.exitCode
                                    hasCompleted = true
                                    didTimeout = event.timedOut
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        AppLogger.w(TAG, "Command execution timed out after ${timeout}ms")
                        cancelTimedOutCommand(terminal, sessionId)
                        hasCompleted = true
                        exitCode = -1
                        didTimeout = true
                    }

                    val fullOutput = completionOutput?.takeIf { it.isNotEmpty() } ?: events.joinToString("")
                    AppLogger.d(TAG, "Command output collected: '$fullOutput', exitCode: $exitCode")
                    val errorMessage =
                            when {
                                didTimeout -> null
                                !hasCompleted -> context.getString(R.string.terminal_error_command_failed)
                                else -> null
                            }

                    ToolResult(
                            toolName = tool.name,
                            success = errorMessage == null && exitCode == 0,
                            result = TerminalCommandResultData(
                                    command = command,
                                    output = fullOutput,
                                    exitCode = exitCode,
                                    sessionId = sessionId,
                                    timedOut = didTimeout
                            ),
                            error = errorMessage
                    )
                } else {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_command_failed)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "执行终端命令时出错", e)
                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_execute_command, e.message ?: "")
                )
            }
        }
    }

    /** 在指定的终端会话中执行命令并流式返回输出 */
    fun executeCommandInSessionStream(tool: AITool): Flow<ToolResult> = flow {
        try {
            val command = tool.parameters.find { param -> param.name == "command" }?.value ?: ""
            val sessionId = tool.parameters.find { param -> param.name == "session_id" }?.value

            if (sessionId.isNullOrBlank()) {
                emit(
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_session_id)
                    )
                )
                return@flow
            }

            val timeout =
                tool.parameters
                    .find { param -> param.name == "timeout_ms" }
                    ?.value
                    ?.toLongOrNull()
                    ?: 1800000L

            val terminal = Terminal.getInstance(context)

            if (terminal.terminalState.value.sessions.none { it.id == sessionId }) {
                sessionNameToIdMap.entries.removeIf { it.value == sessionId }
                emit(
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_session_not_exist, sessionId)
                    )
                )
                return@flow
            }

            emit(
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result =
                        TerminalStreamEventData(
                            type = "start",
                            command = command,
                            sessionId = sessionId,
                            chunkIndex = 0,
                            receivedChars = 0
                        ),
                    error = ""
                )
            )

            val outputFlow = terminal.executeCommandFlow(sessionId, command, timeout)
            val events = mutableListOf<String>()
            var completionOutput: String? = null
            var exitCode = 0
            var hasCompleted = false
            var didTimeout = false
            var chunkIndex = 0
            var receivedChars = 0

            try {
                withTimeout(collectionTimeoutMs(timeout)) {
                    outputFlow.collect { event ->
                        if (event.isCompleted) {
                            completionOutput = event.outputChunk
                            exitCode = event.exitCode
                            hasCompleted = true
                            didTimeout = event.timedOut
                            return@collect
                        }

                        val chunk = event.outputChunk
                        if (chunk.isEmpty()) {
                            return@collect
                        }

                        events.add(chunk)
                        receivedChars += chunk.length
                        emit(
                            ToolResult(
                                toolName = tool.name,
                                success = true,
                                result =
                                    TerminalStreamEventData(
                                        type = "chunk",
                                        command = command,
                                        sessionId = sessionId,
                                        chunk = chunk,
                                        chunkIndex = chunkIndex,
                                        receivedChars = receivedChars
                                    ),
                                error = ""
                            )
                        )
                        chunkIndex += 1
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLogger.w(TAG, "Command execution timed out after ${timeout}ms")
                cancelTimedOutCommand(terminal, sessionId)
                hasCompleted = true
                exitCode = -1
                didTimeout = true
            }

            val fullOutput = completionOutput?.takeIf { it.isNotEmpty() } ?: events.joinToString("")
            val errorMessage =
                when {
                    didTimeout -> null
                    !hasCompleted -> context.getString(R.string.terminal_error_command_failed)
                    else -> null
                }

            emit(
                ToolResult(
                    toolName = tool.name,
                    success = errorMessage == null && exitCode == 0,
                    result =
                        TerminalCommandResultData(
                            command = command,
                            output = fullOutput,
                            exitCode = exitCode,
                            sessionId = sessionId,
                            timedOut = didTimeout
                        ),
                    error = errorMessage
                )
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "流式执行终端命令时出错", e)
            emit(
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_execute_command, e.message ?: "")
                )
            )
        }
    }

    /** 在隐藏终端执行器中执行命令 */
    fun executeHiddenCommand(tool: AITool): ToolResult {
        return runBlocking {
            try {
                val command = tool.parameters.find { it.name == "command" }?.value ?: ""
                if (command.isBlank()) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_command)
                    )
                }

                val executorKey =
                    tool.parameters
                        .find { it.name == "executor_key" }
                        ?.value
                        ?.trim()
                        ?.ifEmpty { "default" }
                        ?: "default"
                val timeoutMs =
                    tool.parameters
                        .find { it.name == "timeout_ms" }
                        ?.value
                        ?.toLongOrNull()
                        ?: 120000L

                val terminal = Terminal.getInstance(context)
                val hiddenResult =
                    terminal.executeHiddenCommand(
                        command = command,
                        executorKey = executorKey,
                        timeoutMs = timeoutMs,
                        target = HostTerminalTarget.UBUNTU
                    )
                val output = extractHiddenExecOutput(hiddenResult)
                val didTimeout = hiddenResult.state == HostTerminalHiddenResult.State.TIMEOUT
                val errorMessage =
                    when {
                        didTimeout -> null
                        !hiddenResult.isOk ->
                            context.getString(
                                R.string.terminal_error_execute_hidden_command,
                                buildHiddenExecFailureDetail(hiddenResult)
                            )
                        else -> null
                    }

                ToolResult(
                    toolName = tool.name,
                    success = errorMessage == null && hiddenResult.exitCode == 0,
                    result =
                        HiddenTerminalCommandResultData(
                            command = command,
                            output = output,
                            exitCode = hiddenResult.exitCode,
                            executorKey = executorKey,
                            timedOut = didTimeout
                        ),
                    error = errorMessage
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "执行隐藏终端命令时出错", e)
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error =
                        context.getString(
                            R.string.terminal_error_execute_hidden_command,
                            e.message ?: ""
                        )
                )
            }
        }
    }

    /** 向指定的终端会话写入输入 */
    fun inputInSession(tool: AITool): ToolResult {
        return runBlocking {
            val sessionId = tool.parameters.find { it.name == "session_id" }?.value
            try {
                if (sessionId.isNullOrBlank()) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_session_id)
                    )
                }

                val inputParam = tool.parameters.find { it.name == "input" }
                val hasInput = inputParam != null
                val input = inputParam?.value ?: ""
                val control = normalizeControl(tool.parameters.find { it.name == "control" }?.value)

                if (!hasInput && control == null) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_input_or_control)
                    )
                }

                val terminal = Terminal.getInstance(context)

                val acceptedChars = applyTerminalInput(
                    terminal = terminal,
                    sessionId = sessionId,
                    hasInput = hasInput,
                    input = input,
                    control = control
                )

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(
                        context.getString(
                            R.string.terminal_input_sent,
                            sessionId,
                            acceptedChars
                        )
                    )
                )
            } catch (e: IllegalArgumentException) {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = e.message ?: context.getString(R.string.terminal_error_input)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "向终端会话写入输入时出错", e)
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_input_with_reason, e.message ?: "")
                )
            }
        }
    }

    /** 关闭一个终端会话 */
    fun closeSession(tool: AITool): ToolResult {
        return runBlocking {
            val sessionId = tool.parameters.find { it.name == "session_id" }?.value
            try {
                if (sessionId.isNullOrBlank()) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_session_id)
                    )
                }

                val terminal = Terminal.getInstance(context)
                terminal.closeSessionAndWait(sessionId)

                // 从名称映射中移除
                sessionNameToIdMap.entries.removeIf { it.value == sessionId }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = TerminalSessionCloseResultData(
                        sessionId = sessionId,
                        success = true,
                        message = context.getString(R.string.terminal_session_closed, sessionId)
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "关闭终端会话时出错", e)
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_close_session, sessionId, e.message ?: "")
                )
            }
        }
    }

    /** 获取终端会话当前屏幕内容（不包含历史滚动缓冲） */
    fun getSessionScreen(tool: AITool): ToolResult {
        return runBlocking {
            val sessionId = tool.parameters.find { it.name == "session_id" }?.value
            try {
                if (sessionId.isNullOrBlank()) {
                    return@runBlocking ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.terminal_error_missing_session_id)
                    )
                }

                val terminal = Terminal.getInstance(context)
                val screen = terminal.getSessionScreen(sessionId)

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = TerminalSessionScreenResultData(
                        sessionId = sessionId,
                        rows = screen.rows,
                        cols = screen.cols,
                        content = screen.content
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取终端会话屏幕内容时出错", e)
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = context.getString(R.string.terminal_error_get_screen, e.message ?: "")
                )
            }
        }
    }

    private fun renderSingleScreen(screen: Array<Array<HostTerminalChar>>): String {
        val lines = screen.map { row ->
            buildString {
                row.forEach { cell -> append(cell.char) }
            }.trimEnd()
        }.toMutableList()

        while (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n")
    }

    private suspend fun cancelTimedOutCommand(terminal: Terminal, sessionId: String) {
        terminal.sendInterruptSignalAndWait(sessionId)
        val settled =
            withTimeoutOrNull(COMMAND_CANCEL_SETTLE_TIMEOUT_MS) {
                terminal.terminalState.first { state ->
                    val session = state.sessions.find { it.id == sessionId }
                    session?.currentExecutingCommand?.isExecuting != true
                }
            }
        if (settled == null) {
            AppLogger.w(TAG, "Timed-out command cancellation did not settle within ${COMMAND_CANCEL_SETTLE_TIMEOUT_MS}ms")
        }
    }

    private fun extractHiddenExecOutput(result: HostTerminalHiddenResult): String {
        return result.output.ifBlank { result.rawOutputPreview }
    }

    private fun buildHiddenExecFailureDetail(result: HostTerminalHiddenResult): String {
        val summary =
            buildString {
                append("state=")
                append(result.state.name)
                append(", target=")
                append(result.target.wireName)
                val error = result.error.trim()
                if (error.isNotEmpty()) {
                    append(", error=")
                    append(error)
                }
            }
        val preview = result.rawOutputPreview.trim()
        return if (preview.isNotEmpty()) {
            "$summary\n$preview"
        } else {
            summary
        }
    }

    private fun parseYieldTimeMs(tool: AITool, defaultValue: Long): Long {
        return tool.parameters.find { it.name == "yield_time_ms" }?.value?.toLongOrNull()
            ?.coerceIn(0L, MAX_TERMUX_YIELD_MS)
            ?: defaultValue
    }

    private fun toTermuxSessionResultData(
        session: HostTermuxExecSession
    ): TermuxExecSessionResultData =
        TermuxExecSessionResultData(
            sessionId = session.sessionId,
            sessionName = session.sessionName,
            workingDirectory = session.workingDirectory,
            state = session.state.name.lowercase(),
            cursor = session.cursor.toString(),
            exitCode = session.exitCode,
            persistent = session.persistent,
            startedAtMs = session.startedAtEpochMs
        )

    private fun sessionMapKey(target: HostTerminalTarget, sessionName: String): String {
        return "${target.wireName}:$sessionName"
    }

    private fun collectionTimeoutMs(commandTimeoutMs: Long): Long =
        commandTimeoutMs
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE - COMMAND_BACKEND_SETTLE_TIMEOUT_MS) +
            COMMAND_BACKEND_SETTLE_TIMEOUT_MS

    private fun normalizeControl(rawControl: String?): String? {
        val value = rawControl?.trim()?.lowercase()
        if (value.isNullOrEmpty()) return null
        return when (value) {
            "return" -> "enter"
            "escape" -> "esc"
            "arrowup" -> "up"
            "arrowdown" -> "down"
            "arrowleft" -> "left"
            "arrowright" -> "right"
            "pgup", "page_up" -> "pageup"
            "pgdn", "page_down" -> "pagedown"
            "del" -> "delete"
            else -> value
        }
    }

    private suspend fun applyTerminalInput(
        terminal: Terminal,
        sessionId: String,
        hasInput: Boolean,
        input: String,
        control: String?
    ): Int = terminal.sendInputAndWait(sessionId, hasInput, input, control)
}
