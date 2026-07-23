package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.tools.TermuxExecResultData
import com.ai.assistance.operit.host.terminal.HostTermuxExecResult
import com.ai.assistance.operit.host.terminal.HostTermuxExecState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardTerminalCommandExecutorTest {
    @Test
    fun runningManagedTermuxCommandIsSuccessfulAndReturnsSession() {
        val result =
            map(
                HostTermuxExecResult(
                    state = HostTermuxExecState.RUNNING,
                    sessionId = "operit_v_t_1234567890abcdef12345678",
                    output = "building\n",
                    cursor = 9L,
                    persistent = true,
                )
            )

        assertTrue(result.success)
        assertNull(result.error)
        val content = result.result as TermuxExecResultData
        assertEquals("running", content.state)
        assertEquals("operit_v_t_1234567890abcdef12345678", content.sessionId)
        assertTrue(content.persistent)
    }

    @Test
    fun completedManagedTermuxCommandWithZeroExitIsSuccessful() {
        val result =
            map(
                HostTermuxExecResult(
                    state = HostTermuxExecState.COMPLETED,
                    output = "2\n",
                    exitCode = 0,
                )
            )

        assertTrue(result.success)
        assertNull(result.error)
        val content = result.result as TermuxExecResultData
        assertEquals("completed", content.state)
        assertEquals(0, content.exitCode)
        assertEquals("2\n", content.output)
    }

    @Test
    fun completedManagedTermuxCommandWithNonzeroExitIsFailure() {
        val result =
            map(
                HostTermuxExecResult(
                    state = HostTermuxExecState.COMPLETED,
                    output = "not found\n",
                    exitCode = 127,
                )
            )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("127"))
        val content = result.result as TermuxExecResultData
        assertEquals("completed", content.state)
        assertEquals(127, content.exitCode)
        assertTrue(content.toString().contains("Exit Code: 127"))
    }

    @Test
    fun setupRequiredAlwaysExposesBootstrapAndUnderlyingDetail() {
        val result =
            map(
                HostTermuxExecResult(
                    state = HostTermuxExecState.SETUP_REQUIRED,
                    error = "tmux executable was not found under PREFIX",
                    setupCommand = "pkg install -y tmux",
                    missingDependencies = listOf("tmux"),
                )
            )

        assertFalse(result.success)
        val topLevelError = result.error.orEmpty()
        assertTrue(topLevelError.contains("setup_required"))
        assertTrue(topLevelError.contains("missingDependencies=tmux"))
        assertTrue(topLevelError.contains("setupCommand=pkg install -y tmux"))
        assertTrue(topLevelError.contains("tmux executable was not found under PREFIX"))

        val content = result.result as TermuxExecResultData
        assertTrue(content.setupRequired)
        assertEquals(listOf("tmux"), content.missingDependencies)
        assertEquals("pkg install -y tmux", content.setupCommand)
        assertEquals("tmux executable was not found under PREFIX", content.error)
        val visibleContent = content.toString()
        assertTrue(visibleContent.contains("State: setup_required"))
        assertTrue(visibleContent.contains("Missing Dependencies: tmux"))
        assertTrue(visibleContent.contains("pkg install -y tmux"))
        assertTrue(visibleContent.contains("tmux executable was not found under PREFIX"))
    }

    private fun map(result: HostTermuxExecResult) =
        StandardTerminalCommandExecutor.buildManagedTermuxToolResult(
            toolName = "termux_exec_command",
            command = "test command",
            result = result,
        )
}
