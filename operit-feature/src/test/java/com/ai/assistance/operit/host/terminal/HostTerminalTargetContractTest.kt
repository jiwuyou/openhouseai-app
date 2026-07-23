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
