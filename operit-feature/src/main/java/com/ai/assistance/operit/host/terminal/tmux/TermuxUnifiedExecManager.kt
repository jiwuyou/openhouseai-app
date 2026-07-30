package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTerminalScreenSnapshot
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.HostTermuxExecResult
import com.ai.assistance.operit.host.terminal.HostTermuxExecSession
import com.ai.assistance.operit.host.terminal.HostTermuxExecState
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class TermuxUnifiedExecManager(
    private val transport: TermuxSessionTransport,
    private val sessionIdFactory: () -> String = {
        "operit_v_t_${UUID.randomUUID().toString().replace("-", "").take(24)}"
    },
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        command: String,
        workingDirectory: String?,
        yieldTimeMs: Long,
        sessionName: String?,
    ): HostTermuxExecResult {
        require(command.isNotBlank()) { "command must not be blank" }
        require(yieldTimeMs in 0L..MAX_YIELD_MS) {
            "yield_time must be between 0 and $MAX_YIELD_MS milliseconds"
        }
        val availability = try {
            tmuxAvailability()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return failed(error.message ?: error.javaClass.simpleName)
        }
        if (!availability.available) return setupRequired(availability.detail)

        val sessionId = TermuxSessionProtocol.requireSessionId(sessionIdFactory())
        require(TermuxSessionProtocol.isTermuxSessionId(sessionId)) {
            "Managed Termux session IDs must use the operit_v_t_ prefix"
        }
        val name = normalizeSessionName(sessionName, sessionId)
        val directory = normalizeWorkingDirectory(workingDirectory)
        val startedAt = currentTimeMillis()
        createSession(sessionId, name, directory, command, startedAt)

        val snapshot = try {
            awaitSnapshot(sessionId, yieldTimeMs, afterCursor = 0L)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return recoverableFailure(sessionId, name, error)
        }
        val result = snapshot.toResult(sessionId, name)
        if (snapshot.state != HostTermuxExecState.COMPLETED) return result

        val tmuxCleanupError = runCatching { removeTmuxSession(sessionId) }.exceptionOrNull()
        if (tmuxCleanupError != null) {
            return result.copy(
                error = "Command completed, but tmux cleanup failed: " +
                    (tmuxCleanupError.message ?: tmuxCleanupError.javaClass.simpleName),
                persistent = true,
            )
        }
        if (snapshot.hasMoreOutput) return result

        return runCatching { removeStateDirectory(sessionId) }
            .fold(
                onSuccess = { result.copy(sessionId = null, persistent = false) },
                onFailure = { error ->
                    result.copy(
                        error = "Command completed, but managed state cleanup failed: " +
                            (error.message ?: error.javaClass.simpleName),
                        persistent = true,
                    )
                },
            )
    }

    suspend fun writeStdin(
        sessionId: String,
        chars: String,
        control: String?,
        yieldTimeMs: Long,
        afterCursor: Long?,
    ): HostTermuxExecResult {
        val validatedId = requireTermuxSessionId(sessionId)
        require(yieldTimeMs in 0L..MAX_YIELD_MS) {
            "yield-time must be between 0 and $MAX_YIELD_MS milliseconds"
        }
        val metadata = readMetadata(validatedId) ?: return lost(validatedId, "Managed Termux session is unavailable")
        val cursor = afterCursor ?: 0L
        var snapshot = reconcile(validatedId, readSnapshot(validatedId, cursor), cursor)
        if (snapshot.state == HostTermuxExecState.RUNNING) {
            if (chars.isNotEmpty() || !control.isNullOrBlank()) {
                sendInput(validatedId, chars, control)
            }
            snapshot = awaitSnapshot(validatedId, yieldTimeMs, cursor)
        } else if (chars.isNotEmpty() || !control.isNullOrBlank()) {
            return snapshot.toResult(validatedId, metadata.sessionName).copy(
                error = "Managed Termux session is not running",
            )
        }
        if (snapshot.state == HostTermuxExecState.COMPLETED) removeTmuxSession(validatedId)
        return snapshot.toResult(validatedId, metadata.sessionName)
    }

    suspend fun list(includeCompleted: Boolean): List<HostTermuxExecSession> =
        readMetadataList().mapNotNull { metadata ->
            val snapshot = reconcile(
                metadata.sessionId,
                readSnapshot(metadata.sessionId, afterCursor = 0L, maxOutputBytes = 0),
                afterCursor = 0L,
            )
            if (snapshot.state == HostTermuxExecState.COMPLETED) removeTmuxSession(metadata.sessionId)
            if (!includeCompleted && snapshot.state == HostTermuxExecState.COMPLETED) return@mapNotNull null
            HostTermuxExecSession(
                sessionId = metadata.sessionId,
                sessionName = metadata.sessionName,
                workingDirectory = metadata.workingDirectory,
                state = snapshot.state,
                cursor = snapshot.outputSize,
                exitCode = snapshot.exitCode,
                startedAtEpochMs = metadata.startedAtEpochMs,
            )
        }.sortedByDescending { it.startedAtEpochMs }

    suspend fun close(sessionId: String) {
        val validatedId = requireTermuxSessionId(sessionId)
        require(readMetadata(validatedId) != null || hasTmuxSession(validatedId)) {
            "Terminal session does not exist: $validatedId"
        }
        removeTmuxSession(validatedId)
        removeStateDirectory(validatedId)
    }

    suspend fun input(
        sessionId: String,
        hasInput: Boolean,
        input: String,
        control: String?,
    ): Int {
        val validatedId = requireTermuxSessionId(sessionId)
        val snapshot = reconcile(
            validatedId,
            readSnapshot(validatedId, afterCursor = 0L, maxOutputBytes = 0),
            afterCursor = 0L,
        )
        require(snapshot.state == HostTermuxExecState.RUNNING) {
            "Managed Termux session is not running: $validatedId"
        }
        require(hasInput || !control.isNullOrBlank()) { "At least one of input or control is required" }
        sendInput(validatedId, if (hasInput) input else "", control)
        return (if (hasInput) input.length else 0) + if (control.isNullOrBlank()) 0 else 1
    }

    suspend fun screen(sessionId: String): HostTerminalScreenSnapshot {
        val validatedId = requireTermuxSessionId(sessionId)
        if (hasTmuxSession(validatedId)) {
            val pane = paneTarget(validatedId)
            val dimensions = runTmuxChecked(
                "read managed Termux dimensions",
                listOf("display-message", "-p", "-t", pane, "#{pane_height}\t#{pane_width}"),
            ).stdout.trim().split('\t')
            val content = runTmuxChecked(
                "capture managed Termux screen",
                listOf("capture-pane", "-p", "-J", "-t", pane),
            ).stdout.replace("\r\n", "\n").trimEnd('\r', '\n')
            return HostTerminalScreenSnapshot(
                validatedId,
                dimensions.getOrNull(0)?.toIntOrNull() ?: 40,
                dimensions.getOrNull(1)?.toIntOrNull() ?: 120,
                content,
            )
        }
        val initial = readSnapshot(validatedId, afterCursor = 0L, maxOutputBytes = 0)
        val snapshot = readSnapshot(
            validatedId,
            afterCursor = (initial.outputSize - MAX_SCREEN_OUTPUT_BYTES).coerceAtLeast(0L),
            maxOutputBytes = MAX_SCREEN_OUTPUT_BYTES,
        )
        require(snapshot.state != HostTermuxExecState.LOST || snapshot.output.isNotEmpty()) {
            "Managed Termux session is unavailable: $validatedId"
        }
        val lines = snapshot.output.lines().ifEmpty { listOf("") }
        return HostTerminalScreenSnapshot(
            validatedId,
            lines.size.coerceAtLeast(1),
            lines.maxOfOrNull(String::length)?.coerceAtLeast(1) ?: 1,
            snapshot.output,
        )
    }

    private suspend fun createSession(
        sessionId: String,
        sessionName: String,
        workingDirectory: String,
        command: String,
        startedAt: Long,
    ) {
        val directory = stateDirectory(sessionId)
        val runnerPath = "$directory/runner.sh"
        val pipeWriterPath = "$directory/pipe-writer.sh"
        val setupScript = buildString {
            append("set -eu; umask 077; mkdir -p ")
            append(quote(stateRoot()))
            append("; rm -rf ")
            append(quote(directory))
            append("; mkdir -p ")
            append(quote(directory))
            append("; printf '%s' ")
            append(quote(sessionName))
            append(" > ")
            append(quote("$directory/session-name"))
            append("; printf '%s' ")
            append(quote(workingDirectory))
            append(" > ")
            append(quote("$directory/working-directory"))
            append("; printf '%s' ")
            append(quote(startedAt.toString()))
            append(" > ")
            append(quote("$directory/started-at"))
            append("; printf '%s\\n' starting > ")
            append(quote("$directory/state"))
            append("; : > ")
            append(quote("$directory/output.log"))
        }
        runProgramChecked("prepare managed Termux command", "bash", listOf("-lc", setupScript))
        writeExecutable("write managed Termux runner", runnerPath, runnerScript(directory, command))
        writeExecutable("write managed Termux pipe writer", pipeWriterPath, pipeWriterScript(directory))

        val create = runTmux(
            listOf(
                "new-session", "-d", "-s", sessionId, "-c", workingDirectory,
                "-x", "120", "-y", "40", runnerPath,
            ),
        )
        if (!isSuccessful(create)) {
            runCatching { removeStateDirectory(sessionId) }
            throw commandFailure("create managed Termux session", create)
        }
        try {
            setSessionOption(sessionId, "@operit_kind", "unified-exec")
            setSessionOption(sessionId, "@operit_target", HostTerminalTarget.TERMUX.wireName)
            setSessionOption(sessionId, "@operit_source_name", sessionName)
            setSessionOption(sessionId, "@operit_persistent", "1")
            runTmuxChecked(
                "disable managed Termux pane retention",
                listOf("set-option", "-w", "-t", paneTarget(sessionId), "remain-on-exit", "off"),
            )
            runTmuxChecked(
                "configure managed Termux history",
                listOf("set-option", "-w", "-t", paneTarget(sessionId), "history-limit", TermuxSessionProtocol.HISTORY_LIMIT),
            )
            runTmuxChecked(
                "capture managed Termux output",
                listOf("pipe-pane", "-t", paneTarget(sessionId), quote(pipeWriterPath)),
            )
            runProgramChecked("start managed Termux command", "touch", listOf("$directory/start"))
        } catch (error: Throwable) {
            runCatching { removeTmuxSession(sessionId) }
            runCatching { removeStateDirectory(sessionId) }
            throw error
        }
    }

    private fun runnerScript(directory: String, command: String): String {
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        val prefix = transport.termuxPrefix
        return """
            #!$prefix/bin/bash
            set +e
            state_dir=${quote(directory)}
            atomic_write() {
              value="${'$'}1"
              path="${'$'}2"
              tmp="${'$'}path.tmp.${'$'}${'$'}"
              printf '%s\n' "${'$'}value" > "${'$'}tmp" && mv -f "${'$'}tmp" "${'$'}path"
            }
            while [ ! -e "${'$'}state_dir/start" ]; do sleep 0.02; done
            atomic_write running "${'$'}state_dir/state"
            command="${'$'}(printf '%s' ${quote(encoded)} | ${quote("$prefix/bin/base64")} -d)"
            ${quote("$prefix/bin/bash")} --noprofile --norc -lc "${'$'}command"
            status="${'$'}?"
            atomic_write "${'$'}status" "${'$'}state_dir/exit-code"
            : > "${'$'}state_dir/process-complete.tmp.${'$'}${'$'}"
            mv -f "${'$'}state_dir/process-complete.tmp.${'$'}${'$'}" "${'$'}state_dir/process-complete"
            atomic_write finishing "${'$'}state_dir/state"
            exit "${'$'}status"
        """.trimIndent()
    }

    private fun pipeWriterScript(directory: String): String {
        val prefix = transport.termuxPrefix
        return """
            #!$prefix/bin/bash
            set +e
            state_dir=${quote(directory)}
            atomic_write() {
              value="${'$'}1"
              path="${'$'}2"
              tmp="${'$'}path.tmp.${'$'}${'$'}"
              printf '%s\n' "${'$'}value" > "${'$'}tmp" && mv -f "${'$'}tmp" "${'$'}path"
            }
            ${quote("$prefix/bin/cat")} >> "${'$'}state_dir/output.log"
            pipe_status="${'$'}?"
            output_size="${'$'}(${quote("$prefix/bin/stat")} -c %s "${'$'}state_dir/output.log")"
            atomic_write "${'$'}output_size" "${'$'}state_dir/output-size"
            if [ "${'$'}pipe_status" -eq 0 ] && [ -e "${'$'}state_dir/process-complete" ]; then
              atomic_write completed "${'$'}state_dir/state"
            else
              atomic_write failed "${'$'}state_dir/state"
            fi
            exit "${'$'}pipe_status"
        """.trimIndent()
    }

    private suspend fun writeExecutable(action: String, path: String, content: String) {
        val script = "cat > ${quote(path)} && chmod 700 ${quote(path)}"
        runProgramChecked(action, "bash", listOf("-lc", script), content)
    }

    private suspend fun awaitSnapshot(
        sessionId: String,
        yieldTimeMs: Long,
        afterCursor: Long,
    ): ExecSnapshot {
        val deadline = nanoTime() + yieldTimeMs * 1_000_000L
        var snapshot = reconcile(sessionId, readSnapshot(sessionId, afterCursor), afterCursor)
        while (snapshot.state == HostTermuxExecState.RUNNING && nanoTime() < deadline) {
            sleeper(POLL_INTERVAL_MS)
            snapshot = reconcile(sessionId, readSnapshot(sessionId, afterCursor), afterCursor)
        }
        return snapshot
    }

    private suspend fun reconcile(
        sessionId: String,
        snapshot: ExecSnapshot,
        afterCursor: Long,
    ): ExecSnapshot {
        if (snapshot.state != HostTermuxExecState.RUNNING) return snapshot
        if (snapshot.rawState == "finishing") return snapshot
        if (hasTmuxSession(sessionId) && !isPaneDead(sessionId)) return snapshot
        val refreshed = readSnapshot(sessionId, afterCursor)
        if (refreshed.state != HostTermuxExecState.RUNNING) return refreshed
        if (refreshed.rawState == "finishing") return refreshed
        writeState(sessionId, "lost")
        return refreshed.copy(state = HostTermuxExecState.LOST)
    }

    private suspend fun isPaneDead(sessionId: String): Boolean {
        val result = runTmuxChecked(
            "read managed Termux process state",
            listOf("display-message", "-p", "-t", paneTarget(sessionId), "#{pane_dead}"),
        )
        return result.stdout.trim() == "1"
    }

    private suspend fun readSnapshot(
        sessionId: String,
        afterCursor: Long,
        maxOutputBytes: Int = MAX_OUTPUT_CHUNK_BYTES,
    ): ExecSnapshot {
        require(afterCursor >= 0L) { "after_cursor must be non-negative" }
        require(maxOutputBytes in 0..MAX_OUTPUT_CHUNK_BYTES) { "Invalid managed output read size" }
        val directory = stateDirectory(sessionId)
        val script = """
            # OPERIT_SNAPSHOT_V2
            dir=${quote(directory)}
            requested="${'$'}1"
            limit="${'$'}2"
            [ -d "${'$'}dir" ] || exit 44
            state="${'$'}(cat "${'$'}dir/state" 2>/dev/null || printf lost)"
            code="${'$'}(cat "${'$'}dir/exit-code" 2>/dev/null || true)"
            if [ "${'$'}state" = completed ] && [ -f "${'$'}dir/output-size" ]; then
              size="${'$'}(cat "${'$'}dir/output-size")"
            else
              size="${'$'}(${quote("${transport.termuxPrefix}/bin/stat")} -c %s "${'$'}dir/output.log")"
            fi
            start="${'$'}requested"
            [ "${'$'}start" -le "${'$'}size" ] || start="${'$'}size"
            available="${'$'}((size - start))"
            count="${'$'}limit"
            [ "${'$'}count" -le "${'$'}available" ] || count="${'$'}available"
            output=''
            if [ "${'$'}count" -gt 0 ]; then
              output="${'$'}(${quote("${transport.termuxPrefix}/bin/dd")} if="${'$'}dir/output.log" iflag=skip_bytes,count_bytes skip="${'$'}start" count="${'$'}count" status=none | ${quote("${transport.termuxPrefix}/bin/base64")} | ${quote("${transport.termuxPrefix}/bin/tr")} -d '\n')"
            fi
            printf '%s\n%s\n%s\n%s\n%s\n' "${'$'}state" "${'$'}code" "${'$'}size" "${'$'}start" "${'$'}output"
        """.trimIndent()
        val result = runProgram(
            "bash",
            listOf("-lc", script, "operit-read-snapshot", afterCursor.toString(), maxOutputBytes.toString()),
        )
        if (!isSuccessful(result)) {
            if (!result.timedOut && result.errCode == -1 && result.exitCode == 44) {
                return ExecSnapshot(HostTermuxExecState.LOST, "lost", null, "", 0L, 0L)
            }
            throw commandFailure("read managed Termux snapshot", result)
        }
        val lines = result.stdout.replace("\r\n", "\n").split('\n')
        val rawState = lines.getOrNull(0)?.trim()?.lowercase().orEmpty()
        val state = when (rawState) {
            "starting", "running", "finishing" -> HostTermuxExecState.RUNNING
            "completed" -> HostTermuxExecState.COMPLETED
            "closed" -> HostTermuxExecState.CLOSED
            "failed" -> HostTermuxExecState.FAILED
            "lost" -> HostTermuxExecState.LOST
            else -> HostTermuxExecState.LOST
        }
        val outputSize = lines.getOrNull(2)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val outputStart = lines.getOrNull(3)?.trim()?.toLongOrNull()?.coerceIn(0L, outputSize) ?: 0L
        val decoded = decodeOutputChunk(
            value = lines.getOrNull(4).orEmpty(),
            outputStart = outputStart,
            outputSize = outputSize,
            terminalState = state != HostTermuxExecState.RUNNING,
        )
        return ExecSnapshot(
            state = state,
            rawState = rawState,
            exitCode = lines.getOrNull(1)?.trim()?.toIntOrNull(),
            output = decoded.text.replace("\r\n", "\n"),
            cursor = (outputStart + decoded.consumedBytes).coerceAtMost(outputSize),
            outputSize = outputSize,
        )
    }

    private suspend fun readMetadataList(): List<ExecMetadata> {
        val root = stateRoot()
        val script = """
            root=${quote(root)}
            [ -d "${'$'}root" ] || exit 0
            for dir in "${'$'}root"/operit_v_t_*; do
              [ -d "${'$'}dir" ] || continue
              id="${'$'}{dir##*/}"
              name="${'$'}(${quote("${transport.termuxPrefix}/bin/base64")} < "${'$'}dir/session-name" | tr -d '\n')"
              cwd="${'$'}(${quote("${transport.termuxPrefix}/bin/base64")} < "${'$'}dir/working-directory" | tr -d '\n')"
              started="${'$'}(cat "${'$'}dir/started-at" 2>/dev/null || printf 0)"
              printf '%s\t%s\t%s\t%s\n' "${'$'}id" "${'$'}name" "${'$'}cwd" "${'$'}started"
            done
        """.trimIndent()
        val result = runProgramChecked("list managed Termux sessions", "bash", listOf("-lc", script))
        return result.stdout.lineSequence().mapNotNull { line ->
            val fields = line.trimEnd().split('\t')
            if (fields.size != 4 || !TermuxSessionProtocol.isTermuxSessionId(fields[0])) return@mapNotNull null
            ExecMetadata(
                sessionId = fields[0],
                sessionName = decodeMetadata(fields[1]),
                workingDirectory = decodeMetadata(fields[2]),
                startedAtEpochMs = fields[3].toLongOrNull() ?: 0L,
            )
        }.toList()
    }

    private suspend fun readMetadata(sessionId: String): ExecMetadata? =
        readMetadataList().firstOrNull { it.sessionId == sessionId }

    private fun ExecSnapshot.toResult(
        sessionId: String,
        sessionName: String,
    ): HostTermuxExecResult = HostTermuxExecResult(
            state = state,
            sessionId = sessionId,
            sessionName = sessionName,
            output = output,
            cursor = cursor,
            exitCode = exitCode,
            error = when (state) {
                HostTermuxExecState.FAILED -> "Managed Termux command failed"
                HostTermuxExecState.LOST -> "Managed Termux session was lost"
                HostTermuxExecState.CLOSED -> "Managed Termux session was closed"
                else -> ""
            },
            persistent = state == HostTermuxExecState.RUNNING || sessionId.isNotBlank(),
        )

    private suspend fun sendInput(sessionId: String, chars: String, rawControl: String?) {
        require(chars.isNotEmpty() || !rawControl.isNullOrBlank()) {
            "At least one of chars or control is required"
        }
        val pane = paneTarget(sessionId)
        val control = rawControl?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        if (control == null) {
            if (chars.isNotEmpty()) {
                runTmuxChecked("write managed Termux input", listOf("send-keys", "-t", pane, "-l", "--", chars))
            }
            return
        }
        if (control == "ctrl" || control == "control") {
            require(chars.length == 1) { "$control requires exactly one input character" }
            runTmuxChecked("send managed Termux control", listOf("send-keys", "-t", pane, ctrlKey(chars[0])))
            return
        }
        if (control in setOf("alt", "meta", "cmd")) {
            runTmuxChecked("send managed Termux modifier", listOf("send-keys", "-t", pane, "Escape"))
            if (chars.isNotEmpty()) {
                runTmuxChecked("write managed Termux input", listOf("send-keys", "-t", pane, "-l", "--", chars))
            }
            return
        }
        if (control == "shift") {
            val shifted = chars.uppercase()
            if (shifted.isNotEmpty()) {
                runTmuxChecked("write managed Termux input", listOf("send-keys", "-t", pane, "-l", "--", shifted))
            }
            return
        }
        if (chars.isNotEmpty()) {
            runTmuxChecked("write managed Termux input", listOf("send-keys", "-t", pane, "-l", "--", chars))
        }
        val key = when (control) {
            "interrupt", "ctrl-c", "control-c" -> "C-c"
            else -> TermuxSessionProtocol.controlKey(TermuxSessionProtocol.normalizeControl(control).orEmpty())
                ?: throw IllegalArgumentException("Unsupported terminal control: $rawControl")
        }
        runTmuxChecked("send managed Termux control", listOf("send-keys", "-t", pane, key))
    }

    private fun ctrlKey(value: Char): String =
        when (val upper = value.uppercaseChar()) {
            in 'A'..'Z' -> "C-${upper.lowercaseChar()}"
            '@', '[', '\\', ']', '^', '_', '?' -> "C-$upper"
            else -> throw IllegalArgumentException("Unsupported ctrl input: $value")
        }

    private suspend fun tmuxAvailability(): TmuxAvailability {
        ensureTermuxAvailable()
        if (!isTmuxInstalled()) {
            return TmuxAvailability(false, "tmux is not installed in Termux")
        }
        val result = runTmux(listOf("-V"))
        if (isSuccessful(result)) return TmuxAvailability(true, "")
        throw commandFailure("check tmux availability", result)
    }

    private suspend fun ensureTermuxAvailable() {
        val result = runProgram(
            "bash",
            listOf("--noprofile", "--norc", "-lc", "printf '%s\\n' ${quote(TERMUX_PROBE_MARKER)}"),
        )
        if (!isSuccessful(result) || result.stdout.trim() != TERMUX_PROBE_MARKER) {
            throw commandFailure("check Termux RUN_COMMAND availability", result)
        }
    }

    private suspend fun isTmuxInstalled(): Boolean {
        val result = runProgram(
            "bash",
            listOf(
                "--noprofile",
                "--norc",
                "-lc",
                "# OPERIT_TMUX_BINARY_PROBE\n[ -x ${quote("${transport.termuxPrefix}/bin/tmux")} ]",
            ),
        )
        if (isSuccessful(result)) return true
        if (!result.timedOut && result.errCode == -1 && result.exitCode == 1) return false
        throw commandFailure("check tmux installation", result)
    }

    private fun setupRequired(detail: String) = HostTermuxExecResult(
        state = HostTermuxExecState.SETUP_REQUIRED,
        error = detail.ifBlank { "tmux is required for managed Termux commands" },
        setupCommand = "pkg install -y tmux",
        missingDependencies = listOf("tmux"),
    )

    private fun failed(message: String) = HostTermuxExecResult(
        state = HostTermuxExecState.FAILED,
        error = message,
    )

    private fun recoverableFailure(
        sessionId: String,
        sessionName: String,
        error: Throwable,
    ) = HostTermuxExecResult(
        state = HostTermuxExecState.FAILED,
        sessionId = sessionId,
        sessionName = sessionName,
        error = error.message ?: error.javaClass.simpleName,
        persistent = true,
    )

    private fun lost(sessionId: String, message: String) = HostTermuxExecResult(
        state = HostTermuxExecState.LOST,
        sessionId = sessionId,
        error = message,
        persistent = true,
    )

    private fun normalizeSessionName(value: String?, sessionId: String): String {
        val name = value?.trim().orEmpty().ifBlank { "termux-${sessionId.takeLast(8)}" }
        require(name.length <= 128 && !name.contains('\n') && !name.contains('\r') && !name.contains('\u0000')) {
            "session_name must be at most 128 characters and contain no line breaks"
        }
        return name
    }

    private fun normalizeWorkingDirectory(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        val directory = when (trimmed) {
            "", "~", "${'$'}HOME" -> transport.termuxHome
            else -> when {
                trimmed.startsWith("~/") -> transport.termuxHome + trimmed.removePrefix("~")
                trimmed.startsWith("${'$'}HOME/") ->
                    transport.termuxHome + trimmed.removePrefix("${'$'}HOME")
                else -> trimmed
            }
        }
        require(!directory.contains('\n') && !directory.contains('\r') && !directory.contains('\u0000')) {
            "working_directory contains invalid characters"
        }
        return directory
    }

    private fun requireTermuxSessionId(value: String): String {
        val id = TermuxSessionProtocol.requireSessionId(value)
        require(TermuxSessionProtocol.isTermuxSessionId(id)) { "Expected a managed Termux session_id" }
        return id
    }

    private suspend fun writeState(sessionId: String, state: String) {
        val path = "${stateDirectory(sessionId)}/state"
        val script = "printf '%s\\n' ${quote(state)} > ${quote("$path.tmp")} && mv -f ${quote("$path.tmp")} ${quote(path)}"
        runProgramChecked("update managed Termux state", "bash", listOf("-lc", script))
    }

    private suspend fun removeStateDirectory(sessionId: String) {
        runProgramChecked(
            "remove managed Termux state",
            "rm",
            listOf("-rf", stateDirectory(sessionId)),
        )
    }

    private suspend fun removeTmuxSession(sessionId: String) {
        if (hasTmuxSession(sessionId)) {
            runTmuxChecked("close managed Termux session", listOf("kill-session", "-t", tmuxTarget(sessionId)))
        }
    }

    private suspend fun hasTmuxSession(sessionId: String): Boolean {
        val result = runTmux(listOf("has-session", "-t", tmuxTarget(sessionId)))
        if (!result.timedOut && result.exitCode == 127) {
            ensureTermuxAvailable()
            if (!isTmuxInstalled()) return false
        }
        if (result.timedOut || result.errCode != -1) throw commandFailure("check managed Termux session", result)
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw commandFailure("check managed Termux session", result)
        }
    }

    private suspend fun setSessionOption(sessionId: String, option: String, value: String) {
        runTmuxChecked(
            "store managed Termux metadata",
            listOf(
                "set-option",
                "-t",
                TermuxSessionProtocol.tmuxSessionOptionTarget(sessionId),
                option,
                value,
            ),
        )
    }

    private suspend fun runTmux(arguments: List<String>): TermuxTransportResult =
        transport.executeProgram("tmux", arguments)

    private suspend fun runTmuxChecked(action: String, arguments: List<String>): TermuxTransportResult {
        val result = runTmux(arguments)
        if (!isSuccessful(result)) throw commandFailure(action, result)
        return result
    }

    private suspend fun runProgram(
        program: String,
        arguments: List<String>,
        stdin: String? = null,
    ): TermuxTransportResult = transport.executeProgram(program, arguments, stdin)

    private suspend fun runProgramChecked(
        action: String,
        program: String,
        arguments: List<String>,
        stdin: String? = null,
    ): TermuxTransportResult {
        val result = runProgram(program, arguments, stdin)
        if (!isSuccessful(result)) throw commandFailure(action, result)
        return result
    }

    private fun isSuccessful(result: TermuxTransportResult): Boolean =
        !result.timedOut && result.errCode == -1 && result.exitCode == 0

    private fun commandFailure(action: String, result: TermuxTransportResult): IllegalStateException {
        val detail = listOf(result.errorMessage, result.stderr, result.stdout)
            .firstOrNull(String::isNotBlank)?.trim()
            ?: "exitCode=${result.exitCode}, errCode=${result.errCode}"
        return IllegalStateException("Failed to $action: $detail")
    }

    private fun decodeMetadata(value: String): String = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrDefault("")

    private fun decodeOutputChunk(
        value: String,
        outputStart: Long,
        outputSize: Long,
        terminalState: Boolean,
    ): DecodedOutput {
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrDefault(ByteArray(0))
        if (bytes.isEmpty()) return DecodedOutput("", 0L)
        val isFinalChunk = terminalState && outputStart + bytes.size >= outputSize
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val maxTrim = minOf(3, bytes.size)
        for (trim in 0..maxTrim) {
            val length = bytes.size - trim
            val decoded = runCatching {
                decoder.reset()
                decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
            }.getOrNull() ?: continue
            if (trim == 0 || !isFinalChunk) return DecodedOutput(decoded, length.toLong())
            break
        }
        return DecodedOutput(String(bytes, Charsets.UTF_8), bytes.size.toLong())
    }

    // TermuxService clears $PREFIX/tmp when a RUN_COMMAND-backed service exits. Keep
    // reconnectable session state under Home so one-shot commands cannot remove it.
    private fun stateRoot(): String = "${transport.termuxHome}/.operit-unified-exec"

    private fun stateDirectory(sessionId: String): String = "${stateRoot()}/$sessionId"

    private fun tmuxTarget(sessionId: String): String = TermuxSessionProtocol.tmuxTarget(sessionId)

    private fun paneTarget(sessionId: String): String = TermuxSessionProtocol.tmuxPaneTarget(sessionId)

    private fun quote(value: String): String = TermuxSessionProtocol.shellQuote(value)

    private data class TmuxAvailability(val available: Boolean, val detail: String)

    private data class ExecSnapshot(
        val state: HostTermuxExecState,
        val rawState: String,
        val exitCode: Int?,
        val output: String,
        val cursor: Long,
        val outputSize: Long,
    ) {
        val hasMoreOutput: Boolean
            get() = cursor < outputSize
    }

    private data class DecodedOutput(val text: String, val consumedBytes: Long)

    private data class ExecMetadata(
        val sessionId: String,
        val sessionName: String,
        val workingDirectory: String,
        val startedAtEpochMs: Long,
    )

    private companion object {
        const val MAX_YIELD_MS = 300_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_OUTPUT_CHUNK_BYTES = 64 * 1024
        const val MAX_SCREEN_OUTPUT_BYTES = MAX_OUTPUT_CHUNK_BYTES
        const val TERMUX_PROBE_MARKER = "__OPERIT_TERMUX_READY__"
    }
}
