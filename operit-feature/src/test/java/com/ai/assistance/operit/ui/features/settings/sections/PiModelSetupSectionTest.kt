package com.ai.assistance.operit.ui.features.settings.sections

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.model.withoutAndroidCloudCredentialAuthority
import com.wuxianpi.pi.PiDiscoveredModel
import com.wuxianpi.pi.PiModelApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PiModelSetupSectionTest {
    @Test
    fun `advanced headers parse into Runtime draft`() {
        assertEquals(
            mapOf("X-Client" to "Operit", "X-Mode" to "advanced"),
            parsePiHeaders("{\"X-Client\":\"Operit\",\"X-Mode\":\"advanced\"}"),
        )
    }

    @Test
    fun `invalid headers fail before apply`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePiHeaders("not-json")
        }
    }

    @Test
    fun `auto source follows each selected fetched model without changing user selection`() {
        val claude = PiDiscoveredModel("claude", sources = listOf(PiModelApi.ANTHROPIC_MESSAGES))
        val gemini = PiDiscoveredModel("gemini", sources = listOf(PiModelApi.GOOGLE_GENERATIVE_AI))

        assertEquals(
            PiModelApi.ANTHROPIC_MESSAGES,
            resolvedSourceForSelectedModel(PiModelApi.AUTO, claude),
        )
        assertEquals(
            PiModelApi.GOOGLE_GENERATIVE_AI,
            resolvedSourceForSelectedModel(PiModelApi.AUTO, gemini),
        )
        assertEquals(
            PiModelApi.AUTO,
            PiModelApi.AUTO,
        )
    }

    @Test
    fun `manual model edit clears source and auto blocks test or apply`() {
        assertThrows(IllegalArgumentException::class.java) {
            effectiveApiForSelectedModel(PiModelApi.AUTO, null)
        }
        assertEquals(
            PiModelApi.OPENAI_RESPONSES,
            effectiveApiForSelectedModel(PiModelApi.OPENAI_RESPONSES, null),
        )
    }

    @Test
    fun `Pi config hides Android key pool and credential cleanup preserves binding`() {
        val config = ModelConfigData(
            id = "pi",
            name = "Pi",
            apiKey = "android-key",
            apiEndpoint = "https://android.invalid",
            modelName = "old-model",
            apiProviderType = ApiProviderType.OTHER,
            apiProviderTypeId = "PI_RUNTIME",
            piModelBinding = PiModelBinding("openai", "gpt-5"),
            useMultipleApiKeys = true,
        )

        assertFalse(showsAndroidCredentialPool(config))
        val cleaned = config.withoutAndroidCloudCredentialAuthority()
        assertEquals("", cleaned.apiKey)
        assertEquals("", cleaned.apiEndpoint)
        assertEquals(emptyList<Any>(), cleaned.apiKeyPool)
        assertEquals(config.piModelBinding, cleaned.piModelBinding)
    }
}
