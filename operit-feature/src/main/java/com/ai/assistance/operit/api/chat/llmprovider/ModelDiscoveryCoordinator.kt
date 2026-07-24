package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class ModelDiscoveryResult(
    val providerType: ApiProviderType,
    val models: List<ModelOption>,
)

object ModelDiscoveryCoordinator {
    private val probeOrder =
        listOf(
            ApiProviderType.OPENAI_GENERIC,
            ApiProviderType.OPENAI_RESPONSES_GENERIC,
            ApiProviderType.ANTHROPIC_GENERIC,
            ApiProviderType.GEMINI_GENERIC,
        )

    suspend fun discover(
        context: Context,
        modelConfigManager: ModelConfigManager,
        draft: ModelConfigData,
        reload: Boolean = false,
    ): Result<ModelDiscoveryResult> =
        discoverWith { providerType ->
            probeProvider(
                context = context,
                modelConfigManager = modelConfigManager,
                draft = draft,
                providerType = providerType,
                reload = reload,
            )
        }

    private suspend fun probeProvider(
        context: Context,
        modelConfigManager: ModelConfigManager,
        draft: ModelConfigData,
        providerType: ApiProviderType,
        reload: Boolean,
    ): Result<List<ModelOption>> {
        val modelsResult =
            ModelListFetcher.getModelsList(
                context = context,
                apiKey = draft.apiKey,
                apiEndpoint = draft.apiEndpoint,
                apiProviderType = providerType,
                customHeadersJson = draft.customHeaders,
                reload = reload,
                maxRetries = 0,
            )
        val models = modelsResult.getOrElse { return Result.failure(it) }
        val candidateIds = selectProbeModelIds(draft.modelName, models)
        if (candidateIds.isEmpty()) {
            return Result.failure(IllegalStateException("${providerType.name} 未返回可用于对话的模型"))
        }

        val failures = mutableListOf<String>()
        for (modelId in candidateIds) {
            val report =
                try {
                    ModelConfigConnectionTester.run(
                        context = context,
                        modelConfigManager = modelConfigManager,
                        config = draft.minimalProbeConfig(providerType, modelId),
                        parametersOverride = emptyList(),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += "$modelId: ${error.message ?: "调用失败"}"
                    continue
                }
            val chatResult = report.items.firstOrNull { it.type == ModelConnectionTestType.CHAT }
            if (chatResult?.success == true) {
                return Result.success(mergeManualModels(draft.modelName, models))
            }
            failures += "$modelId: ${chatResult?.error ?: "协议调用失败"}"
        }

        return Result.failure(
            IllegalStateException("${providerType.name} 候选模型均调用失败：${failures.joinToString("; ")}")
        )
    }

    internal suspend fun discoverWith(
        probe: suspend (ApiProviderType) -> Result<List<ModelOption>>,
    ): Result<ModelDiscoveryResult> = supervisorScope {
        val attempts =
            probeOrder.map { providerType ->
                async {
                    val result =
                        try {
                            probe(providerType)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    providerType to result
                }
            }.awaitAll()

        attempts.firstOrNull { (_, result) ->
            result.isSuccess && result.getOrThrow().isNotEmpty()
        }?.let { (providerType, result) ->
            return@supervisorScope Result.success(
                ModelDiscoveryResult(providerType, result.getOrThrow().distinctBy { it.id })
            )
        }

        val details =
            attempts.joinToString("; ") { (providerType, result) ->
                val error =
                    result.exceptionOrNull()?.message
                        ?: if (result.getOrNull().isNullOrEmpty()) "未返回模型" else "未知错误"
                "${providerType.name}: $error"
            }
        Result.failure(IllegalStateException("多协议模型探测失败：$details"))
    }

    internal fun selectProbeModelIds(
        manualModelNames: String,
        fetchedModels: List<ModelOption>,
    ): List<String> {
        val manualIds = parseManualModelIds(manualModelNames).take(MAX_MANUAL_PROBE_MODELS)
        val fetchedIds =
            fetchedModels
                .asSequence()
                .map { it.id.trim() }
                .filter { it.isNotEmpty() && !isNonTextGenerationModel(it) }
                .distinctBy { it.lowercase() }
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<String>> { textGenerationScore(it.value) }
                        .thenBy { it.index }
                )
                .map { it.value }
                .take(MAX_FETCHED_PROBE_MODELS)
                .toList()

        return (manualIds + fetchedIds).distinctBy { it.lowercase() }
    }

    private fun ModelConfigData.minimalProbeConfig(
        providerType: ApiProviderType,
        modelId: String,
    ): ModelConfigData =
        copy(
            apiProviderType = providerType,
            apiProviderTypeId = providerType.name,
            modelName = modelId,
            useMultipleApiKeys = false,
            apiKeyPool = emptyList(),
            currentKeyIndex = 0,
            hasCustomParameters = false,
            maxTokensEnabled = false,
            temperatureEnabled = false,
            topPEnabled = false,
            topKEnabled = false,
            presencePenaltyEnabled = false,
            frequencyPenaltyEnabled = false,
            repetitionPenaltyEnabled = false,
            customParameters = "[]",
            enableToolCall = false,
            enableDirectImageProcessing = false,
            enableDirectAudioProcessing = false,
            enableDirectVideoProcessing = false,
            enableGoogleSearch = false,
            enableClaude1hPromptCache = false,
            requestLimitPerMinute = 0,
            maxConcurrentRequests = 0,
        )

    private fun mergeManualModels(
        manualModelNames: String,
        fetchedModels: List<ModelOption>,
    ): List<ModelOption> =
        (
            parseManualModelIds(manualModelNames).map { ModelOption(id = it, name = it) } +
                fetchedModels
        ).distinctBy { it.id.lowercase() }

    private fun parseManualModelIds(modelNames: String): List<String> =
        modelNames
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)

    private fun isNonTextGenerationModel(modelId: String): Boolean {
        val normalized = modelId.lowercase()
        return NON_TEXT_MODEL_PATTERN.containsMatchIn(normalized) ||
            NON_TEXT_MODEL_PREFIXES.any(normalized::startsWith)
    }

    private fun textGenerationScore(modelId: String): Int {
        val normalized = modelId.lowercase()
        return TEXT_GENERATION_HINTS.count(normalized::contains)
    }

    private const val MAX_MANUAL_PROBE_MODELS = 3
    private const val MAX_FETCHED_PROBE_MODELS = 5

    private val NON_TEXT_MODEL_PATTERN =
        Regex(
            "(^|[^a-z0-9])(embed|embedding|embeddings|rerank|reranker|moderation|image|images|" +
                "dall[-_]?e|flux|stable[-_]?diffusion|whisper|transcribe|transcription|speech|" +
                "tts|audio|video|veo|sora|clip|realtime)([^a-z0-9]|$)"
        )

    private val NON_TEXT_MODEL_PREFIXES =
        listOf("bge-", "bge_", "e5-", "e5_", "gte-", "gte_", "jina-embeddings")

    private val TEXT_GENERATION_HINTS =
        listOf(
            "gpt",
            "chat",
            "claude",
            "gemini",
            "deepseek",
            "qwen",
            "llama",
            "mistral",
            "command-r",
            "grok",
            "glm",
            "kimi",
            "minimax",
            "sonnet",
            "haiku",
            "opus",
            "instruct",
            "coder",
            "reasoning",
        )
}
