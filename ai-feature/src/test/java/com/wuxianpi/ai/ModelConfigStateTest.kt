package com.wuxianpi.ai

import com.wuxianpi.pi.PiAvailableModel
import com.wuxianpi.pi.PiModelRef
import com.wuxianpi.pi.PiModelStatus
import com.wuxianpi.pi.PiProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigStateTest {
    private val provider = PiProviderStatus("dynamic-provider", "Dynamic Provider", authenticated = true)
    private val model = PiAvailableModel(
        provider = provider.id,
        id = "dynamic-model",
        name = "Dynamic Model",
        available = true,
        reasoning = true,
        input = listOf("text"),
        contextWindow = null,
        maxTokens = null,
    )

    @Test
    fun `missing usable default automatically opens configuration`() {
        val status = PiModelStatus(listOf(provider.copy(authenticated = false)), listOf(model), null, null)
        val state = ModelConfigState().withStatus(status, openIfMissing = true)
        assertTrue(state.isOpen)
        assertTrue(state.promptRequired)
        assertEquals("dynamic-provider", state.selectedProvider)
        assertEquals("dynamic-model", state.selectedModelId)
    }

    @Test
    fun `usable dynamic default does not force dialog`() {
        val status = PiModelStatus(listOf(provider), listOf(model), PiModelRef(provider.id, model.id), null)
        val state = ModelConfigState().withStatus(status, openIfMissing = true)
        assertFalse(state.isOpen)
        assertTrue(state.hasUsableDefault)
    }

    @Test
    fun `successful login clears transient api key`() {
        val state = ModelConfigState(apiKey = "secret-value")
            .complete("Signed in", clearSecret = true)
        assertEquals("", state.apiKey)
        assertEquals(ModelConfigPhase.CONFIGURED, state.phase)
    }

    @Test
    fun `provider selection uses only server supplied models`() {
        val second = model.copy(provider = "other", id = "other-model")
        val state = ModelConfigState(providers = listOf(provider), models = listOf(model, second))
            .selectProvider(provider.id)
        assertEquals(listOf(model), state.selectedModels)
    }
}
