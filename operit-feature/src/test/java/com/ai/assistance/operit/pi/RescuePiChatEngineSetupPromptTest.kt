package com.ai.assistance.operit.pi

import org.junit.Assert.assertTrue
import org.junit.Test

class RescuePiChatEngineSetupPromptTest {
    @Test
    fun promptUsesDeterministicSetupOrderAndExplicitBackends() {
        val prompt = RescuePiChatEngine.RESCUE_SYSTEM_PROMPT
        val orderedTools =
            listOf(
                "inspect_wuxianpi_setup",
                "prepare_runtime_host",
                "request_termux_home_access",
                "request_termux_run_command_permission",
                "prepare_persistent_termux",
                "start_wuxianpi_setup",
                "get_wuxianpi_setup_status",
            )

        var previousIndex = -1
        orderedTools.forEach { tool ->
            val index = prompt.indexOf(tool)
            assertTrue("$tool is missing or out of order", index > previousIndex)
            previousIndex = index
        }
        assertTrue(prompt.contains("Android"))
        assertTrue(prompt.contains("Termux"))
        assertTrue(prompt.contains("Ubuntu"))
        assertTrue(prompt.contains("not Rescue AI process memory"))
    }
}
