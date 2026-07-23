package com.ai.assistance.operit.host.terminal

import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.core.config.SystemToolPromptsInternal
import com.ai.assistance.operit.host.OperitHostCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTerminalTargetContractTest {
    @Test
    fun commandTargetsAreExplicitAndSessionsDefaultToUbuntu() {
        assertEquals(
            listOf("android", "termux", "ubuntu"),
            HostTerminalTarget.values().map { it.wireName },
        )
        assertEquals(HostTerminalTarget.UBUNTU, HostTerminalTarget.DEFAULT)
    }

    @Test
    fun exposedHostCategoryUsesExplicitToolNames() {
        val names = SystemToolPrompts.getHostTerminalToolCategoryEn().tools.map { it.name }
        assertTrue("execute_android_command" in names)
        assertTrue("execute_termux_command" in names)
        assertTrue("termux_exec_command" in names)
        assertTrue("termux_write_stdin" in names)
        assertTrue("list_termux_exec_sessions" in names)
        assertTrue("create_terminal_session" in names)
        assertFalse("execute_shell" in names)
        assertFalse(
            SystemToolPromptsInternal.internalToolCategoriesEn
                .flatMap { it.tools }
                .any { it.name == "execute_shell" },
        )
        assertFalse(
            SystemToolPromptsInternal.internalToolCategoriesCn
                .flatMap { it.tools }
                .any { it.name == "execute_shell" },
        )
    }

    @Test
    fun managedTermuxContractKeepsRawCommandAndUbuntuSessionSemanticsSeparate() {
        val tools = SystemToolPrompts.getHostTerminalToolCategoryEn().tools.associateBy { it.name }
        val raw = requireNotNull(tools["execute_termux_command"])
        val managed = requireNotNull(tools["termux_exec_command"])
        val ubuntu = requireNotNull(tools["create_terminal_session"])

        assertTrue(raw.description.contains("unrestricted"))
        assertTrue(raw.description.contains("does not terminate"))
        assertTrue(managed.description.contains("setup_required"))
        assertTrue(managed.description.contains("pkg install -y tmux"))
        assertTrue(managed.description.contains("Never fall back"))
        assertTrue(ubuntu.description.contains("Ubuntu"))
    }

    @Test
    fun transportErrorsCannotBeReportedAsSuccessfulCommands() {
        val result =
            OperitHostCommandResult(
                command = "true",
                exitCode = 0,
                stdout = "",
                stderr = "",
                error = "",
                timedOut = false,
                durationMs = 1,
                transportErrorCode = 1,
                transportErrorMessage = "RUN_COMMAND permission denied",
            )
        assertFalse(result.isSuccess)
    }
}
