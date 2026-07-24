package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelListFetcherTest {
    @Test
    fun `openai operation endpoint resolves to models endpoint`() {
        assertEquals(
            "https://example.test/v1/models",
            ModelListFetcher.getModelsListUrl(
                "https://example.test/v1/chat/completions",
                ApiProviderType.OPENAI_GENERIC,
            ),
        )
    }

    @Test
    fun `custom gemini endpoint stays on custom host`() {
        assertEquals(
            "https://gateway.example/v1beta/models",
            ModelListFetcher.getModelsListUrl(
                "https://gateway.example/v1beta/models/gemini-pro:generateContent",
                ApiProviderType.GEMINI_GENERIC,
            ),
        )
    }

    @Test
    fun `custom anthropic endpoint resolves to models endpoint`() {
        assertEquals(
            "https://gateway.example/v1/models",
            ModelListFetcher.getModelsListUrl(
                "https://gateway.example/v1/messages",
                ApiProviderType.ANTHROPIC_GENERIC,
            ),
        )
    }
}
