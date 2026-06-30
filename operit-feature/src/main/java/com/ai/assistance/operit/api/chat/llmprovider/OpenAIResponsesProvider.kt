package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.util.AppLogger
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAIResponsesProvider(
    private val responsesApiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    private val responsesProviderType: ApiProviderType = ApiProviderType.OPENAI_RESPONSES,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
    apiEndpoint = responsesApiEndpoint,
    apiKeyProvider = apiKeyProvider,
    modelName = modelName,
    client = client,
    customHeaders = customHeaders,
    providerType = responsesProviderType,
    supportsVision = supportsVision,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    enableToolCall = enableToolCall
) {
    override val useResponsesApi: Boolean = true

    override fun createRequestBody(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean
    ): RequestBody {
        val baseRequestBodyJson = super.createRequestBodyInternal(
            context,
            chatHistory,
            modelParameters,
            stream,
            availableTools,
            preserveThinkInHistory
        )
        val jsonObject = JSONObject(baseRequestBodyJson)

        applyResponsesReasoningEffort(
            context = context,
            requestJson = jsonObject,
            enableThinking = enableThinking
        )

        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        val sanitizedLogJson = sanitizeImageDataForLogging(logJson)
        logLargeString(
            "OpenAIResponsesProvider",
            sanitizedLogJson.toString(4),
            "Final Responses request body: "
        )

        return createJsonRequestBody(jsonObject.toString())
    }

    override fun customizeFinalRequestObject(
        requestObject: JSONObject,
        messagesArray: JSONArray,
        toolsJson: String?
    ) {
        if (!shouldAttachPromptCacheKey()) {
            return
        }

        if (requestObject.has("prompt_cache_key")) {
            return
        }

        val promptCacheKey = buildPromptCacheKey(messagesArray, toolsJson) ?: return
        requestObject.put("prompt_cache_key", promptCacheKey)
        AppLogger.d("AIService", "Responses API自动附加prompt_cache_key: $promptCacheKey")
    }

    private fun applyResponsesReasoningEffort(
        context: Context,
        requestJson: JSONObject,
        enableThinking: Boolean
    ) {
        if (!enableThinking) {
            return
        }

        val reasoningObject = requestJson.optJSONObject("reasoning")
        val existingEffort =
            reasoningObject?.optString("effort", "")?.trim()?.takeIf { it.isNotEmpty() }

        when {
            reasoningObject == null && requestJson.has("reasoning") && !requestJson.isNull("reasoning") -> {
                AppLogger.w(
                    "OpenAIResponsesProvider",
                    "Skipping Responses reasoning adaptation because reasoning is not an object"
                )
                return
            }

            existingEffort != null -> {
                AppLogger.d(
                    "OpenAIResponsesProvider",
                    "Preserving caller-supplied Responses reasoning.effort=$existingEffort"
                )
                return
            }
        }

        val effort = resolveResponsesReasoningEffort(context) ?: return
        val finalReasoningObject = reasoningObject ?: JSONObject()
        finalReasoningObject.put("effort", effort)
        requestJson.put("reasoning", finalReasoningObject)
        AppLogger.d(
            "OpenAIResponsesProvider",
            "Responses reasoning enabled via reasoning.effort=$effort"
        )
    }

    private fun resolveResponsesReasoningEffort(context: Context): String? {
        val qualityLevel = runCatching {
            runBlocking {
                ApiPreferences.getInstance(context).thinkingQualityLevelFlow.first()
            }
        }.getOrElse {
            AppLogger.w(
                "OpenAIResponsesProvider",
                "Failed to read thinking quality level, falling back to auto reasoning",
                it
            )
            return null
        }

        return when (qualityLevel.coerceIn(1, 4)) {
            1 -> "low"
            2 -> "medium"
            3 -> "high"
            4 -> "xhigh"
            else -> null
        }
    }

    private fun shouldAttachPromptCacheKey(): Boolean {
        return responsesProviderType == ApiProviderType.OPENAI_RESPONSES
    }

    private fun buildPromptCacheKey(
        messagesArray: JSONArray,
        toolsJson: String?
    ): String? {
        if (messagesArray.length() == 0 && toolsJson.isNullOrBlank()) {
            return null
        }

        val anchorParts = mutableListOf<String>()
        var assistantOrToolSeen = false

        for (i in 0 until messagesArray.length()) {
            val message = messagesArray.optJSONObject(i) ?: continue
            val role = message.optString("role", "")
            if (role.isEmpty()) {
                continue
            }

            if (role == "assistant" || role == "tool") {
                assistantOrToolSeen = true
                break
            }

            if (role == "system" || role == "developer") {
                anchorParts.add("$role:${message.opt("content")}")
                continue
            }

            if (role == "user") {
                anchorParts.add("$role:${message.opt("content")}")
                break
            }
        }

        if (anchorParts.isEmpty() && assistantOrToolSeen) {
            val firstMessage = messagesArray.optJSONObject(0)
            if (firstMessage != null) {
                anchorParts.add(
                    "${firstMessage.optString("role", "unknown")}:${firstMessage.opt("content")}"
                )
            }
        }

        val digestInput =
            buildString {
                append("operit:responses_prompt_cache:v1")
                append("|model=").append(modelName)
                append("|toolCall=").append(enableToolCall)
                if (!toolsJson.isNullOrBlank()) {
                    append("|tools=").append(toolsJson)
                }
                anchorParts.forEach { part ->
                    append("|anchor=").append(part)
                }
            }

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(digestInput.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        return "operit_resp_${digest.take(48)}"
    }
}

object OpenAIResponsesPayloadAdapter {

    data class UsageCounts(
        val totalInputTokens: Int,
        val actualInputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int
    )

    data class ParsedResponseOutput(
        val textChunks: List<String>,
        val reasoningChunks: List<String>,
        val toolCalls: JSONArray,
        val usage: UsageCounts?
    )

    fun mapParameterNameForResponses(apiName: String): String {
        return when (apiName) {
            "max_tokens" -> "max_output_tokens"
            else -> apiName
        }
    }

    fun parseUsageCounts(usage: JSONObject?): UsageCounts? {
        usage ?: return null

        val totalInputTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0))
        val outputTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0))
        val cachedDetails =
            usage.optJSONObject("prompt_tokens_details")
                ?: usage.optJSONObject("input_tokens_details")
        val cachedInputTokens =
            cachedDetails?.optInt("cached_tokens", usage.optInt("cached_tokens", 0))
                ?: usage.optInt("cached_tokens", 0)
        val actualInputTokens = (totalInputTokens - cachedInputTokens).coerceAtLeast(0)

        return if (totalInputTokens > 0 || outputTokens > 0 || cachedInputTokens > 0) {
            UsageCounts(totalInputTokens, actualInputTokens, cachedInputTokens, outputTokens)
        } else {
            null
        }
    }

    fun toResponsesRequest(chatStyleRequest: JSONObject): JSONObject {
        val converted = JSONObject(chatStyleRequest.toString())

        if (converted.has("max_tokens") && !converted.has("max_output_tokens")) {
            converted.put("max_output_tokens", converted.get("max_tokens"))
            converted.remove("max_tokens")
        }

        if (converted.has("response_format")) {
            val responseFormat = converted.get("response_format")
            val textConfig = converted.optJSONObject("text") ?: JSONObject()
            textConfig.put("format", responseFormat)
            converted.put("text", textConfig)
            converted.remove("response_format")
        }

        if (converted.has("tools")) {
            val originalTools = converted.optJSONArray("tools")
            if (originalTools != null) {
                converted.put("tools", convertToolsToResponsesFormat(originalTools))
            }
        }

        if (converted.has("messages")) {
            val messages = converted.optJSONArray("messages")
            if (messages != null) {
                converted.put("input", convertMessagesToResponsesInput(messages))
                converted.remove("messages")
            }
        }

        return converted
    }

    fun parseNonStreamingResponse(jsonResponse: JSONObject): ParsedResponseOutput {
        val textChunks = mutableListOf<String>()
        val reasoningChunks = mutableListOf<String>()
        val toolCalls = JSONArray()

        val output = jsonResponse.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type", "")) {
                    "message" -> {
                        val contentArray = item.optJSONArray("content")
                        if (contentArray != null) {
                            for (j in 0 until contentArray.length()) {
                                val part = contentArray.optJSONObject(j) ?: continue
                                when (part.optString("type", "")) {
                                    "output_text", "text" -> {
                                        val text = part.optString("text", "")
                                        if (text.isNotEmpty()) {
                                            textChunks.add(text)
                                        }
                                    }

                                    "reasoning_text" -> {
                                        val text = part.optString("text", "")
                                        if (text.isNotEmpty()) {
                                            reasoningChunks.add(text)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "reasoning" -> {
                        val summaryArray = item.optJSONArray("summary")
                        if (summaryArray != null) {
                            for (j in 0 until summaryArray.length()) {
                                val summaryPart = summaryArray.optJSONObject(j) ?: continue
                                val text = summaryPart.optString("text", "")
                                if (text.isNotEmpty()) {
                                    reasoningChunks.add(text)
                                }
                            }
                        }
                    }

                    "function_call" -> {
                        val toolCall = convertFunctionCallItemToChatToolCall(item)
                        if (toolCall != null) {
                            toolCalls.put(toolCall)
                        }
                    }
                }
            }
        }

        return ParsedResponseOutput(
            textChunks = textChunks,
            reasoningChunks = reasoningChunks,
            toolCalls = toolCalls,
            usage = parseUsageCounts(jsonResponse.optJSONObject("usage"))
        )
    }

    private fun convertToolsToResponsesFormat(chatTools: JSONArray): JSONArray {
        val converted = JSONArray()

        for (i in 0 until chatTools.length()) {
            val tool = chatTools.optJSONObject(i) ?: continue
            val toolType = tool.optString("type", "")
            if (toolType != "function") {
                converted.put(tool)
                continue
            }

            val function = tool.optJSONObject("function")
            if (function == null) {
                converted.put(tool)
                continue
            }

            val convertedFunction = JSONObject().apply {
                put("type", "function")
                put("name", function.optString("name", ""))
                if (function.has("description")) {
                    put("description", function.get("description"))
                }
                if (function.has("parameters")) {
                    put("parameters", function.get("parameters"))
                }
                if (function.has("strict")) {
                    put("strict", function.get("strict"))
                }
            }

            converted.put(convertedFunction)
        }

        return converted
    }

    private fun convertMessagesToResponsesInput(messages: JSONArray): JSONArray {
        val input = JSONArray()

        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role", "")
            if (role.isEmpty()) continue

            if (role == "tool") {
                val callId = message.optString("tool_call_id", "")
                if (callId.isNotEmpty()) {
                    val outputText = extractToolOutputText(message.opt("content"))
                    input.put(
                        JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", outputText)
                        }
                    )
                    continue
                }
            }

            if (role == "assistant") {
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null && toolCalls.length() > 0) {
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val function = call.optJSONObject("function") ?: continue
                        val name = function.optString("name", "")
                        if (name.isEmpty()) continue

                        val callItem = JSONObject().apply {
                            put("type", "function_call")
                            put("name", name)
                            put("arguments", function.optString("arguments", "{}"))
                        }

                        val callId = call.optString("id", "")
                        if (callId.isNotEmpty()) {
                            callItem.put("call_id", callId)
                        }

                        input.put(callItem)
                    }
                }
            }

            val convertedContent = convertMessageContentForResponses(message.opt("content"))
            val hasContent =
                when (convertedContent) {
                    is String -> convertedContent.isNotBlank()
                    is JSONArray -> convertedContent.length() > 0
                    else -> false
                }

            if (hasContent) {
                val mappedRole =
                    when (role) {
                        "system" -> "developer"
                        else -> role
                    }

                input.put(
                    JSONObject().apply {
                        put("type", "message")
                        put("role", mappedRole)
                        put("content", convertedContent)
                    }
                )
            }
        }

        return input
    }

    private fun convertMessageContentForResponses(content: Any?): Any {
        return when (content) {
            null -> ""
            is String -> content
            is JSONArray -> {
                val convertedParts = JSONArray()

                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    when (part.optString("type", "")) {
                        "text", "output_text", "input_text" -> {
                            val text = part.optString("text", "")
                            if (text.isNotEmpty()) {
                                convertedParts.put(
                                    JSONObject().apply {
                                        put("type", "input_text")
                                        put("text", text)
                                    }
                                )
                            }
                        }

                        "image_url", "input_image" -> {
                            val imageUrl =
                                if (part.optString("type", "") == "input_image") {
                                    part.optString("image_url", "")
                                } else {
                                    part.optJSONObject("image_url")?.optString("url", "")
                                        ?: part.optString("image_url", "")
                                }
                            if (imageUrl.isNotEmpty()) {
                                convertedParts.put(
                                    JSONObject().apply {
                                        put("type", "input_image")
                                        put("image_url", imageUrl)
                                    }
                                )
                            }
                        }

                        "input_audio" -> {
                            val audioObject = part.optJSONObject("input_audio")
                            if (audioObject != null) {
                                convertedParts.put(
                                    JSONObject().apply {
                                        put("type", "input_audio")
                                        put("input_audio", audioObject)
                                    }
                                )
                            }
                        }

                        else -> {
                            val fallbackText = part.optString("text", "")
                            if (fallbackText.isNotEmpty()) {
                                convertedParts.put(
                                    JSONObject().apply {
                                        put("type", "input_text")
                                        put("text", fallbackText)
                                    }
                                )
                            }
                        }
                    }
                }

                convertedParts
            }

            else -> content.toString()
        }
    }

    private fun extractToolOutputText(content: Any?): String {
        return when (content) {
            null -> ""
            is String -> content
            is JSONArray -> {
                val parts = mutableListOf<String>()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val type = part.optString("type", "")
                    if (type == "text" || type == "output_text" || type == "input_text") {
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            parts.add(text)
                        }
                    }
                }
                if (parts.isNotEmpty()) parts.joinToString("\n") else content.toString()
            }

            else -> content.toString()
        }
    }

    private fun convertFunctionCallItemToChatToolCall(item: JSONObject): JSONObject? {
        val name = item.optString("name", "")
        if (name.isEmpty()) return null

        val arguments = item.optString("arguments", "{}").ifBlank { "{}" }
        val callId = item.optString("call_id", item.optString("id", ""))

        return JSONObject().apply {
            if (callId.isNotEmpty()) {
                put("id", callId)
            }
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", name)
                    put("arguments", arguments)
                }
            )
        }
    }
}
