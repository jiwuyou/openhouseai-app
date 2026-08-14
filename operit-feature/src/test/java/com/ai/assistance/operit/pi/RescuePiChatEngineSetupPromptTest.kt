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
                "request_termux_run_command_permission",
                "configure_termux_external_apps",
                "prepare_persistent_termux",
                "start_wuxianpi_setup",
                "store_service_manager_connection",
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
        assertTrue(prompt.contains("SAF is not part of first installation"))
        assertTrue(prompt.contains("side-effect-free command"))
        assertTrue(prompt.contains("short-lived APK bundle download"))
        assertTrue(prompt.contains("OpenHouse loopback bridge"))
        assertTrue(prompt.contains("Ubuntu as the final independent phase"))
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
