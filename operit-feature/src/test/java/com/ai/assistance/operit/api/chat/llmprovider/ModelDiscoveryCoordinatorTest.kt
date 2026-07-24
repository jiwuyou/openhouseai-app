package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDiscoveryCoordinatorTest {
    @Test
    fun `selects chat protocol when only chat invocation succeeds`() = runBlocking {
        val result =
            ModelDiscoveryCoordinator.discoverWith { providerType ->
                if (providerType == ApiProviderType.OPENAI_GENERIC) {
                    Result.success(listOf(ModelOption("gpt-chat", "gpt-chat")))
                } else {
                    Result.failure(IllegalStateException("unsupported"))
                }
            }.getOrThrow()

        assertEquals(ApiProviderType.OPENAI_GENERIC, result.providerType)
        assertEquals(listOf("gpt-chat"), result.models.map { it.id })
    }

    @Test
    fun `selects responses protocol when chat invocation fails`() = runBlocking {
        val result =
            ModelDiscoveryCoordinator.discoverWith { providerType ->
                if (providerType == ApiProviderType.OPENAI_RESPONSES_GENERIC) {
                    Result.success(listOf(ModelOption("gpt-responses", "gpt-responses")))
                } else {
                    Result.failure(IllegalStateException("unsupported"))
                }
            }.getOrThrow()

        assertEquals(ApiProviderType.OPENAI_RESPONSES_GENERIC, result.providerType)
        assertEquals(listOf("gpt-responses"), result.models.map { it.id })
    }

    @Test
    fun `probe candidates skip non chat models even when they are listed first`() {
        val candidates =
            ModelDiscoveryCoordinator.selectProbeModelIds(
                manualModelNames = "",
                fetchedModels =
                    listOf(
                        ModelOption("text-embedding-3-large", "Embedding"),
                        ModelOption("gpt-image-1", "Image"),
                        ModelOption("whisper-1", "Audio"),
                        ModelOption("qwen3-reranker", "Reranker"),
                        ModelOption("vendor-unknown", "Unknown"),
                        ModelOption("gpt-4o-mini", "GPT"),
                        ModelOption("claude-3-5-sonnet", "Claude"),
                    ),
            )

        assertEquals(setOf("gpt-4o-mini", "claude-3-5-sonnet"), candidates.take(2).toSet())
        assertEquals("vendor-unknown", candidates.last())
    }

    @Test
    fun `manually entered models have priority over fetched candidates`() {
        val candidates =
            ModelDiscoveryCoordinator.selectProbeModelIds(
                manualModelNames = "private-chat, gpt-4o",
                fetchedModels =
                    listOf(
                        ModelOption("claude-3-5-sonnet", "Claude"),
                        ModelOption("private-chat", "Private"),
                        ModelOption("gemini-2-5-pro", "Gemini"),
                    ),
            )

        assertEquals(
            listOf("private-chat", "gpt-4o", "claude-3-5-sonnet", "gemini-2-5-pro"),
            candidates,
        )
    }
}
