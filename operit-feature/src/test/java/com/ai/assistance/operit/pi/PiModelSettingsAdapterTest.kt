package com.ai.assistance.operit.pi

import com.wuxianpi.pi.PiModelStatus
import com.wuxianpi.pi.PiProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelSettingsAdapterTest {
    @Test
    fun `operit generic provider ids resolve to Pi provider ids`() {
        val status =
            PiModelStatus(
                providers =
                    listOf(
                        PiProviderStatus("openai", "OpenAI", true),
                        PiProviderStatus("anthropic", "Anthropic", true),
                        PiProviderStatus("google", "Google", true),
                    ),
                models = emptyList(),
                defaultModel = null,
                availabilityError = null,
            )
        assertEquals("openai", resolveNodeProviderId("OPENAI_RESPONSES_GENERIC", status))
        assertEquals("anthropic", resolveNodeProviderId("ANTHROPIC_GENERIC", status))
        assertEquals("google", resolveNodeProviderId("GEMINI_GENERIC", status))
    }

    @Test
    fun `normalization keeps direct Pi marketplace provider ids usable`() {
        assertTrue("openrouter" in providerCandidates("open-router"))
        assertTrue("deepseek" in providerCandidates("DEEPSEEK"))
    }
}
