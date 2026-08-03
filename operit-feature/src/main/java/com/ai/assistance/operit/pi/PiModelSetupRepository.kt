package com.ai.assistance.operit.pi

import com.ai.assistance.operit.data.model.PiModelBinding
import com.wuxianpi.pi.PiConfiguredModel
import com.wuxianpi.pi.PiDiscoveredModel
import com.wuxianpi.pi.PiModelApi
import com.wuxianpi.pi.PiModelApiKey
import com.wuxianpi.pi.PiModelApplyRequest
import com.wuxianpi.pi.PiModelCredentialMutation
import com.wuxianpi.pi.PiModelDraftResult
import com.wuxianpi.pi.PiModelHeaders
import com.wuxianpi.pi.PiModelProviderChange
import com.wuxianpi.pi.PiModelProviderConfig
import com.wuxianpi.pi.PiModelProviderDraft
import com.wuxianpi.pi.PiModelRef
import com.wuxianpi.pi.PiModelSetupConfig
import com.wuxianpi.pi.PiModelSetupState
import com.wuxianpi.pi.WuxianPiModelApiException
import com.wuxianpi.pi.WuxianPiModelClient

data class PiModelEditorDraft(
    val providerId: String,
    val presetId: String? = null,
    val baseUrl: String = "",
    val api: PiModelApi = PiModelApi.AUTO,
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val modelId: String = "",
    val discoveredModels: List<PiDiscoveredModel> = emptyList(),
)

data class PiModelApplyOutcome(
    val setup: PiModelSetupState,
    val binding: PiModelBinding,
    val resolvedApi: PiModelApi,
)

class PiModelRevisionConflictException(
    val refreshedSetup: PiModelSetupState,
    cause: WuxianPiModelApiException,
) : IllegalStateException("模型配置已被其他页面修改，已刷新到最新版本，请确认后重试。", cause)

internal interface PiModelSetupGateway {
    suspend fun getSetup(): PiModelSetupState
    suspend fun fetchModels(draft: PiModelProviderDraft): PiModelDraftResult
    suspend fun testModel(draft: PiModelProviderDraft, modelId: String): PiModelDraftResult
    suspend fun apply(request: PiModelApplyRequest): PiModelSetupState
}

internal class WuxianPiModelSetupGateway(
    private val client: WuxianPiModelClient,
) : PiModelSetupGateway {
    override suspend fun getSetup(): PiModelSetupState = client.getSetup()

    override suspend fun fetchModels(draft: PiModelProviderDraft): PiModelDraftResult =
        client.fetchModels(draft)

    override suspend fun testModel(
        draft: PiModelProviderDraft,
        modelId: String,
    ): PiModelDraftResult = client.testModel(draft, modelId)

    override suspend fun apply(request: PiModelApplyRequest): PiModelSetupState =
        client.apply(request)
}

/** Shared model setup orchestration. Android never reads or writes Pi configuration files. */
class PiModelSetupRepository internal constructor(
    private val gateway: PiModelSetupGateway,
) {
    constructor(client: WuxianPiModelClient) : this(WuxianPiModelSetupGateway(client))

    suspend fun setup(): PiModelSetupState = gateway.getSetup()

    suspend fun fetch(draft: PiModelEditorDraft): PiModelDraftResult =
        gateway.fetchModels(draft.toProviderDraft())

    suspend fun test(draft: PiModelEditorDraft): PiModelDraftResult {
        val modelId = draft.modelId.trim()
        require(modelId.isNotEmpty()) { "请填写或选择模型 ID" }
        val resolvedApi = resolveApiForModel(draft, modelId)
        return gateway.testModel(draft.copy(api = resolvedApi).toProviderDraft(), modelId)
    }

    suspend fun testSavedBinding(
        setup: PiModelSetupState,
        binding: PiModelBinding,
    ): PiModelDraftResult {
        val provider = setup.config.providers[binding.provider]
            ?: throw IllegalStateException("Pi Runtime 中不存在 Provider ${binding.provider}")
        val api = provider.api
            ?: provider.models.firstOrNull { it.id == binding.modelId }?.api
            ?: throw IllegalStateException("Provider ${binding.provider} 尚未配置 API 类型")
        require(api != PiModelApi.AUTO) { "已保存 Provider 仍使用 Auto，请选择具体 API 类型后重新保存" }
        return gateway.testModel(
            PiModelProviderDraft(
                providerId = binding.provider,
                baseUrl = provider.baseUrl,
                api = api,
                headers = provider.headers,
                models = provider.models,
            ),
            binding.modelId,
        )
    }

    suspend fun apply(
        setup: PiModelSetupState,
        draft: PiModelEditorDraft,
        setGlobalDefault: Boolean,
    ): PiModelApplyOutcome {
        val providerId = draft.providerId.trim()
        val modelId = draft.modelId.trim()
        require(providerId.isNotEmpty()) { "Provider ID 不能为空" }
        require(modelId.isNotEmpty()) { "请填写或选择模型 ID" }
        val resolvedApi = resolveApiForModel(draft, modelId)
        val existing = setup.config.providers[providerId]
        val selected = draft.discoveredModels.firstOrNull { it.id == modelId }
        val existingSelected = existing?.models?.firstOrNull { it.id == modelId }
        val configuredModels = buildList<PiConfiguredModel> {
            add(selected.toConfiguredModel(modelId, resolvedApi, existingSelected))
            draft.discoveredModels
                .asSequence()
                .filter { it.id != modelId }
                .map { discovered ->
                    discovered.toConfiguredModel(
                        discovered.id,
                        resolvedApiForDiscovered(discovered, resolvedApi),
                        existing?.models?.firstOrNull { it.id == discovered.id },
                    )
                }
                .forEach(::add)
            existing?.models.orEmpty()
                .filter { saved ->
                    saved.id != modelId && draft.discoveredModels.none { discovered -> discovered.id == saved.id }
                }
                .forEach(::add)
        }
        val provider = PiModelProviderConfig(
            baseUrl = draft.baseUrl.trim().takeIf(String::isNotEmpty),
            api = resolvedApi,
            headers = PiModelHeaders.of(draft.headers.normalizedHeaders()),
            models = configuredModels,
            additionalProperties = existing?.additionalProperties.orEmpty(),
        )
        val nextConfig = PiModelSetupConfig(setup.config.providers + (providerId to provider))
        val credential = draft.apiKey.trim().takeIf(String::isNotEmpty)?.let {
            PiModelCredentialMutation.Set(PiModelApiKey.of(it))
        } ?: PiModelCredentialMutation.Keep
        val binding = PiModelBinding(providerId, modelId)
        val request = PiModelApplyRequest(
            revision = setup.revision,
            config = nextConfig,
            changes = listOf(PiModelProviderChange.Upsert(providerId, provider, credential)),
            defaultModel = if (setGlobalDefault) PiModelRef(providerId, modelId) else null,
            setGlobalDefault = setGlobalDefault,
        )
        val applied = try {
            gateway.apply(request)
        } catch (error: WuxianPiModelApiException) {
            if (error.statusCode == 409 || error.code.contains("revision", ignoreCase = true)) {
                throw PiModelRevisionConflictException(gateway.getSetup(), error)
            }
            throw error
        }
        return PiModelApplyOutcome(applied, binding, resolvedApi)
    }
}

internal fun resolveApiForModel(draft: PiModelEditorDraft, modelId: String): PiModelApi {
    if (draft.api != PiModelApi.AUTO) return draft.api
    val sources = draft.discoveredModels.firstOrNull { it.id == modelId }?.sources.orEmpty()
    return sources.firstOrNull { it != PiModelApi.AUTO }
        ?: throw IllegalArgumentException("手动填写模型 ID 时不能使用 Auto，请选择具体 API 类型")
}

private fun PiModelEditorDraft.toProviderDraft(): PiModelProviderDraft =
    PiModelProviderDraft(
        providerId = providerId.trim(),
        presetId = presetId?.trim()?.takeIf(String::isNotEmpty),
        baseUrl = baseUrl.trim().takeIf(String::isNotEmpty),
        api = api,
        headers = PiModelHeaders.of(headers.normalizedHeaders()),
        apiKey = apiKey.trim().takeIf(String::isNotEmpty)?.let(PiModelApiKey::of),
        models = discoveredModels.map { model ->
            model.toConfiguredModel(model.id, resolvedApiForDiscovered(model, api), null)
        },
    )

private fun PiDiscoveredModel?.toConfiguredModel(
    fallbackId: String,
    api: PiModelApi,
    existing: PiConfiguredModel?,
): PiConfiguredModel = PiConfiguredModel(
    id = this?.id ?: fallbackId,
    name = this?.name ?: existing?.name,
    api = api,
    reasoning = existing?.reasoning,
    input = existing?.input.orEmpty(),
    contextWindow = existing?.contextWindow,
    maxTokens = existing?.maxTokens,
    additionalProperties = existing?.additionalProperties.orEmpty(),
)

private fun resolvedApiForDiscovered(model: PiDiscoveredModel, fallback: PiModelApi): PiModelApi =
    model.sources.firstOrNull { it != PiModelApi.AUTO }
        ?: fallback.takeUnless { it == PiModelApi.AUTO }
        ?: PiModelApi.OPENAI_COMPLETIONS

private fun Map<String, String>.normalizedHeaders(): Map<String, String> =
    entries.mapNotNull { (name, value) ->
        name.trim().takeIf(String::isNotEmpty)?.let { it to value.trim() }
    }.toMap()
