package com.ai.assistance.operit.rescue.pi

import com.ai.assistance.operit.data.model.ApiKeyInfo
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.ApiPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueDeepSeekQuickSetupTest {
    @Test
    fun existingDeepSeekConfigOnlyReplacesCredentialFields() {
        val current = ModelConfigData(
            id = "default",
            name = "DeepSeek",
            apiEndpoint = "https://example.test/custom",
            modelName = "deepseek-custom",
            apiProviderType = ApiProviderType.DEEPSEEK,
            apiProviderTypeId = ApiProviderType.DEEPSEEK.name,
            useMultipleApiKeys = true,
            apiKeyPool = listOf(ApiKeyInfo(id = "old-id", key = "old")),
        )

        val updated = current.withRescueDeepSeekApiKey("  new-key  ")

        assertEquals("new-key", updated.apiKey)
        assertEquals(current.apiEndpoint, updated.apiEndpoint)
        assertEquals(current.modelName, updated.modelName)
        assertFalse(updated.useMultipleApiKeys)
        assertTrue(updated.apiKeyPool.isEmpty())
    }

    @Test
    fun otherProviderBecomesRunnableDeepSeekDefault() {
        val current = ModelConfigData(
            id = "default",
            name = "Empty",
            apiProviderType = ApiProviderType.OPENAI_GENERIC,
            apiProviderTypeId = ApiProviderType.OPENAI_GENERIC.name,
        )

        val updated = current.withRescueDeepSeekApiKey("new-key")

        assertEquals(ApiProviderType.DEEPSEEK, updated.apiProviderType)
        assertEquals(ApiPreferences.DEFAULT_API_ENDPOINT, updated.apiEndpoint)
        assertEquals(ApiPreferences.DEFAULT_MODEL_NAME, updated.modelName)
        assertEquals("new-key", updated.apiKey)
    }
}
