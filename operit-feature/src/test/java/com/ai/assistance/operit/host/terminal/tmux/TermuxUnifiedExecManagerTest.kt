package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTermuxExecState
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxUnifiedExecManagerTest {
    @Test
    fun missingTmuxReturnsSetupRequiredWithoutCreatingSession() = runBlocking {
        val transport = UnifiedExecFakeTransport().apply { tmuxInstalled = false }
        val manager = manager(transport)

        val result = manager.execute("printf ready", null, 0L, null)

        assertEquals(HostTermuxExecState.SETUP_REQUIRED, result.state)
        assertEquals(listOf("tmux"), result.missingDependencies)
        assertEquals("pkg install -y tmux", result.setupCommand)
        assertFalse(transport.sessionExists)
    }

    @Test
    fun unavailableTermuxReturnsFailureInsteadOfTmuxSetup() = runBlocking {
        val transport = UnifiedExecFakeTransport().apply { termuxAvailable = false }
        val manager = manager(transport)

        val result = manager.execute("printf ready", null, 0L, null)

        assertEquals(HostTermuxExecState.FAILED, result.state)
        assertNull(result.setupCommand)
        assertTrue(result.missingDependencies.isEmpty())
        assertTrue(result.error.contains("Termux package"))
        assertFalse(transport.sessionExists)
    }

    @Test
    fun shortCommandReturnsResultAndRemovesManagedState() = runBlocking {
        val transport = UnifiedExecFakeTransport().apply {
            completeOnStart = true
            completedOutput = "2\n"
            completedExitCode = 0
        }
        val manager = manager(transport)

        val result = manager.execute("expr 1 + 1", "\$HOME", 100L, "math")

        assertEquals(HostTermuxExecState.COMPLETED, result.state)
        assertEquals("2\n", result.output)
        assertEquals(0, result.exitCode)
        assertNull(result.sessionId)
        assertFalse(result.persistent)
        assertFalse(transport.sessionExists)
        assertFalse(transport.metadataExists)
    }

    @Test
    fun runningCommandSurvivesBackendDestroyAndCanReconnect() = runBlocking {
        val transport = UnifiedExecFakeTransport()
        val backend = TmuxHostTerminalBackend(transport)

        val started = backend.executeTermuxCommand("sleep 30", null, 0L, "repair")
        backend.sendInput(started.sessionId!!, hasInput = true, input = "c", control = "ctrl")
        backend.destroy()

        assertEquals(HostTermuxExecState.RUNNING, started.state)
        assertTrue(transport.keys.contains("C-c"))
        assertFalse(transport.literalInputs.contains("c"))
        assertTrue(transport.sessionExists)
        assertTrue(transport.writtenExecutableBodies.keys.any { path ->
            path.startsWith("${transport.termuxHome}/.operit-unified-exec/")
        })
        assertFalse(transport.writtenExecutableBodies.keys.any { path ->
            path.startsWith("${transport.termuxPrefix}/tmp/operit-unified-exec/")
        })
        val reopened = TmuxHostTerminalBackend(transport).listTermuxSessions(includeCompleted = false)
        assertEquals(listOf(started.sessionId), reopened.map { it.sessionId })
        assertEquals("repair", reopened.single().sessionName)
    }

    @Test
    fun cursorInputInterruptCompletionAndExplicitCloseAreManaged() = runBlocking {
        val transport = UnifiedExecFakeTransport().apply { output = "first\n" }
        val manager = manager(transport)
        val started = manager.execute("read value", null, 0L, "interactive")
        val id = started.sessionId!!

        transport.completeOnInterrupt = true
        val completed = manager.writeStdin(
            sessionId = id,
            chars = "answer",
            control = "interrupt",
            yieldTimeMs = 0L,
            afterCursor = started.cursor,
        )

        assertEquals(HostTermuxExecState.COMPLETED, completed.state)
        assertEquals("stopped\n", completed.output)
        assertEquals(130, completed.exitCode)
        assertTrue(transport.literalInputs.contains("answer"))
        assertTrue(transport.keys.contains("C-c"))
        assertFalse(transport.sessionExists)
        assertTrue(transport.metadataExists)

        manager.close(id)
        assertFalse(transport.metadataExists)
    }

    @Test
    fun missingTmuxSessionWithRunningStateBecomesLost() = runBlocking {
        val transport = UnifiedExecFakeTransport()
        val manager = manager(transport)
        val started = manager.execute("sleep 30", null, 0L, "lost-task")
        transport.sessionExists = false

        val sessions = manager.list(includeCompleted = true)

        assertEquals(started.sessionId, sessions.single().sessionId)
        assertEquals(HostTermuxExecState.LOST, sessions.single().state)
    }

    @Test
    fun largeUtf8OutputUsesBoundedByteCursorReads() = runBlocking {
        val expected = "a".repeat(65_535) + "界" + "tail\n"
        val transport = UnifiedExecFakeTransport().apply {
            completeOnStart = true
            completedOutput = expected
            completedExitCode = 0
        }
        val manager = manager(transport)

        val first = manager.execute("large-output", null, 0L, "large")

        assertEquals(HostTermuxExecState.COMPLETED, first.state)
        assertEquals("a".repeat(65_535), first.output)
        assertEquals(65_535L, first.cursor)
        assertNotNull(first.sessionId)
        assertTrue(first.persistent)
        assertFalse(transport.sessionExists)
        assertTrue(transport.metadataExists)

        val second = manager.writeStdin(
            sessionId = first.sessionId!!,
            chars = "",
            control = null,
            yieldTimeMs = 0L,
            afterCursor = first.cursor,
        )

        assertEquals(HostTermuxExecState.COMPLETED, second.state)
        assertEquals("界tail\n", second.output)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), second.cursor)
        assertEquals(expected, first.output + second.output)
        assertEquals(listOf(0L, 65_535L), transport.snapshotReadOffsets)
        assertTrue(transport.snapshotReadLimits.all { it <= 64 * 1024 })
    }

    @Test
    fun monitoringFailureAfterCreationPreservesRecoverableSession() = runBlocking {
        val transport = UnifiedExecFakeTransport().apply { failNextSnapshot = true }
        val manager = manager(transport)

        val failed = manager.execute("sleep 30", null, 0L, "recoverable")

        assertEquals(HostTermuxExecState.FAILED, failed.state)
        assertEquals(SESSION_ID, failed.sessionId)
        assertEquals("recoverable", failed.sessionName)
        assertTrue(failed.persistent)
        assertTrue(transport.sessionExists)
        assertTrue(transport.metadataExists)

        val recovered = manager.writeStdin(SESSION_ID, "", null, 0L, 0L)
        assertEquals(HostTermuxExecState.RUNNING, recovered.state)
        assertEquals(SESSION_ID, recovered.sessionId)
    }

    @Test
    fun completionIsPublishedOnlyAfterPipeWriterFinalizesOutput() = runBlocking {
        val sleepCalls = mutableListOf<Long>()
        val transport = UnifiedExecFakeTransport()
        val manager = manager(transport, sleeper = { sleepCalls += it })
        val started = manager.execute("printf head; printf tail", null, 0L, "flush")

        transport.output = "head"
        transport.state = "finishing"
        transport.exitCode = 0
        val finishing = manager.writeStdin(SESSION_ID, "", null, 0L, started.cursor)

        assertEquals(HostTermuxExecState.RUNNING, finishing.state)
        assertEquals("head", finishing.output)
        assertEquals(4L, finishing.cursor)

        transport.output = "headtail"
        transport.state = "completed"
        transport.sessionExists = false
        val completed = manager.writeStdin(SESSION_ID, "", null, 0L, finishing.cursor)

        assertEquals(HostTermuxExecState.COMPLETED, completed.state)
        assertEquals("tail", completed.output)
        assertEquals(8L, completed.cursor)
        assertTrue(sleepCalls.isEmpty())
        assertTrue(transport.tmuxCommands.any { command ->
            command ==
                listOf(
                    "set-option",
                    "-w",
                    "-t",
                    "=$SESSION_ID:0.0",
                    "remain-on-exit",
                    "off",
                )
        })
        assertTrue(transport.writtenExecutableBodies.values.any { body ->
            body.contains("output-size") && body.indexOf("output-size") < body.indexOf("completed")
        })
    }

    private fun manager(
        transport: UnifiedExecFakeTransport,
        sleeper: suspend (Long) -> Unit = {},
    ) =
        TermuxUnifiedExecManager(
            transport = transport,
            sessionIdFactory = { SESSION_ID },
            sleeper = sleeper,
            nanoTime = { Long.MAX_VALUE },
            currentTimeMillis = { 1234L },
        )

    private class UnifiedExecFakeTransport : TermuxSessionTransport {
        override val termuxPrefix = "/data/data/com.termux/files/usr"
        var termuxAvailable = true
        var tmuxInstalled = true
        var sessionId = SESSION_ID
        var sessionExists = false
        var metadataExists = false
        var state = "starting"
        var output = ""
        var exitCode: Int? = null
        var sessionName = ""
        var workingDirectory = termuxHome
        var startedAt = 0L
        var completeOnStart = false
        var completedOutput = ""
        var completedExitCode = 0
        var completeOnInterrupt = false
        var failNextSnapshot = false
        val literalInputs = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val snapshotReadOffsets = mutableListOf<Long>()
        val snapshotReadLimits = mutableListOf<Int>()
        val writtenExecutableBodies = mutableMapOf<String, String>()
        val tmuxCommands = mutableListOf<List<String>>()

        override suspend fun executeProgram(
            program: String,
            arguments: List<String>,
            stdin: String?,
            timeoutMs: Long,
        ): TermuxTransportResult = when (program) {
            "tmux" -> tmux(arguments)
            "bash" -> bash(arguments, stdin)
            "touch" -> {
                state = if (completeOnStart) "completed" else "running"
                if (completeOnStart) {
                    output = completedOutput
                    exitCode = completedExitCode
                }
                success()
            }
            "rm" -> {
                if (arguments.contains("-rf")) metadataExists = false
                success()
            }
            else -> error("Unexpected program: $program $arguments")
        }

        private fun tmux(arguments: List<String>): TermuxTransportResult {
            tmuxCommands += arguments
            return when (arguments.first()) {
            "-V" -> if (tmuxInstalled) success("tmux 3.4\n") else failure(127, "tmux unavailable")
            "new-session" -> {
                sessionId = arguments[arguments.indexOf("-s") + 1]
                sessionExists = true
                workingDirectory = arguments[arguments.indexOf("-c") + 1]
                success()
            }
            "set-option" -> {
                val target = arguments.getOrNull(arguments.indexOf("-t") + 1).orEmpty()
                if (!arguments.contains("-w") && !target.endsWith(':')) {
                    failure(1, "no such session: $target")
                } else {
                    success()
                }
            }
            "pipe-pane" -> success()
            "has-session" -> if (sessionExists) success() else failure(1)
            "kill-session" -> {
                sessionExists = false
                success()
            }
            "send-keys" -> {
                if (arguments.contains("-l")) {
                    literalInputs += arguments.last()
                } else {
                    keys += arguments.last()
                    if (arguments.last() == "C-c" && completeOnInterrupt) {
                        output += "stopped\n"
                        exitCode = 130
                        state = "completed"
                    }
                }
                success()
            }
            "display-message" ->
                if (arguments.last() == "#{pane_dead}") success("0\n") else success("40\t120\n")
            "capture-pane" -> success(output)
            else -> error("Unexpected tmux command: $arguments")
            }
        }

        private fun bash(arguments: List<String>, stdin: String?): TermuxTransportResult {
            val script = arguments.getOrNull(arguments.indexOf("-lc") + 1).orEmpty()
            if (script.contains("__OPERIT_TERMUX_READY__")) {
                return if (termuxAvailable) {
                    success("__OPERIT_TERMUX_READY__\n")
                } else {
                    transportFailure(127, "Termux package com.termux is not installed")
                }
            }
            if (script.contains("OPERIT_TMUX_BINARY_PROBE")) {
                return if (tmuxInstalled) success() else failure(1, "tmux is not installed")
            }
            if (script.startsWith("cat >") && stdin != null) {
                val path = Regex("cat > '([^']+)' && chmod").find(script)?.groupValues?.get(1).orEmpty()
                writtenExecutableBodies[path] = stdin
                return success()
            }
            if (script.contains("session-name") && script.contains("output.log")) {
                metadataExists = true
                sessionName = Regex("printf '%s' '([^']*)' > '[^']*/session-name'")
                    .find(script)?.groupValues?.get(1).orEmpty()
                startedAt = Regex("printf '%s' '([0-9]+)' > '[^']*/started-at'")
                    .find(script)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                state = "starting"
                return success()
            }
            if (script.contains("for dir in")) {
                if (!metadataExists) return success()
                return success(
                    listOf(
                        sessionId,
                        encode(sessionName),
                        encode(workingDirectory),
                        startedAt.toString(),
                    ).joinToString("\t", postfix = "\n"),
                )
            }
            if (script.contains("OPERIT_SNAPSHOT_V2")) {
                if (failNextSnapshot) {
                    failNextSnapshot = false
                    return transportFailure(1, "temporary RUN_COMMAND callback failure")
                }
                if (!metadataExists) return failure(44)
                val start = arguments.getOrNull(arguments.size - 2)?.toLongOrNull() ?: 0L
                val limit = arguments.lastOrNull()?.toIntOrNull() ?: 0
                val bytes = output.toByteArray(Charsets.UTF_8)
                val boundedStart = start.coerceIn(0L, bytes.size.toLong()).toInt()
                val end = (boundedStart + limit).coerceAtMost(bytes.size)
                val chunk = bytes.copyOfRange(boundedStart, end)
                snapshotReadOffsets += boundedStart.toLong()
                snapshotReadLimits += limit
                return success(
                    "$state\n${exitCode ?: ""}\n${bytes.size}\n$boundedStart\n${encode(chunk)}\n",
                )
            }
            if (script.contains("lost") && script.contains("mv -f")) {
                state = "lost"
                return success()
            }
            return success()
        }

        private fun encode(value: String): String = encode(value.toByteArray(Charsets.UTF_8))

        private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

        private fun success(stdout: String = "") = TermuxTransportResult(stdout = stdout)

        private fun failure(exitCode: Int, message: String = "") =
            TermuxTransportResult(exitCode = exitCode, errorMessage = message)

        private fun transportFailure(exitCode: Int, message: String) =
            TermuxTransportResult(exitCode = exitCode, errCode = 1, errorMessage = message)
    }

    private companion object {
        const val SESSION_ID = "operit_v_t_0123456789abcdef01234567"
    }
}
