package com.ai.assistance.operit.pi

import com.ai.assistance.operit.rescue.plugins.selectRescuePluginAssistantContexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                "configure_termux_external_apps",
                "termux-reload-settings",
                "verify_termux_run_command",
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
        assertTrue(prompt.contains("termuxHomeEnvironment=repo:termux-home"))
        assertTrue(prompt.contains("All-in-One host keeps its direct file behavior"))
        assertTrue(prompt.contains("Never call verify_termux_run_command before"))
        assertTrue(prompt.contains("default every Termux command, short or long, to termux_exec_command"))
    }

    @Test
    fun appendsOnlySelectedShortPluginContextsToTheSessionPrompt() {
        val selected =
            selectRescuePluginAssistantContexts(
                candidates =
                    listOf(
                        "  hint  ",
                        "hint",
                        "x".repeat(33),
                        "service",
                    ),
                maxBytesPerContext = 32,
                maxTotalBytes = 16,
            )
        assertEquals(listOf("hint", "service"), selected)

        val prompt = RescuePiChatEngine.composeSystemPrompt(selected.joinToString("\n\n"))
        assertTrue(prompt.startsWith(RescuePiChatEngine.RESCUE_SYSTEM_PROMPT))
        assertTrue(prompt.contains("service"))
        assertFalse(prompt.contains("document body that was never selected"))

        val turnMessage = RescuePiChatEngine.composeTurnMessage("hello", "current time: now")
        assertTrue(turnMessage.startsWith("<assistant_context>"))
        assertTrue(turnMessage.endsWith("hello"))
        assertEquals("hello", RescuePiChatEngine.composeTurnMessage("hello", ""))
    }

    @Test
    fun instructionSelectionDeduplicatesAndHonorsTotalUtf8Bytes() {
        val selected =
            selectRescuePluginAssistantContexts(
                candidates =
                    listOf(
                        "你好",
                        " 你好 ",
                        "abc",
                    ),
                maxBytesPerContext = 8,
                maxTotalBytes = 8,
            )

        assertEquals(listOf("你好"), selected)
    }
}
