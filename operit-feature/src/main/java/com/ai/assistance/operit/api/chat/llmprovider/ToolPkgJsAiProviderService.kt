package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_CALCULATE_INPUT_TOKENS
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_LIST_MODELS
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_SEND_MESSAGE
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_TEST_CONNECTION
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistration
import com.ai.assistance.operit.plugins.toolpkg.decodeToolPkgHookResult
import com.ai.assistance.operit.plugins.toolpkg.jsonObjectToMap
import com.ai.assistance.operit.plugins.toolpkg.toolPkgPackageManager
import com.ai.assistance.operit.util.stream.Stream
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ToolPkgJsAiProviderService(
    private val config: ModelConfigData,
    private val provider: ToolPkgAiProviderRegistration
) : AIService {
    private sealed interface ProviderHookValue {
        data object NullValue : ProviderHookValue

        data class TextValue(
            val value: String
        ) : ProviderHookValue

        data class BooleanValue(
            val value: Boolean
        ) : ProviderHookValue

        data class NumberValue(
            val value: Number
        ) : ProviderHookValue

        data class ObjectValue(
            val value: JSONObject
        ) : ProviderHookValue

        data class ArrayValue(
            val value: JSONArray
        ) : ProviderHookValue
    }

    @Volatile
    private var currentInputTokenCount: Int = 0

    @Volatile
    private var currentCachedInputTokenCount: Int = 0

    @Volatile
    private var currentOutputTokenCount: Int = 0

    private val executionChatId =
        "toolpkg-ai-provider:${provider.providerId}:${UUID.randomUUID().toString().replace("-", "")}"

    private val providerRuntimeContextKey =
        "toolpkg_provider:${provider.containerPackageName}:${provider.providerId.trim().lowercase()}"

    override val inputTokenCount: Int
        get() = currentInputTokenCount

    override val cachedInputTokenCount: Int
        get() = currentCachedInputTokenCount

    override val outputTokenCount: Int
        get() = currentOutputTokenCount

    override val providerModel: String
        get() = "${provider.displayName}:${config.modelName}"

    override fun resetTokenCounts() {
        currentInputTokenCount = 0
        currentCachedInputTokenCount = 0
        currentOutputTokenCount = 0
    }

    override fun cancelStreaming() {
        toolPkgPackageManager().cancelToolPkgExecutionsForChat(executionChatId)
    }

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return runCatching {
            val decoded =
                invokeProviderFunction(
                    functionName = provider.listModelsFunctionName,
                    functionSource = provider.listModelsFunctionSource,
                    event = TOOLPKG_EVENT_AI_PROVIDER_LIST_MODELS,
                    eventPayload = buildBasePayload(context)
                )
            ensureNoFatalError(decoded)
            parseModelOptions(decoded)
        }
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean
    ): Stream<String> = com.ai.assistance.operit.util.stream.stream {
        var hasIntermediateTextChunk = false
        val decoded =
            invokeProviderFunction(
                functionName = provider.sendMessageFunctionName,
                functionSource = provider.sendMessageFunctionSource,
                event = TOOLPKG_EVENT_AI_PROVIDER_SEND_MESSAGE,
                eventPayload =
                    buildBasePayload(context).apply {
                        put("chatHistory", JSONArray(chatHistory.map(::serializePromptTurn)))
                        put("modelParameters", JSONArray(modelParameters.map(::serializeModelParameter)))
                        put(
                            "availableTools",
                            availableTools?.let { tools -> JSONArray(tools.map(::serializeToolPrompt)) }
                        )
                        put("enableThinking", enableThinking)
                        put("stream", stream)
                        put("preserveThinkInHistory", preserveThinkInHistory)
                        put("enableRetry", enableRetry)
                    },
                onIntermediateResult = { intermediateDecoded ->
                    extractUsage(intermediateDecoded)?.let { usage ->
                        applyUsage(usage)
                        onTokensUpdated(
                            currentInputTokenCount,
                            currentCachedInputTokenCount,
                            currentOutputTokenCount
                        )
                    }
                    extractNonFatalError(intermediateDecoded)?.let { error ->
                        onNonFatalError(error)
                    }
                    extractMessageChunks(intermediateDecoded).forEach { chunk ->
                        hasIntermediateTextChunk = true
                        emit(chunk)
                    }
                }
            )

        ensureNoFatalError(decoded)
        extractUsage(decoded)?.let { usage ->
            applyUsage(usage)
            onTokensUpdated(
                currentInputTokenCount,
                currentCachedInputTokenCount,
                currentOutputTokenCount
            )
        }
        extractNonFatalError(decoded)?.let { error ->
            onNonFatalError(error)
        }
        if (!hasIntermediateTextChunk) {
            extractMessageChunks(decoded).forEach { chunk ->
                emit(chunk)
            }
        }
    }

    override suspend fun testConnection(context: Context): Result<String> {
        return runCatching {
            val decoded =
                invokeProviderFunction(
                    functionName = provider.testConnectionFunctionName,
                    functionSource = provider.testConnectionFunctionSource,
                    event = TOOLPKG_EVENT_AI_PROVIDER_TEST_CONNECTION,
                    eventPayload = buildBasePayload(context)
                )
            ensureNoFatalError(decoded)
            parseConnectionMessage(decoded)
        }
    }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Int {
        val decoded =
            invokeProviderFunction(
                functionName = provider.calculateInputTokensFunctionName,
                functionSource = provider.calculateInputTokensFunctionSource,
                event = TOOLPKG_EVENT_AI_PROVIDER_CALCULATE_INPUT_TOKENS,
                eventPayload =
                    buildBasePayload(context = null).apply {
                        put("chatHistory", JSONArray(chatHistory.map(::serializePromptTurn)))
                        put(
                            "availableTools",
                            availableTools?.let { tools -> JSONArray(tools.map(::serializeToolPrompt)) }
                        )
                    }
            )
        ensureNoFatalError(decoded)
        return parseTokenCount(decoded)
    }

    override fun release() {
        cancelStreaming()
    }

    private suspend fun invokeProviderFunction(
        functionName: String,
        functionSource: String?,
        event: String,
        eventPayload: JSONObject,
        onIntermediateResult: (suspend (ProviderHookValue) -> Unit)? = null
    ): ProviderHookValue = coroutineScope {
        val manager = toolPkgPackageManager()
        val intermediateChannel =
            if (onIntermediateResult == null) {
                null
            } else {
                Channel<Any?>(capacity = Channel.UNLIMITED)
            }
        val intermediateJob =
            intermediateChannel?.let { channel ->
                launch(Dispatchers.IO) {
                    for (raw in channel) {
                        onIntermediateResult?.invoke(
                            decodeProviderHookValue(decodeToolPkgHookResult(raw))
                        )
                    }
                }
            }

        try {
            val result =
                withContext(Dispatchers.IO) {
                    manager.runToolPkgMainHook(
                        containerPackageName = provider.containerPackageName,
                        functionName = functionName,
                        event = event,
                        pluginId = "${provider.providerId}:$event",
                        inlineFunctionSource = functionSource,
                        eventPayload =
                            jsonObjectToMap(
                                JSONObject(eventPayload.toString()).put("chatId", executionChatId)
                            ),
                        executionContextKey = providerRuntimeContextKey,
                        runtimeKind = "provider",
                        dispatchIntermediateOnMain = false,
                        onIntermediateResult =
                            intermediateChannel?.let { channel ->
                                { raw ->
                                    channel.trySend(raw)
                                }
                            }
                    )
                }
            decodeProviderHookValue(
                result.getOrElse { error -> throw error }?.let { raw -> decodeToolPkgHookResult(raw) }
            )
        } finally {
            intermediateChannel?.close()
            intermediateJob?.join()
        }
    }

    private fun buildBasePayload(context: Context?): JSONObject {
        return jsonObjectOf(
            "providerId" to provider.providerId,
            "providerDisplayName" to provider.displayName,
            "providerDescription" to provider.description,
            "config" to serializeModelConfig(context)
        )
    }

    private fun serializeModelConfig(context: Context?): JSONObject {
        return jsonObjectOf(
            "id" to config.id,
            "name" to config.name,
            "apiProviderType" to config.apiProviderTypeId,
            "apiProviderTypeId" to config.apiProviderTypeId,
            "apiKey" to config.apiKey,
            "apiEndpoint" to config.apiEndpoint,
            "modelName" to config.modelName,
            "customHeaders" to decodeJsonObjectString(config.customHeaders),
            "customParameters" to decodeJsonArrayString(config.customParameters),
            "enableDirectImageProcessing" to config.enableDirectImageProcessing,
            "enableDirectAudioProcessing" to config.enableDirectAudioProcessing,
            "enableDirectVideoProcessing" to config.enableDirectVideoProcessing,
            "enableGoogleSearch" to config.enableGoogleSearch,
            "enableClaude1hPromptCache" to config.enableClaude1hPromptCache,
            "enableToolCall" to config.enableToolCall,
            "requestLimitPerMinute" to config.requestLimitPerMinute,
            "maxConcurrentRequests" to config.maxConcurrentRequests,
            "locale" to context?.resources?.configuration?.locales?.get(0)?.toLanguageTag()
        )
    }

    private fun serializePromptTurn(turn: PromptTurn): JSONObject {
        return jsonObjectOf(
            "kind" to turn.kind.name,
            "content" to turn.content,
            "toolName" to turn.toolName,
            "metadata" to turn.metadata
        )
    }

    private fun serializeModelParameter(parameter: ModelParameter<*>): JSONObject {
        return jsonObjectOf(
            "id" to parameter.id,
            "name" to parameter.name,
            "value" to parameter.currentValue,
            "type" to parameter.valueType.name,
            "category" to parameter.category.name,
            "enabled" to parameter.isEnabled,
            "custom" to parameter.isCustom
        )
    }

    private fun serializeToolPrompt(tool: ToolPrompt): JSONObject {
        return jsonObjectOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to tool.parameters,
            "parametersStructured" to
                JSONArray(
                    tool.parametersStructured?.map { schema ->
                        jsonObjectOf(
                            "name" to schema.name,
                            "type" to schema.type,
                            "description" to schema.description,
                            "required" to schema.required,
                            "default" to schema.default
                        )
                    } ?: emptyList<JSONObject>()
                )
        )
    }

    private fun decodeJsonObjectString(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "{}") {
            return JSONObject()
        }
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun decodeJsonArrayString(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") {
            return JSONArray()
        }
        return try {
            JSONArray(trimmed)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun parseModelOptions(decoded: ProviderHookValue): List<ModelOption> {
        val items =
            when (decoded) {
                is ProviderHookValue.ObjectValue ->
                    decodeProviderHookValue(
                        if (decoded.value.has("models")) decoded.value.opt("models") else decoded.value
                    )
                is ProviderHookValue.ArrayValue -> decoded
                else -> decoded
            }
        return when (items) {
            is ProviderHookValue.ArrayValue ->
                (0 until items.value.length()).mapNotNull { index ->
                    parseModelOption(decodeProviderHookValue(items.value.opt(index)))
                }
            else -> listOfNotNull(parseModelOption(items))
        }
    }

    private fun parseModelOption(raw: ProviderHookValue): ModelOption? {
        return when (raw) {
            ProviderHookValue.NullValue -> null
            is ProviderHookValue.TextValue ->
                raw.value.trim().takeIf { it.isNotBlank() }?.let { ModelOption(id = it, name = it) }
            is ProviderHookValue.ObjectValue -> {
                val id =
                    raw.value.optString("id")
                        .ifBlank { raw.value.optString("name") }
                        .ifBlank { raw.value.optString("model") }
                        .trim()
                val name =
                    raw.value.optString("name")
                        .ifBlank { raw.value.optString("displayName") }
                        .ifBlank { raw.value.optString("title") }
                        .ifBlank { id }
                        .trim()
                if (id.isBlank()) null else ModelOption(id = id, name = name)
            }
            else -> null
        }
    }

    private fun parseConnectionMessage(decoded: ProviderHookValue): String {
        return when (decoded) {
            is ProviderHookValue.TextValue -> decoded.value.ifBlank { "Connection successful" }
            is ProviderHookValue.BooleanValue ->
                if (decoded.value) "Connection successful" else error("Connection failed")
            is ProviderHookValue.ObjectValue -> {
                val success =
                    if (decoded.value.has("success")) {
                        decoded.value.optBoolean("success", true)
                    } else {
                        true
                    }
                if (!success) {
                    throw IllegalStateException(
                        decoded.value.optString("error").ifBlank { "Connection failed" }
                    )
                }
                decoded.value.optString("message").ifBlank { "Connection successful" }
            }
            else -> "Connection successful"
        }
    }

    private fun parseTokenCount(decoded: ProviderHookValue): Int {
        return when (decoded) {
            is ProviderHookValue.NumberValue -> decoded.value.toInt()
            is ProviderHookValue.TextValue ->
                decoded.value.trim().toIntOrNull()
                    ?: throw IllegalStateException("Invalid token count result: ${decoded.value}")
            is ProviderHookValue.ObjectValue -> {
                decoded.value.optInt("tokens", Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }
                    ?: decoded.value.optInt("inputTokens", Int.MIN_VALUE)
                        .takeIf { it != Int.MIN_VALUE }
                    ?: decoded.value.optInt("count", Int.MIN_VALUE)
                        .takeIf { it != Int.MIN_VALUE }
                    ?: throw IllegalStateException("Invalid token count result")
            }
            else -> throw IllegalStateException("Invalid token count result")
        }
    }

    private fun ensureNoFatalError(decoded: ProviderHookValue) {
        when (decoded) {
            is ProviderHookValue.ObjectValue -> {
                val success =
                    if (decoded.value.has("success")) {
                        decoded.value.optBoolean("success", true)
                    } else {
                        true
                    }
                if (!success) {
                    throw IllegalStateException(
                        decoded.value.optString("error").ifBlank { "ToolPkg AI provider call failed" }
                    )
                }
            }
            else -> Unit
        }
    }

    private fun extractNonFatalError(decoded: ProviderHookValue): String? {
        return when (decoded) {
            is ProviderHookValue.ObjectValue ->
                decoded.value.optString("nonFatalError").trim().ifBlank { null }
            else -> null
        }
    }

    private fun extractUsage(decoded: ProviderHookValue): TokenUsage? {
        return when (decoded) {
            is ProviderHookValue.ObjectValue -> extractUsageFromJson(decoded.value)
            else -> null
        }
    }

    private fun extractUsageFromJson(json: JSONObject): TokenUsage? {
        val usageObject = json.optJSONObject("usage")
        val source = usageObject ?: json
        val input = source.optInt("input", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?: source.optInt("inputTokens", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val cachedInput = source.optInt("cachedInput", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?: source.optInt("cachedInputTokens", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val output = source.optInt("output", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?: source.optInt("outputTokens", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        if (input == null && cachedInput == null && output == null) {
            return null
        }
        return TokenUsage(
            input = input ?: currentInputTokenCount,
            cachedInput = cachedInput ?: currentCachedInputTokenCount,
            output = output ?: currentOutputTokenCount
        )
    }

    private fun applyUsage(usage: TokenUsage) {
        currentInputTokenCount = usage.input
        currentCachedInputTokenCount = usage.cachedInput
        currentOutputTokenCount = usage.output
    }

    private fun extractMessageChunks(decoded: ProviderHookValue): List<String> {
        return when (decoded) {
            ProviderHookValue.NullValue -> emptyList()
            is ProviderHookValue.TextValue ->
                if (decoded.value.isEmpty()) emptyList() else listOf(decoded.value)
            is ProviderHookValue.ObjectValue -> {
                val chunks = mutableListOf<String>()
                if (decoded.value.has("chunk") && !decoded.value.isNull("chunk")) {
                    decoded.value.optString("chunk").takeIf { it.isNotEmpty() }?.let(chunks::add)
                }
                decoded.value.optJSONArray("chunks")?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotEmpty() }?.let(chunks::add)
                    }
                }
                if (decoded.value.has("text") && !decoded.value.isNull("text")) {
                    decoded.value.optString("text").takeIf { it.isNotEmpty() }?.let(chunks::add)
                } else if (decoded.value.has("content") && !decoded.value.isNull("content")) {
                    decoded.value.optString("content").takeIf { it.isNotEmpty() }?.let(chunks::add)
                }
                chunks
            }
            else -> emptyList()
        }
    }

    private fun decodeProviderHookValue(raw: kotlin.Any?): ProviderHookValue {
        return when (raw) {
            null,
            JSONObject.NULL -> ProviderHookValue.NullValue
            is JSONObject -> ProviderHookValue.ObjectValue(raw)
            is JSONArray -> ProviderHookValue.ArrayValue(raw)
            is String -> ProviderHookValue.TextValue(raw)
            is Boolean -> ProviderHookValue.BooleanValue(raw)
            is Number -> ProviderHookValue.NumberValue(raw)
            is Map<*, *> -> ProviderHookValue.ObjectValue(JSONObject(raw))
            is List<*> -> ProviderHookValue.ArrayValue(JSONArray(raw))
            else -> ProviderHookValue.TextValue(raw.toString())
        }
    }

    private fun jsonObjectOf(vararg entries: Pair<String, *>): JSONObject {
        return JSONObject().apply {
            entries.forEach { (key, value) -> put(key, value) }
        }
    }

    private data class TokenUsage(
        val input: Int,
        val cachedInput: Int,
        val output: Int
    )
}
