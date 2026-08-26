package com.ai.assistance.operit.launcher

import android.content.Intent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitAiLauncherContractTest {
    @Test
    fun `advanced mode prefers AionUI and keeps the bundled runtime fallback`() {
        assertArrayEquals(
            arrayOf("http://127.0.0.1:25808/", "http://127.0.0.1:8765/"),
            OperitAiLauncher.advancedUrls(),
        )
        assertEquals(
            "http://127.0.0.1:8765/v1/ui/metadata",
            OperitAiLauncher.ADVANCED_UI_METADATA_URL,
        )
    }

    @Test
    fun `repair mode reorders within the host task without clearing it`() {
        val flags = OperitAiLauncher.REPAIR_INTENT_FLAGS

        assertTrue(flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
