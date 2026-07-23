package com.ai.assistance.operit.pi

import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.wuxianpi.pi.PiDiscoveredModel
import com.wuxianpi.pi.PiModelApi
import com.wuxianpi.pi.PiModelApplyRequest
import com.wuxianpi.pi.PiModelCredentialMutation
import com.wuxianpi.pi.PiModelDraftResult
import com.wuxianpi.pi.PiModelProviderConfig
import com.wuxianpi.pi.PiModelProviderDraft
import com.wuxianpi.pi.PiModelSetupConfig
import com.wuxianpi.pi.PiModelSetupState
import com.wuxianpi.pi.WuxianPiModelApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelSetupRepositoryTest {
    @Test
    fun `fetch and test submit unsaved draft`() = runBlocking {
        val gateway = FakeGateway()
        val repository = PiModelSetupRepository(gateway)
        val draft = PiModelEditorDraft(
            providerId = "custom",
            baseUrl = "https://example.test/v1",
            api = PiModelApi.OPENAI_COMPLETIONS,
            apiKey = "secret",
            headers = mapOf("X-Test" to "yes"),
            modelId = "model-a",
        )

        assertEquals("revision-1", repository.setup().revision)
        repository.fetch(draft)
        repository.test(draft)

        assertEquals("https://example.test/v1", gateway.fetchDraft?.baseUrl)
        assertEquals(PiModelApi.OPENAI_COMPLETIONS, gateway.testDraft?.api)
        assertEquals("yes", gateway.testDraft?.headers?.get("X-Test"))
        assertEquals("model-a", gateway.testModelId)
        assertEquals("[REDACTED]", gateway.testDraft?.apiKey.toString())
    }

    @Test
    fun `apply keeps named binding local and does not change global default unless explicit`() = runBlocking {
        val gateway = FakeGateway()
        val repository = PiModelSetupRepository(gateway)
        val outcome = repository.apply(
            setup = emptySetup(),
            draft = PiModelEditorDraft(
                providerId = "deepseek",
                baseUrl = "https://api.deepseek.com",
                api = PiModelApi.OPENAI_COMPLETIONS,
                apiKey = "key",
                modelId = "deepseek-chat",
            ),
            setGlobalDefault = false,
        )

        assertEquals(PiModelBinding("deepseek", "deepseek-chat"), outcome.binding)
        val request = requireNotNull(gateway.applyRequest)
        assertFalse(request.setGlobalDefault)
        assertEquals(null, request.defaultModel)
        assertEquals("https://api.deepseek.com", request.config.providers["deepseek"]?.baseUrl)
        assertTrue(request.changes.single().credential is PiModelCredentialMutation.Set)
    }

    @Test
    fun `two Android configs can retain independent Pi bindings`() {
        val coding = PiModelBinding("anthropic", "claude-sonnet")
        val daily = PiModelBinding("deepseek", "deepseek-chat")
        assertTrue(coding != daily)
        assertEquals("claude-sonnet", coding.modelId)
        assertEquals("deepseek-chat", daily.modelId)
    }

    @Test
    fun `new Pi binding does not persist key or base URL as Android cloud authority`() {
        val config = ModelConfigData(
            id = "coding",
            name = "Coding",
            apiProviderType = ApiProviderType.OTHER,
            apiProviderTypeId = ModelConfigManager.PI_RUNTIME_PROVIDER_TYPE_ID,
            piModelBinding = PiModelBinding("openai", "gpt-5"),
        )
        assertEquals("", config.apiKey)
        assertEquals("", config.apiEndpoint)
        assertEquals("", config.modelName)
        assertEquals("openai", config.piModelBinding?.provider)
    }

    @Test
    fun `auto resolves from returned model sources`() {
        val draft = PiModelEditorDraft(
            providerId = "custom",
            api = PiModelApi.AUTO,
            modelId = "gemini-2.5-pro",
            discoveredModels = listOf(
                PiDiscoveredModel(
                    id = "gemini-2.5-pro",
                    sources = listOf(PiModelApi.GOOGLE_GENERATIVE_AI),
                )
            ),
        )
        assertEquals(PiModelApi.GOOGLE_GENERATIVE_AI, resolveApiForModel(draft, draft.modelId))
    }

    @Test
    fun `manual model with auto requires concrete API`() {
        val draft = PiModelEditorDraft(providerId = "custom", api = PiModelApi.AUTO, modelId = "manual")
        assertThrows(IllegalArgumentException::class.java) {
            resolveApiForModel(draft, draft.modelId)
        }
    }

    @Test
    fun `revision conflict refreshes setup before surfacing`() = runBlocking {
        val refreshed = emptySetup("new-revision")
        val gateway = FakeGateway(setup = refreshed).apply {
            applyFailure = WuxianPiModelApiException(
                statusCode = 409,
                code = "revision_conflict",
                message = "stale",
                details = mapOf("expected" to "new-revision", "actual" to "old"),
            )
        }
        val error = assertThrows(PiModelRevisionConflictException::class.java) {
            runBlocking {
                PiModelSetupRepository(gateway).apply(
                    setup = emptySetup("old"),
                    draft = PiModelEditorDraft(
                        providerId = "p",
                        api = PiModelApi.OPENAI_COMPLETIONS,
                        modelId = "m",
                    ),
                    setGlobalDefault = false,
                )
            }
        }
        assertEquals("new-revision", error.refreshedSetup.revision)
        assertNotNull(error.cause)
    }
}

private class FakeGateway(
    var setup: PiModelSetupState = emptySetup(),
) : PiModelSetupGateway {
    var fetchDraft: PiModelProviderDraft? = null
    var testDraft: PiModelProviderDraft? = null
    var testModelId: String? = null
    var applyRequest: PiModelApplyRequest? = null
    var applyFailure: WuxianPiModelApiException? = null

    override suspend fun getSetup(): PiModelSetupState = setup

    override suspend fun fetchModels(draft: PiModelProviderDraft): PiModelDraftResult {
        fetchDraft = draft
        return draftResult()
    }

    override suspend fun testModel(draft: PiModelProviderDraft, modelId: String): PiModelDraftResult {
        testDraft = draft
        testModelId = modelId
        return draftResult().copy(modelId = modelId, resolvedApi = draft.api)
    }

    override suspend fun apply(request: PiModelApplyRequest): PiModelSetupState {
        applyRequest = request
        applyFailure?.let { throw it }
        setup = setup.copy(revision = "applied", config = request.config)
        return setup
    }
}

private fun emptySetup(revision: String = "revision-1"): PiModelSetupState = PiModelSetupState(
    revision = revision,
    presets = emptyList(),
    config = PiModelSetupConfig(emptyMap()),
    providers = emptyList(),
    models = emptyList(),
    defaultModel = null,
)

private fun draftResult(): PiModelDraftResult = PiModelDraftResult(
    ok = true,
    models = emptyList(),
    recommendedModel = null,
    resolvedApi = null,
    modeResults = emptyList(),
    candidates = emptyList(),
    provider = null,
    modelId = null,
    latencyMs = null,
    status = null,
    responseText = null,
    message = null,
    hint = null,
)
