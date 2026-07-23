package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSessionManagerTest {
    @Test
    fun createsUbuntuTmuxSessionAndExecutesEncodedCommand() = runBlocking {
        val transport = FakeTransport()
        val manager =
            TermuxSessionManager(
                transport = transport,
                tokenFactory = { "fixedtoken" },
                sleeper = {},
            )

        assertTrue(manager.initialize())
        val created = manager.createOrGet("rescue_tools_session")
        val command = "cd '\$HOME/work dir'; printf '%s\\n' done; false"
        val chunks = mutableListOf<String>()
        val execution = manager.execute(created.sessionId, command, 5_000L, chunks::add)

        assertTrue(created.isNewSession)
        assertEquals(HostTerminalTarget.UBUNTU, created.target)
        assertEquals("done", execution.output)
        assertEquals(listOf("done"), chunks)
        assertEquals(7, execution.exitCode)
        assertFalse(execution.timedOut)

        val createCall =
            transport.calls.first { it.program == "tmux" && it.arguments.first() == "new-session" }
        assertTrue(createCall.arguments.last().contains("proot-distro"))
        assertTrue(createCall.arguments.last().contains("ubuntu"))
        val payload =
            transport.calls.first { it.program == "tmux" && it.arguments.first() == "load-buffer" }
                .stdin.orEmpty()
        assertFalse(payload.contains(command))
        assertTrue(payload.contains(TermuxSessionProtocol.base64(command)))
    }

    @Test
    fun managerReusesSessionAndSupportsScreenInputAndClose() = runBlocking {
        val transport = FakeTransport()
        val first = TermuxSessionManager(transport, sleeper = {})
        val created = first.createOrGet("persistent_session")
        val second = TermuxSessionManager(transport, sleeper = {})
        val reopened = second.createOrGet("persistent_session")

        assertTrue(created.isNewSession)
        assertFalse(reopened.isNewSession)
        assertEquals(created.sessionId, reopened.sessionId)

        assertEquals(1, second.input(created.sessionId, true, "c", "ctrl"))
        val screen = second.screen(created.sessionId)
        assertEquals(40, screen.rows)
        assertEquals(120, screen.cols)
        assertTrue(screen.content.contains("__OPERIT_READY__"))

        second.close(created.sessionId)
        assertFalse(transport.sessionExists)
    }

    @Test
    fun timeoutRebuildsSessionWhenInterruptDoesNotStopCommand() = runBlocking {
        var nowNanos = 0L
        val transport = FakeTransport().apply { completeOnSubmit = false }
        val manager =
            TermuxSessionManager(
                transport = transport,
                tokenFactory = { "timeouttoken" },
                sleeper = { millis -> nowNanos += millis * 1_000_000L },
                nanoTime = { nowNanos },
            )
        val created = manager.createOrGet("timeout_session")

        val timedOut = manager.execute(created.sessionId, "sleep 999", 1_000L)

        assertTrue(timedOut.timedOut)
        assertEquals(-1, timedOut.exitCode)
        assertEquals(2, transport.createCount)
        assertEquals(1, transport.killCount)
        assertTrue(transport.sessionExists)

        transport.completeOnSubmit = true
        val next = manager.execute(created.sessionId, "printf done", 5_000L)
        assertFalse(next.timedOut)
        assertEquals("done", next.output)
    }

    @Test
    fun backendDestroyClosesVisibleAndHiddenTmuxSessions() = runBlocking {
        val visibleTransport = FakeTransport()
        val visibleBackend = TmuxHostTerminalBackend(visibleTransport)
        visibleBackend.createSession("visible_cleanup")
        visibleBackend.destroy()
        assertFalse(visibleTransport.sessionExists)
        assertEquals(1, visibleTransport.killCount)

        val hiddenTransport = FakeTransport()
        val hiddenBackend = TmuxHostTerminalBackend(hiddenTransport)
        hiddenBackend.executeHiddenCommand("printf hidden", "hidden_cleanup", 5_000L)
        hiddenBackend.destroy()
        assertFalse(hiddenTransport.sessionExists)
        assertEquals(1, hiddenTransport.killCount)
    }

    private data class Call(
        val program: String,
        val arguments: List<String>,
        val stdin: String?,
    )

    private class FakeTransport : TermuxSessionTransport {
        override val termuxPrefix: String = "/data/data/com.termux/files/usr"
        val calls = mutableListOf<Call>()
        val options = mutableMapOf<String, String>()
        var sessionExists = false
        var completeOnSubmit = true
        var createCount = 0
        var killCount = 0
        private var paneCapture = ""
        private var commandCapture = ""
        private var loadedPayload = ""

        override suspend fun executeProgram(
            program: String,
            arguments: List<String>,
            stdin: String?,
            timeoutMs: Long,
        ): TermuxTransportResult {
            calls += Call(program, arguments, stdin)
            if (program == "cat") return success(stdout = commandCapture)
            if (program == "touch") {
                commandCapture = ""
                return success()
            }
            if (program == "rm") return success()
            check(program == "tmux")
            return when (arguments.first()) {
                "-V" -> success(stdout = "tmux 3.4\n")
                "has-session" -> if (sessionExists) success() else failure(1)
                "new-session" -> {
                    sessionExists = true
                    createCount += 1
                    paneCapture = "__OPERIT_READY__\n"
                    success()
                }
                "set-option" -> {
                    if (!arguments.contains("-w")) options[arguments[arguments.size - 2]] = arguments.last()
                    success()
                }
                "show-options" -> success(stdout = options.getValue(arguments.last()) + "\n")
                "capture-pane" -> success(stdout = paneCapture)
                "pipe-pane" -> success()
                "load-buffer" -> {
                    loadedPayload = stdin.orEmpty()
                    success()
                }
                "paste-buffer" -> success()
                "send-keys" -> {
                    if (
                        arguments.last() == "Enter" &&
                            loadedPayload.isNotEmpty() &&
                            completeOnSubmit
                    ) {
                        val token =
                            Regex("__OPERIT_BEGIN_([A-Za-z0-9]+)__")
                                .find(loadedPayload)
                                ?.groupValues
                                ?.get(1)
                                ?: error("Missing command token")
                        commandCapture =
                            "${TermuxSessionProtocol.beginMarker(token)}\r\ndone\r\n" +
                                "${TermuxSessionProtocol.endMarker(token)}:7\r\n"
                    }
                    success()
                }
                "kill-session" -> {
                    sessionExists = false
                    killCount += 1
                    success()
                }
                "display-message" -> success(stdout = "40\t120\n")
                else -> error("Unexpected tmux command: $arguments")
            }
        }

        private fun success(stdout: String = "") =
            TermuxTransportResult(stdout = stdout, exitCode = 0)

        private fun failure(exitCode: Int) = TermuxTransportResult(exitCode = exitCode)
    }
}
