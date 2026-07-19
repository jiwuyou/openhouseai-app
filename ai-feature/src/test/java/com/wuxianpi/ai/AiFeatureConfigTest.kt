package com.wuxianpi.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AiFeatureConfigTest {
    @Test
    fun `external mode has no bundled credentials and therefore uses pairing`() {
        val config = AiFeatureConfig.externalTermux()
        assertEquals(RuntimeMode.EXTERNAL_TERMUX, config.runtimeMode)
        assertNull(config.bundledRuntime)
    }

    @Test
    fun `bundled mode accepts host loopback credentials`() {
        val config = AiFeatureConfig.bundledTermux(
            "http://127.0.0.1:8765/",
            "12345678901234567890123456789012",
            "all-in-one-host",
        )
        assertEquals(RuntimeMode.BUNDLED_TERMUX, config.runtimeMode)
        assertEquals("all-in-one-host", config.bundledRuntime?.clientId)
    }

    @Test
    fun `bundled mode rejects a remote gateway`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiFeatureConfig.bundledTermux(
                "https://example.com/",
                "12345678901234567890123456789012",
                "host",
            )
        }
    }
}
