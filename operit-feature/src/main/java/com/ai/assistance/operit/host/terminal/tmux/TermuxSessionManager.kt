package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TermuxSessionCreation(
    val sessionId: String,
    val sessionName: String,
    val target: HostTerminalTarget,
    val isNewSession: Boolean,
)

internal data class TermuxSessionExecution(
    val command: String,
    val output: String,
    val exitCode: Int,
    val sessionId: String,
    val target: HostTerminalTarget,
    val timedOut: Boolean,
)

internal data class TermuxSessionScreen(
    val sessionId: String,
    val rows: Int,
    val cols: Int,
    val content: String,
)

private data class SessionIdentity(
    val sourceName: String,
    val kind: String,
)

internal class TermuxSessionManager(
    private val transport: TermuxSessionTransport,
    private val tokenFactory: () -> String = TermuxSessionProtocol::newCommandToken,
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionIdentities = ConcurrentHashMap<String, SessionIdentity>()

    suspend fun initialize(): Boolean {
        val result = transport.executeProgram("tmux", listOf("-V"))
        return isSuccessful(result)
    }

    suspend fun createOrGet(sessionName: String): TermuxSessionCreation {
        val validatedName = TermuxSessionProtocol.requireSessionName(sessionName)
        val sessionId = TermuxSessionProtocol.sessionIdForName(validatedName)
        val identity = SessionIdentity(validatedName, "visible")
        val created =
            sessionLock(sessionId).withLock {
                ensureSession(sessionId, identity.sourceName, identity.kind).also {
                    sessionIdentities[sessionId] = identity
                }
            }
        return TermuxSessionCreation(
            sessionId,
            validatedName,
            HostTerminalTarget.UBUNTU,
            created,
        )
    }

    suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        onChunk: suspend (String) -> Unit = {},
    ): TermuxSessionExecution {
        val validatedId = TermuxSessionProtocol.requireSessionId(sessionId)
        validateCommand(command, timeoutMs)
        return sessionLock(validatedId).withLock {
            requireSessionExists(validatedId)
            executeLocked(validatedId, command, timeoutMs, onChunk)
        }
    }

    suspend fun executeHidden(
        executorKey: String,
        command: String,
        timeoutMs: Long,
    ): TermuxSessionExecution {
        val validatedKey = TermuxSessionProtocol.requireSessionName(executorKey)
        validateCommand(command, timeoutMs)
        val sessionId = TermuxSessionProtocol.hiddenSessionIdForKey(validatedKey)
        val identity = SessionIdentity(validatedKey, "hidden")
        return sessionLock(sessionId).withLock {
            ensureSession(sessionId, identity.sourceName, identity.kind)
            sessionIdentities[sessionId] = identity
            executeLocked(sessionId, command, timeoutMs, onChunk = {})
        }
    }

    suspend fun close(sessionId: String) {
        val validatedId = TermuxSessionProtocol.requireSessionId(sessionId)
        require(closeIfExists(validatedId)) { "Terminal session does not exist: $validatedId" }
    }

    suspend fun closeIfExists(sessionId: String): Boolean {
        val validatedId = TermuxSessionProtocol.requireSessionId(sessionId)
        val closed =
            sessionLock(validatedId).withLock {
                if (!hasSession(validatedId)) return@withLock false
                runTmuxChecked(
                    "close terminal session",
                    listOf("kill-session", "-t", target(validatedId)),
                )
                true
            }
        sessionIdentities.remove(validatedId)
        sessionLocks.remove(validatedId)
        return closed
    }

    suspend fun screen(sessionId: String): TermuxSessionScreen {
        val validatedId = TermuxSessionProtocol.requireSessionId(sessionId)
        requireSessionExists(validatedId)
        val pane = paneTarget(validatedId)
        val dimensions =
            runTmuxChecked(
                "read terminal dimensions",
                listOf("display-message", "-p", "-t", pane, "#{pane_height}\t#{pane_width}"),
            ).stdout.trim().split('\t')
        require(dimensions.size == 2) { "tmux returned invalid pane dimensions" }
        val rows = dimensions[0].toIntOrNull() ?: error("tmux returned invalid pane rows")
        val cols = dimensions[1].toIntOrNull() ?: error("tmux returned invalid pane columns")
        val content =
            runTmuxChecked(
                "capture terminal screen",
                listOf("capture-pane", "-p", "-J", "-t", pane),
            ).stdout.replace("\r\n", "\n").trimEnd('\r', '\n')
        return TermuxSessionScreen(validatedId, rows, cols, content)
    }

    suspend fun input(
        sessionId: String,
        hasInput: Boolean,
        input: String,
        rawControl: String?,
    ): Int {
        val validatedId = TermuxSessionProtocol.requireSessionId(sessionId)
        requireSessionExists(validatedId)
        val control = TermuxSessionProtocol.normalizeControl(rawControl)
        require(hasInput || control != null) { "At least one of input or control is required" }
        val pane = paneTarget(validatedId)

        if (control == null) {
            if (hasInput && input.isNotEmpty()) sendLiteral(pane, input)
            return if (hasInput) input.length else 0
        }

        if (control in MODIFIER_CONTROLS) {
            require(hasInput) { "$control requires input" }
            return sendModifiedInput(pane, control, input)
        }

        val key =
            TermuxSessionProtocol.controlKey(control)
                ?: throw IllegalArgumentException("Unsupported terminal control: $control")
        if (hasInput && input.isNotEmpty()) sendLiteral(pane, input)
        sendKey(pane, key)
        return (if (hasInput) input.length else 0) + 1
    }

    private fun validateCommand(command: String, timeoutMs: Long) {
        require(command.isNotBlank()) { "command must not be blank" }
        require(timeoutMs in 1_000L..TermuxSessionProtocol.MAX_TIMEOUT_MS) {
            "timeout_ms must be between 1000 and ${TermuxSessionProtocol.MAX_TIMEOUT_MS}"
        }
    }

    private suspend fun executeLocked(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        onChunk: suspend (String) -> Unit,
    ): TermuxSessionExecution {
        val token = tokenFactory()
        require(token.matches(Regex("^[A-Za-z0-9]+$"))) { "Invalid generated command token" }
        val outputPath = "${transport.termuxPrefix}/tmp/operit-terminal-$token.log"
        val bufferName = "operit_$token"
        val pane = paneTarget(sessionId)
        var pipeActive = false

        runProgramChecked("create command output file", "touch", listOf(outputPath))
        try {
            runTmuxChecked(
                "start terminal output capture",
                listOf(
                    "pipe-pane",
                    "-t",
                    pane,
                    TermuxSessionProtocol.pipeCommand(transport.termuxPrefix, outputPath),
                ),
            )
            pipeActive = true
            runTmuxChecked(
                "load terminal command",
                listOf("load-buffer", "-b", bufferName, "-"),
                stdin = TermuxSessionProtocol.commandPayload(command, token),
            )
            runTmuxChecked(
                "paste terminal command",
                listOf("paste-buffer", "-d", "-b", bufferName, "-t", pane),
            )
            runTmuxChecked("submit terminal command", listOf("send-keys", "-t", pane, "Enter"))

            val deadline = nanoTime() + timeoutMs * 1_000_000L
            var emittedOutput = ""
            while (nanoTime() < deadline) {
                val parsed = readCapture(outputPath, token)
                emittedOutput = emitDelta(emittedOutput, parsed.output, onChunk)
                if (parsed.complete) {
                    stopPipe(pane)
                    pipeActive = false
                    val finalParsed = readCapture(outputPath, token)
                    emittedOutput = emitDelta(emittedOutput, finalParsed.output, onChunk)
                    return TermuxSessionExecution(
                        command = command,
                        output = emittedOutput,
                        exitCode =
                            finalParsed.exitCode
                                ?: error("tmux command completed without an exit code"),
                        sessionId = sessionId,
                        target = TermuxSessionProtocol.targetForSessionId(sessionId),
                        timedOut = false,
                    )
                }
                if (!hasSession(sessionId)) {
                    error("Terminal session exited while executing the command")
                }
                sleeper(TermuxSessionProtocol.POLL_INTERVAL_MS)
            }

            val identity =
                sessionIdentities[sessionId]
                    ?: error("Terminal session identity is unavailable: $sessionId")
            var interruptedCleanly = false
            try {
                sendKey(pane, "C-c")
                val interruptDeadline =
                    nanoTime() + COMMAND_INTERRUPT_GRACE_MS * 1_000_000L
                while (nanoTime() < interruptDeadline && hasSession(sessionId)) {
                    sleeper(TermuxSessionProtocol.POLL_INTERVAL_MS)
                    val interruptedCapture = readCapture(outputPath, token)
                    emittedOutput = emitDelta(emittedOutput, interruptedCapture.output, onChunk)
                    if (interruptedCapture.complete) {
                        stopPipe(pane)
                        pipeActive = false
                        val finalCapture = readCapture(outputPath, token)
                        emittedOutput = emitDelta(emittedOutput, finalCapture.output, onChunk)
                        interruptedCleanly = true
                        return timedOutExecution(command, emittedOutput, sessionId)
                    }
                }
            } finally {
                if (!interruptedCleanly) {
                    pipeActive = false
                    rebuildTimedOutSession(sessionId, identity)
                }
            }

            return timedOutExecution(command, emittedOutput, sessionId)
        } finally {
            if (pipeActive && hasSession(sessionId)) stopPipe(pane)
            runProgramChecked("remove command output file", "rm", listOf("-f", outputPath))
        }
    }

    private fun timedOutExecution(
        command: String,
        output: String,
        sessionId: String,
    ): TermuxSessionExecution =
        TermuxSessionExecution(
            command = command,
            output = output,
            exitCode = -1,
            sessionId = sessionId,
            target = TermuxSessionProtocol.targetForSessionId(sessionId),
            timedOut = true,
        )

    private suspend fun rebuildTimedOutSession(
        sessionId: String,
        identity: SessionIdentity,
    ) {
        if (hasSession(sessionId)) {
            runTmuxChecked(
                "kill unresponsive terminal session",
                listOf("kill-session", "-t", target(sessionId)),
            )
        }
        ensureSession(sessionId, identity.sourceName, identity.kind)
        sessionIdentities[sessionId] = identity
    }

    private suspend fun ensureSession(
        sessionId: String,
        sourceName: String,
        kind: String,
    ): Boolean {
        if (hasSession(sessionId)) {
            verifySessionMetadata(sessionId, sourceName, kind)
            return false
        }

        val createResult =
            runTmux(
                listOf(
                    "new-session",
                    "-d",
                    "-s",
                    sessionId,
                    "-x",
                    "120",
                    "-y",
                    "40",
                    TermuxSessionProtocol.ubuntuShellCommand(transport.termuxPrefix),
                ),
            )
        if (!isSuccessful(createResult)) {
            if (hasSession(sessionId)) {
                verifySessionMetadata(sessionId, sourceName, kind)
                return false
            }
            throw commandFailure("create terminal session", createResult)
        }

        try {
            setSessionOption(sessionId, "@operit_source_name", sourceName)
            setSessionOption(sessionId, "@operit_kind", kind)
            setSessionOption(sessionId, "@operit_target", HostTerminalTarget.UBUNTU.wireName)
            runTmuxChecked(
                "configure terminal history",
                listOf(
                    "set-option",
                    "-w",
                    "-t",
                    paneTarget(sessionId),
                    "history-limit",
                    TermuxSessionProtocol.HISTORY_LIMIT,
                ),
            )
            waitUntilReady(sessionId)
            runTmuxChecked(
                "clear terminal bootstrap screen",
                listOf("send-keys", "-t", paneTarget(sessionId), "C-l"),
            )
        } catch (error: Exception) {
            runCatching {
                runTmuxChecked(
                    "remove incomplete terminal session",
                    listOf("kill-session", "-t", target(sessionId)),
                )
            }
            throw error
        }
        return true
    }

    private suspend fun waitUntilReady(sessionId: String) {
        val deadline = nanoTime() + TermuxSessionProtocol.TMUX_TIMEOUT_MS * 1_000_000L
        while (nanoTime() < deadline) {
            if (!hasSession(sessionId)) error("Terminal session exited during startup")
            val capture =
                runTmuxChecked(
                    "wait for terminal session",
                    listOf("capture-pane", "-p", "-J", "-t", paneTarget(sessionId)),
                ).stdout
            if (capture.contains("__OPERIT_READY__")) return
            sleeper(TermuxSessionProtocol.POLL_INTERVAL_MS)
        }
        error("Timed out starting the terminal session")
    }

    private suspend fun verifySessionMetadata(
        sessionId: String,
        sourceName: String,
        kind: String,
    ) {
        require(
            readSessionOption(sessionId, "@operit_source_name") == sourceName &&
                readSessionOption(sessionId, "@operit_kind") == kind &&
                readSessionOption(sessionId, "@operit_target") == HostTerminalTarget.UBUNTU.wireName,
        ) { "tmux session identity does not match the requested terminal session" }
    }

    private suspend fun setSessionOption(sessionId: String, option: String, value: String) {
        runTmuxChecked(
            "store terminal session metadata",
            listOf("set-option", "-t", target(sessionId), option, value),
        )
    }

    private suspend fun readSessionOption(sessionId: String, option: String): String =
        runTmuxChecked(
            "read terminal session metadata",
            listOf("show-options", "-v", "-t", target(sessionId), option),
        ).stdout.trimEnd('\r', '\n')

    private suspend fun requireSessionExists(sessionId: String) {
        require(hasSession(sessionId)) { "Terminal session does not exist: $sessionId" }
    }

    private suspend fun hasSession(sessionId: String): Boolean {
        val result = runTmux(listOf("has-session", "-t", target(sessionId)))
        if (result.timedOut || result.errCode != -1) {
            throw commandFailure("check terminal session", result)
        }
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw commandFailure("check terminal session", result)
        }
    }

    private suspend fun readCapture(outputPath: String, token: String): CaptureParse =
        TermuxSessionProtocol.parseCapture(
            runProgramChecked("read terminal output", "cat", listOf(outputPath)).stdout,
            token,
        )

    private suspend fun emitDelta(
        previous: String,
        current: String,
        onChunk: suspend (String) -> Unit,
    ): String {
        require(current.startsWith(previous)) { "Terminal output capture was not append-only" }
        val chunk = current.substring(previous.length)
        if (chunk.isNotEmpty()) onChunk(chunk)
        return current
    }

    private suspend fun sendModifiedInput(pane: String, control: String, input: String): Int =
        when (control) {
            "ctrl", "control" -> {
                require(input.length == 1) { "ctrl requires exactly one input character" }
                sendKey(pane, ctrlKey(input[0]))
                1
            }
            "alt", "meta", "cmd" -> {
                sendKey(pane, "Escape")
                if (input.isNotEmpty()) sendLiteral(pane, input)
                input.length + 1
            }
            "shift" -> {
                val shifted = input.uppercase()
                if (shifted.isNotEmpty()) sendLiteral(pane, shifted)
                shifted.length
            }
            else -> error("Unsupported modifier control: $control")
        }

    private fun ctrlKey(value: Char): String =
        when (val upper = value.uppercaseChar()) {
            in 'A'..'Z' -> "C-${upper.lowercaseChar()}"
            '@', '[', '\\', ']', '^', '_', '?' -> "C-$upper"
            else -> throw IllegalArgumentException("Unsupported ctrl input: $value")
        }

    private suspend fun sendLiteral(pane: String, value: String) {
        runTmuxChecked("send terminal input", listOf("send-keys", "-t", pane, "-l", "--", value))
    }

    private suspend fun sendKey(pane: String, key: String) {
        runTmuxChecked("send terminal control key", listOf("send-keys", "-t", pane, key))
    }

    private suspend fun stopPipe(pane: String) {
        runTmuxChecked("stop terminal output capture", listOf("pipe-pane", "-t", pane))
        sleeper(40L)
    }

    private suspend fun runTmux(
        arguments: List<String>,
        stdin: String? = null,
    ): TermuxTransportResult = transport.executeProgram("tmux", arguments, stdin)

    private suspend fun runTmuxChecked(
        action: String,
        arguments: List<String>,
        stdin: String? = null,
    ): TermuxTransportResult {
        val result = runTmux(arguments, stdin)
        if (!isSuccessful(result)) throw commandFailure(action, result)
        return result
    }

    private suspend fun runProgramChecked(
        action: String,
        program: String,
        arguments: List<String>,
        stdin: String? = null,
    ): TermuxTransportResult {
        val result = transport.executeProgram(program, arguments, stdin)
        if (!isSuccessful(result)) throw commandFailure(action, result)
        return result
    }

    private fun isSuccessful(result: TermuxTransportResult): Boolean =
        !result.timedOut && result.errCode == -1 && result.exitCode == 0

    private fun commandFailure(
        action: String,
        result: TermuxTransportResult,
    ): IllegalStateException {
        val detail =
            listOf(result.errorMessage, result.stderr, result.stdout)
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: "exitCode=${result.exitCode}, errCode=${result.errCode}"
        return IllegalStateException("Failed to $action: $detail")
    }

    private fun sessionLock(sessionId: String): Mutex =
        sessionLocks.computeIfAbsent(sessionId) { Mutex() }

    private fun target(sessionId: String): String = TermuxSessionProtocol.tmuxTarget(sessionId)

    private fun paneTarget(sessionId: String): String = "${target(sessionId)}:0.0"

    private companion object {
        const val COMMAND_INTERRUPT_GRACE_MS = 3_000L
        val MODIFIER_CONTROLS = setOf("ctrl", "control", "alt", "meta", "cmd", "shift")
    }
}
