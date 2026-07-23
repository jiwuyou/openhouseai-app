package com.ai.assistance.operit.pi

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.data.model.ApiKeyAvailabilityStatus
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.rescue.pi.RescueToolCatalog
import com.ai.assistance.operit.rescue.pi.RescueToolDispatcher
import com.ai.assistance.operit.rescue.pi.RescueModelConfigStore
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.share
import com.ai.assistance.operit.util.stream.stream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Operit's single main-chat adapter for pi_agent_rust.
 *
 * Rust owns the provider request, agent loop and JSONL session. Kotlin owns Android tool execution,
 * permission prompts, lifecycle hooks and the existing UI stream.
 */
class RescuePiChatEngine private constructor(context: Context) {
    data class TurnRequest(
        val chatId: String,
        val sessionKey: String = chatId,
        val message: String,
        val userMessageTimestamp: Long? = null,
        val workingDirectory: String? = null,
        val forkFromSessionKey: String? = null,
        val forkUserMessage: String? = null,
        val onState: suspend (InputProcessingState) -> Unit,
        val onToolInvocation: suspend (String) -> Unit = {},
    )

    data class Usage(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
    )

    private data class PendingTurn(
        val chatId: String,
        val sessionKey: String,
        val requestId: String,
        val events: Channel<JSONObject>,
        val onState: suspend (InputProcessingState) -> Unit,
        val onToolInvocation: suspend (String) -> Unit,
        val request: TurnRequest,
        val toolCatalog: RescueToolCatalog,
    )

    private data class PendingCompaction(
        val chatId: String,
        val sessionKey: String,
        val requestId: String,
        val events: Channel<JSONObject>,
    )

    companion object {
        private const val TAG = "RescuePiChatEngine"
        private const val POLL_BATCH_SIZE = 128
        private const val IDLE_POLL_DELAY_MS = 20L
        private const val TURN_EVENT_CAPACITY = 1024
        private const val STREAM_REPLAY_CHUNKS = 65_536
        private const val RESCUE_SYSTEM_PROMPT =
            """You are WuxianPi Rescue AI, a complete Android-resident assistant that remains available when the Termux Node Pi runtime is unavailable. Converse normally and use the registered Android file, HTTP, shell, terminal-session, and deterministic repair tools when they help. Use execute_android_command only for the Android environment. Use execute_termux_command for direct, unrestricted Termux commands; its timeout only stops waiting and does not terminate the Termux process. Use termux_exec_command plus termux_write_stdin for managed, persistent, Codex-style Termux commands that may run for a long time or need interaction and reconnection. If termux_exec_command reports setup_required because tmux is missing, call execute_termux_command with `pkg install -y tmux`, verify tmux is available, and retry termux_exec_command. Do not silently install dependencies before setup_required, and never fall back from Termux to Android or Ubuntu. create_terminal_session and the existing terminal-session execution tools are for Ubuntu through Termux and tmux. get_terminal_session_screen, input_in_terminal_session, and close_terminal_session accept both Ubuntu and managed Termux session IDs. Never substitute one execution environment for another after a failure. Diagnose before changing state, report tool failures, and continue with another valid path when possible. Runtime repair tools are asynchronous when they return a jobId; inspect the job with repair_job_status before claiming recovery. Never claim a tool ran unless you actually called it."""

        @Volatile private var INSTANCE: RescuePiChatEngine? = null

        fun getInstance(context: Context): RescuePiChatEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RescuePiChatEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelConfigStore = RescueModelConfigStore(appContext)
    private val androidToolDispatcher = RescueToolDispatcher(appContext)
    private val pendingTurns = ConcurrentHashMap<String, PendingTurn>()
    private val pendingCompactions = ConcurrentHashMap<String, PendingCompaction>()
    private val activeRequestByChatId = ConcurrentHashMap<String, String>()
    private val usageByChatId = ConcurrentHashMap<String, Usage>()
    private val openedSessionKeys = ConcurrentHashMap.newKeySet<String>()
    private val androidToolJobs = ConcurrentHashMap<String, Job>()
    private val sessionDirectory = File(appContext.filesDir, ".pi/agent/sessions-rescue")
    private val activeSessionPreferences =
        appContext.getSharedPreferences("pi_rescue_active_sessions", Context.MODE_PRIVATE)
    private val sessionLifecycleLock = Any()

    init {
        sessionDirectory.mkdirs()
        engineScope.launch { pollNativeEvents() }
    }

    fun getUsage(chatId: String): Usage = usageByChatId[chatId] ?: Usage()

    /** Persist a model copy that remains available when Termux and the Node runtime are down. */
    fun configureModel(config: ModelConfigData) {
        modelConfigStore.save(config)
    }

    /** Explicitly refresh the independent rescue model copy from Operit's Android settings. */
    suspend fun syncModelFromOperit(): ModelConfigData = modelConfigStore.syncFromOperit()

    suspend fun defaultModelDisplay(): Pair<String, String> {
        val config = modelConfigStore.load()
        return config.apiProviderTypeId to config.modelName.substringBefore(',').trim()
    }

    fun getActiveSessionKey(chatId: String): String {
        return activeSessionPreferences.getString(chatId, null) ?: chatId
    }

    fun createVariantSessionKey(chatId: String, targetMessageTimestamp: Long): String {
        return "$chatId::variant::$targetMessageTimestamp::${UUID.randomUUID()}"
    }

    fun activateSession(chatId: String, sessionKey: String) {
        activeSessionPreferences.edit().putString(chatId, sessionKey).apply()
    }

    fun replaceActiveSession(chatId: String, newSessionKey: String) {
        synchronized(sessionLifecycleLock) {
            val previousSessionKey = getActiveSessionKey(chatId)
            if (previousSessionKey == newSessionKey) return
            RescueNativeBridge.nativeCancel(previousSessionKey)
            RescueNativeBridge.nativeCloseSession(previousSessionKey)
            openedSessionKeys.remove(previousSessionKey)
            activeSessionPreferences.edit().putString(chatId, newSessionKey).commit()
        }
    }

    fun discardSession(chatId: String, sessionKey: String) {
        synchronized(sessionLifecycleLock) {
            pendingTurns.values
                .filter { it.sessionKey == sessionKey }
                .forEach { pending ->
                    pendingTurns.remove(pending.requestId, pending)
                    activeRequestByChatId.remove(chatId, pending.requestId)
                    pending.events.close(CancellationException("Variant session discarded"))
                }
            pendingCompactions.values
                .filter { it.sessionKey == sessionKey }
                .forEach { pending ->
                    pendingCompactions.remove(pending.requestId, pending)
                    pending.events.close(CancellationException("Variant session discarded"))
                }
            cancelAndroidToolWork(chatId)
            RescueNativeBridge.nativeCancel(sessionKey)
            RescueNativeBridge.nativeCloseSession(sessionKey)
            openedSessionKeys.remove(sessionKey)
            if (getActiveSessionKey(chatId) == sessionKey) {
                activeSessionPreferences.edit().remove(chatId).commit()
            }
        }
    }

    fun rotateSession(chatId: String): String {
        val previousSessionKey = getActiveSessionKey(chatId)
        cancel(chatId)
        RescueNativeBridge.nativeCloseSession(previousSessionKey)
        openedSessionKeys.remove(previousSessionKey)
        val sessionKey = "$chatId::room::${UUID.randomUUID()}"
        activateSession(chatId, sessionKey)
        return sessionKey
    }

    suspend fun compact(
        chatId: String,
        instructions: String,
        onState: suspend (InputProcessingState) -> Unit,
    ): Boolean {
        val sessionKey = getActiveSessionKey(chatId)
        if (!openedSessionKeys.contains(sessionKey)) return false
        val requestId = UUID.randomUUID().toString()
        val eventChannel = Channel<JSONObject>(32)
        val pending = PendingCompaction(chatId, sessionKey, requestId, eventChannel)
        pendingCompactions[requestId] = pending

        try {
            val accepted =
                JSONObject(RescueNativeBridge.nativeCompact(sessionKey, instructions, requestId))
            check(accepted.optBoolean("ok", false)) {
                accepted.optString("error", "Pi Agent rejected manual compaction")
            }
            while (true) {
                val event = eventChannel.receive()
                when (event.getString("type")) {
                    "auto_compaction_start" -> {
                        onState(
                            InputProcessingState.Summarizing(
                                "Rescue Pi 正在压缩上下文"
                            )
                        )
                    }
                    "auto_compaction_end" -> {
                        check(event.optBoolean("success", false)) {
                            event.optString("error", "Pi Agent compaction failed")
                        }
                        onState(InputProcessingState.Idle)
                        return true
                    }
                    "error" -> {
                        throw IllegalStateException(
                            event.optString("message", "Pi Agent compaction failed")
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            RescueNativeBridge.nativeCancel(sessionKey)
            throw error
        } catch (error: Exception) {
            RescueNativeBridge.nativeCancel(sessionKey)
            throw error
        } finally {
            pendingCompactions.remove(requestId, pending)
            eventChannel.close()
        }
    }

    fun send(request: TurnRequest, streamScope: CoroutineScope): SharedStream<String> {
        val requestId = UUID.randomUUID().toString()
        val eventChannel = Channel<JSONObject>(TURN_EVENT_CAPACITY)
        val toolCatalog = RescueToolCatalog.default()
        val pending =
            PendingTurn(
                chatId = request.chatId,
                sessionKey = request.sessionKey,
                requestId = requestId,
                events = eventChannel,
                onState = request.onState,
                onToolInvocation = request.onToolInvocation,
                request = request,
                toolCatalog = toolCatalog,
            )

        val output = stream<String> {
            pendingTurns[requestId] = pending
            activeRequestByChatId[request.chatId] = requestId
            usageByChatId[request.chatId] = Usage()

            try {
                request.onState(InputProcessingState.Connecting("Connecting to Pi Agent"))
                openSession(request, toolCatalog)
                val accepted =
                    JSONObject(
                        RescueNativeBridge.nativePrompt(
                            request.sessionKey,
                            request.message,
                            requestId,
                        )
                    )
                check(accepted.optBoolean("ok", false)) {
                    accepted.optString("error", "Pi Agent rejected the prompt")
                }

                request.onState(InputProcessingState.Receiving("Receiving from Pi Agent"))
                val renderedToolCalls = mutableSetOf<String>()
                val renderedToolResults = mutableSetOf<String>()
                var thinkingOpen = false
                suspend fun closeThinkingMarkup() {
                    if (thinkingOpen) {
                        emit("</think>\n")
                        thinkingOpen = false
                    }
                }
                var completed = false
                while (!completed) {
                    val event = eventChannel.receive()
                    val eventType = event.getString("type")
                    when (eventType) {
                        "text_delta" -> {
                            closeThinkingMarkup()
                            emit(event.getString("delta"))
                        }
                        "thinking_delta" -> {
                            if (!thinkingOpen) {
                                emit("\n<think>")
                                thinkingOpen = true
                            }
                            emit(event.getString("delta"))
                        }
                        "tool_start", "host_tool_request" -> {
                            closeThinkingMarkup()
                            val toolCallId = event.getString("toolCallId")
                            if (renderedToolCalls.add(toolCallId)) {
                                emit(buildToolCallMarkup(event))
                            }
                        }
                        "tool_update" -> Unit
                        "auto_compaction_start" -> {
                            closeThinkingMarkup()
                            request.onState(
                                InputProcessingState.Summarizing(
                                    "Rescue Pi 正在压缩上下文"
                                )
                            )
                        }
                        "auto_compaction_end" -> {
                            check(event.optBoolean("success", false)) {
                                event.optString("error", "Pi Agent compaction failed")
                            }
                            request.onState(
                                InputProcessingState.Receiving("Receiving from Pi Agent")
                            )
                        }
                        "tool_end" -> {
                            closeThinkingMarkup()
                            val toolCallId = event.getString("toolCallId")
                            if (renderedToolResults.add(toolCallId)) {
                                emit(buildToolResultMarkup(event))
                            }
                        }
                        "usage" -> {
                            usageByChatId[request.chatId] =
                                Usage(
                                    inputTokens = event.optInt("inputTokens", 0),
                                    outputTokens = event.optInt("outputTokens", 0),
                                    cachedInputTokens = event.optInt("cachedInputTokens", 0),
                                )
                        }
                        "agent_end", "prompt_completed" -> {
                            closeThinkingMarkup()
                            completed = true
                        }
                        "error", "prompt_failed" -> {
                            closeThinkingMarkup()
                            throw IllegalStateException(event.optString("message", "Pi Agent failed"))
                        }
                    }
                }
            } catch (error: CancellationException) {
                RescueNativeBridge.nativeCancel(request.sessionKey)
                throw error
            } catch (error: Exception) {
                RescueNativeBridge.nativeCancel(request.sessionKey)
                cancelAndroidToolWork(request.chatId)
                AppLogger.e(TAG, "Pi Agent turn failed", error)

                // `send()` is shared through a hot stream whose upstream job is not allowed to
                // fail.  Returning the provider/agent failure as text keeps the current turn
                // readable and lets the stream complete normally, so a subsequent turn can be
                // retried.  Cancellation remains exceptional above and is still propagated.
                val errorMessages =
                    generateSequence<Throwable>(error) { it.cause }
                        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                        .toList()
                val originalMessage =
                    errorMessages.firstOrNull()
                        ?: error.javaClass.simpleName.takeIf(String::isNotEmpty)
                        ?: "Unknown Pi Agent error"
                val normalizedErrorChain = errorMessages.joinToString(" | ").replace(Regex("\\s+"), " ")
                val message =
                    if (normalizedErrorChain.contains("http 402", ignoreCase = true) ||
                        normalizedErrorChain.contains("insufficient balance", ignoreCase = true)
                    ) {
                        "API 余额不足（HTTP 402，Insufficient Balance）。请更换有额度的模型/API 配置后重试。"
                    } else {
                        "Pi Agent error: $originalMessage"
                    }
                request.onState(InputProcessingState.Error(message))
                emit("\n\n$message\n")
            } finally {
                pendingTurns.remove(requestId, pending)
                activeRequestByChatId.remove(request.chatId, requestId)
                eventChannel.close()
            }
        }

        return output.share(scope = streamScope, replay = STREAM_REPLAY_CHUNKS)
    }

    fun cancel(chatId: String): Boolean {
        cancelCompactions(chatId)
        val requestId = activeRequestByChatId[chatId]
        val pending = requestId?.let { pendingTurns.remove(it) }
        if (pending != null) {
            pending.events.close(CancellationException("User cancelled"))
            cancelAndroidToolWork(chatId)
            activeRequestByChatId.remove(chatId, pending.requestId)
            return RescueNativeBridge.nativeCancel(pending.sessionKey)
        }
        cancelAndroidToolWork(chatId)
        return RescueNativeBridge.nativeCancel(getActiveSessionKey(chatId))
    }

    fun cancelCompaction(chatId: String): Boolean {
        val hadPending = cancelCompactions(chatId)
        return if (hadPending) {
            RescueNativeBridge.nativeCancel(getActiveSessionKey(chatId))
        } else {
            false
        }
    }

    fun closeSession(chatId: String): Boolean {
        val sessionKey = getActiveSessionKey(chatId)
        cancel(chatId)
        usageByChatId.remove(chatId)
        activeSessionPreferences.edit().remove(chatId).apply()
        openedSessionKeys.remove(sessionKey)
        return RescueNativeBridge.nativeCloseSession(sessionKey)
    }

    private suspend fun openSession(request: TurnRequest, toolCatalog: RescueToolCatalog) {
        require(request.forkFromSessionKey == null) {
            "Rescue Pi does not support forking an existing session yet"
        }
        val config = modelConfigStore.load()
        val api = providerApi(config)
        val contextWindow = (config.contextLength * 1024).toInt().coerceAtLeast(4096)
        val compactionEnabled = config.enableSummary && contextWindow >= 4096
        val normalizedThreshold = config.summaryTokenThreshold.toDouble().coerceIn(0.1, 0.95)
        val compactionReserveTokens =
            (contextWindow * (1.0 - normalizedThreshold)).toInt().coerceAtLeast(1024)
        val compactionKeepRecentTokens =
            (contextWindow * 0.2).toInt().coerceAtLeast(1024)
        val configJson =
            JSONObject()
                .put("chatId", request.sessionKey)
                .put("sessionPath", stableSessionPath(request.sessionKey).absolutePath)
                .put("sessionDir", sessionDirectory.absolutePath)
                .put("workingDirectory", request.workingDirectory ?: appContext.filesDir.absolutePath)
                .put("provider", config.apiProviderTypeId)
                .put("api", api)
                .put("model", config.modelName.substringBefore(',').trim())
                .put("baseUrl", config.apiEndpoint)
                .put("apiKey", resolveApiKey(config))
                .put("headers", parseJsonObject(config.customHeaders, "customHeaders"))
                .put("systemPrompt", RESCUE_SYSTEM_PROMPT)
                .put("maxTokens", if (config.maxTokensEnabled) config.maxTokens else JSONObject.NULL)
                .put(
                    "temperature",
                    if (config.temperatureEnabled) config.temperature else JSONObject.NULL,
                )
                .put("contextWindow", contextWindow)
                .put("enableCompaction", compactionEnabled)
                .put("compactionReserveTokens", compactionReserveTokens)
                .put("compactionKeepRecentTokens", compactionKeepRecentTokens)
                .put("thinkingLevel", "medium")
                .put("tools", toolCatalog.toJsonArray())
                .put("history", JSONArray())

        val result = JSONObject(RescueNativeBridge.nativeOpenSession(configJson.toString()))
        check(result.optBoolean("ok", false)) {
            result.optString("error", "Unable to open Pi Agent session")
        }
        openedSessionKeys.add(request.sessionKey)
    }

    private suspend fun pollNativeEvents() {
        while (engineScope.isActive) {
            if (pendingTurns.isEmpty() && pendingCompactions.isEmpty()) {
                delay(IDLE_POLL_DELAY_MS)
                continue
            }

            try {
                val events = JSONArray(RescueNativeBridge.nativePollEvents(POLL_BATCH_SIZE))
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    if (event.getString("type") == "event_queue_overflow") {
                        val error =
                            IllegalStateException(
                                "Pi Agent event queue overflowed by ${event.optLong("dropped", 0)} events"
                            )
                        pendingTurns.values.toList().forEach { pending ->
                            failPendingTurn(pending, error)
                        }
                        pendingCompactions.values.toList().forEach { pending ->
                            failPendingCompaction(pending, error)
                        }
                        continue
                    }
                    val requestId = event.getString("requestId")
                    val pending = pendingTurns[requestId]
                    if (pending == null) {
                        val compaction = pendingCompactions[requestId] ?: continue
                        if (event.getString("chatId") != compaction.sessionKey) continue
                        if (compaction.events.trySend(event).isFailure) {
                            failPendingCompaction(
                                compaction,
                                IllegalStateException("Pi compaction event buffer is full or closed"),
                            )
                        }
                        continue
                    }
                    if (event.getString("chatId") != pending.sessionKey) continue

                    if (event.getString("type") == "host_tool_request") {
                        executeAndroidTool(pending, event)
                    }
                    val delivery = pending.events.trySend(event)
                    if (delivery.isFailure) {
                        failPendingTurn(
                            pending,
                            IllegalStateException("Pi Agent turn event buffer is full or closed"),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(TAG, "Pi event polling failed", error)
                pendingTurns.values.toList().forEach { pending -> failPendingTurn(pending, error) }
                pendingCompactions.values.toList().forEach { pending ->
                    failPendingCompaction(pending, error)
                }
            }

            delay(IDLE_POLL_DELAY_MS)
        }
    }

    private fun executeAndroidTool(pending: PendingTurn, event: JSONObject) {
        val toolCallId = event.getString("toolCallId")
        val toolName = event.getString("toolName")
        val args = event.getJSONObject("args")

        val jobKey = "${pending.chatId}:$toolCallId"
        androidToolJobs[jobKey]?.cancel()
        androidToolJobs[jobKey] =
            engineScope.launch {
                try {
                    pending.onToolInvocation(toolName)
                    pending.onState(InputProcessingState.ExecutingTool(toolName))
                    val completion =
                        androidToolDispatcher.execute(
                            catalog = pending.toolCatalog,
                            toolName = toolName,
                            args = args,
                            onUpdate = {
                                pending.onState(
                                    InputProcessingState.ProcessingToolResult(toolName)
                                )
                            },
                        )
                    check(
                        RescueNativeBridge.nativeCompleteHostTool(
                            toolCallId,
                            completion.toJson().toString(),
                        )
                    ) {
                        "Pi Agent rejected tool result for $toolCallId"
                    }
                    pending.onState(InputProcessingState.Receiving("Receiving from Pi Agent"))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLogger.e(TAG, "Android tool failed: $toolName", error)
                    val failure =
                        JSONObject()
                            .put(
                                "content",
                                error.message?.takeIf { it.isNotBlank() }
                                    ?: "Tool $toolName failed",
                            )
                            .put("details", JSONObject().put("toolName", toolName))
                            .put("isError", true)
                    val failureDelivered =
                        try {
                            RescueNativeBridge.nativeCompleteHostTool(
                                toolCallId,
                                failure.toString(),
                            )
                        } catch (completionError: Exception) {
                            AppLogger.e(
                                TAG,
                                "Failed to return Android tool error to Rescue Pi: $toolName",
                                completionError,
                            )
                            false
                        }
                    if (!failureDelivered) {
                        AppLogger.e(
                            TAG,
                            "Rescue Pi rejected Android tool error for $toolCallId ($toolName)",
                        )
                    }
                    pending.onState(InputProcessingState.Receiving("Receiving from Rescue Pi"))
                } finally {
                    androidToolJobs.remove(jobKey)
                }
            }
    }

    private fun cancelAndroidToolWork(chatId: String) {
        val jobPrefix = "$chatId:"
        androidToolJobs.entries.removeIf { (key, job) ->
            if (key.startsWith(jobPrefix)) job.cancel()
            key.startsWith(jobPrefix)
        }
    }

    private fun cancelCompactions(chatId: String): Boolean {
        val affected =
            pendingCompactions.values
            .filter { it.chatId == chatId }
        affected.forEach { compaction ->
            pendingCompactions.remove(compaction.requestId, compaction)
            compaction.events.close(CancellationException("User cancelled"))
        }
        return affected.isNotEmpty()
    }

    private fun failPendingTurn(pending: PendingTurn, error: Throwable) {
        if (!pendingTurns.remove(pending.requestId, pending)) return
        activeRequestByChatId.remove(pending.chatId, pending.requestId)
        RescueNativeBridge.nativeCancel(pending.sessionKey)
        cancelAndroidToolWork(pending.chatId)
        pending.events.close(error)
    }

    private fun failPendingCompaction(pending: PendingCompaction, error: Throwable) {
        if (!pendingCompactions.remove(pending.requestId, pending)) return
        RescueNativeBridge.nativeCancel(pending.sessionKey)
        pending.events.close(error)
    }

    private fun providerApi(config: ModelConfigData): String {
        val provider =
            ApiProviderType.fromProviderTypeId(config.apiProviderTypeId)
                ?: throw IllegalArgumentException(
                    "Pi direct provider does not support provider ${config.apiProviderTypeId}"
                )
        require(provider != ApiProviderType.MNN) {
            "MNN local models do not yet have a direct Pi provider"
        }
        require(provider != ApiProviderType.LLAMA_CPP) {
            "Android llama.cpp local models do not yet have a direct Pi provider"
        }
        require(config.apiEndpoint.isNotBlank()) {
            "Pi direct provider requires a non-empty API endpoint for ${config.name}"
        }

        val unsupportedFeatures = buildList {
            if (config.hasCustomParameters) add("custom parameters")
            if (config.topPEnabled) add("topP")
            if (config.topKEnabled) add("topK")
            if (config.presencePenaltyEnabled) add("presencePenalty")
            if (config.frequencyPenaltyEnabled) add("frequencyPenalty")
            if (config.repetitionPenaltyEnabled) add("repetitionPenalty")
            if (config.enableDirectImageProcessing) add("direct image input")
            if (config.enableDirectAudioProcessing) add("direct audio input")
            if (config.enableDirectVideoProcessing) add("direct video input")
            if (config.enableGoogleSearch) add("Google Search grounding")
            if (config.enableClaude1hPromptCache) add("Claude 1h prompt cache")
            if (config.requestLimitPerMinute > 0) add("request rate limiting")
            if (config.maxConcurrentRequests > 0) add("provider concurrency limiting")
        }
        require(unsupportedFeatures.isEmpty()) {
            "Pi direct provider does not support ${unsupportedFeatures.joinToString()} for ${config.name}"
        }
        return when (provider) {
            ApiProviderType.OPENAI_RESPONSES,
            ApiProviderType.OPENAI_RESPONSES_GENERIC -> "openai-responses"
            ApiProviderType.ANTHROPIC,
            ApiProviderType.ANTHROPIC_GENERIC -> "anthropic-messages"
            ApiProviderType.GOOGLE,
            ApiProviderType.GEMINI_GENERIC -> "google-generative-ai"
            else -> "openai-completions"
        }
    }

    private fun resolveApiKey(config: ModelConfigData): String {
        if (!config.useMultipleApiKeys) return config.apiKey

        val enabledKeys = config.apiKeyPool.filter { it.isEnabled }
        val hasAvailabilityMarks =
            enabledKeys.any { it.availabilityStatus != ApiKeyAvailabilityStatus.UNTESTED }
        val candidates =
            if (hasAvailabilityMarks) {
                enabledKeys.filter { it.availabilityStatus == ApiKeyAvailabilityStatus.AVAILABLE }
            } else {
                enabledKeys
            }
        require(candidates.isNotEmpty()) { "No usable API key is enabled for ${config.name}" }
        return candidates[Math.floorMod(config.currentKeyIndex, candidates.size)].key
    }

    private fun stableSessionPath(chatId: String): File {
        val readable = chatId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "chat" }
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(chatId.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { byte -> "%02x".format(byte) }
        return File(sessionDirectory, "$readable-$digest.jsonl")
    }

    private fun buildToolCallMarkup(event: JSONObject): String {
        val tagName = ChatMarkupRegex.generateRandomToolTagName()
        val args = event.getJSONObject("args")
        return buildString {
            append('\n').append('<').append(tagName).append(" name=\"")
                .append(escapeXml(event.getString("toolName"))).append("\">")
            args.keys().forEach { key ->
                append("\n<param name=\"").append(escapeXml(key)).append("\">")
                    .append(escapeXml(jsonValueToParameter(args.get(key))))
                    .append("</param>")
            }
            append("\n</").append(tagName).append(">\n")
        }
    }

    private fun buildToolResultMarkup(event: JSONObject): String {
        val result =
            ToolResult(
                toolName = event.getString("toolName"),
                success = !event.optBoolean("isError", false),
                result = com.ai.assistance.operit.core.tools.StringResultData(
                    event.optString("content", "")
                ),
                error = event.optString("error").takeIf { it.isNotBlank() },
            )
        return "\n${ConversationMarkupManager.formatToolResultForMessage(result)}\n"
    }

    private fun parseJsonObject(raw: String, fieldName: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("$fieldName must be a JSON object", error)
        }
    }

    private fun jsonValueToParameter(value: Any): String {
        return when (value) {
            is JSONObject, is JSONArray -> value.toString()
            JSONObject.NULL -> "null"
            else -> value.toString()
        }
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
