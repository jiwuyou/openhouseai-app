package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.stream

/** Non-crashing compatibility service for local engines removed from the slim build. */
class UnavailableLocalAIService(
    private val provider: String,
    private val model: String,
) : AIService {
    private val message = "$provider local inference is not included in this build. Select a remote model provider."

    override val inputTokenCount: Int = 0
    override val cachedInputTokenCount: Int = 0
    override val outputTokenCount: Int = 0
    override val providerModel: String = "$provider:$model"

    override fun resetTokenCounts() = Unit
    override fun cancelStreaming() = Unit

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        Result.failure(UnsupportedOperationException(message))

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
        enableRetry: Boolean,
    ): Stream<String> = stream {
        onNonFatalError(message)
        emit(message)
    }

    override suspend fun testConnection(context: Context): Result<String> =
        Result.failure(UnsupportedOperationException(message))

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?,
    ): Int = 0
}
