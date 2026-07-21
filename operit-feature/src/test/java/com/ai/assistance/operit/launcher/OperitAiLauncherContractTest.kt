package com.ai.assistance.operit.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
