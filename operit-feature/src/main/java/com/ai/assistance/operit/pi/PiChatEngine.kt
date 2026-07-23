package com.ai.assistance.operit.pi

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.share
import com.ai.assistance.operit.util.stream.stream
import com.wuxianpi.pi.PiConnectionState
import com.wuxianpi.pi.PiEvent
import com.wuxianpi.pi.PiServiceConfig
import com.wuxianpi.pi.PiSessionRef
import com.wuxianpi.pi.WuxianPiClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Thin Operit UI facade over the Node process that embeds the official Pi SDK.
 *
 * Android owns only chat/session binding and rendering. Pi owns providers, extensions, tools,
 * retries, compaction and the agent loop. In particular, tool failures and `agent_end` never close
 * an Operit turn; only `agent_settled` or an idle `prompt_completed` event is terminal.
 */
class PiChatEngine private constructor(context: Context) {
    data class TurnRequest(
        val chatId: String,
        val sessionKey: String = chatId,
        val message: String,
        val userMessageTimestamp: Long? = null,
        val workingDirectory: String? = null,
        val forkFromSessionKey: String? = null,
        val forkUserMessage: String? = null,
        val modelBinding: PiModelBinding? = null,
        val onState: suspend (InputProcessingState) -> Unit,
        val onToolInvocation: suspend (String) -> Unit = {},
    )

    data class Usage(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
    )

    data class SessionIdentity(
        val connectionId: String,
        val sessionId: String,
        val eventStreamId: String,
    )

    private data class SessionRuntime(
        val client: WuxianPiClient,
        val turnGate: PiSessionTurnGate = PiSessionTurnGate(),
        val modelBinder: PiSessionModelBinder = PiSessionModelBinder(),
        @Volatile var session: PiSessionRef? = null,
        @Volatile var identity: SessionIdentity? = null,
    )

    companion object {
        private const val TAG = "PiChatEngine"
        private const val REPLAY_CHUNKS = 65_536
        private const val SESSION_BINDINGS = "wuxianpi_node_session_bindings"

        @Volatile private var INSTANCE: PiChatEngine? = null

        fun getInstance(context: Context): PiChatEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PiChatEngine(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceConfig = PiServiceConfig(OPERIT_PI_RUNTIME_URL.toHttpUrl())
    private val bindings = appContext.getSharedPreferences(SESSION_BINDINGS, Context.MODE_PRIVATE)
    private val entryBindings =
        appContext.getSharedPreferences("wuxianpi_pi_entry_bindings", Context.MODE_PRIVATE)
    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()
    private val usageByChatId = ConcurrentHashMap<String, Usage>()
    private val activeSessionKeys =
        appContext.getSharedPreferences("wuxianpi_active_chat_sessions", Context.MODE_PRIVATE)

    fun getUsage(chatId: String): Usage = usageByChatId[chatId] ?: Usage()

    fun getIdentity(sessionKey: String): SessionIdentity? = runtimes[sessionKey]?.identity

    fun getActiveSessionKey(chatId: String): String =
        activeSessionKeys.getString(chatId, null) ?: chatId

    fun createVariantSessionKey(chatId: String, targetMessageTimestamp: Long): String =
        "$chatId::variant::$targetMessageTimestamp::${UUID.randomUUID()}"

    fun activateSession(chatId: String, sessionKey: String) {
        activeSessionKeys.edit().putString(chatId, sessionKey).apply()
    }

    fun replaceActiveSession(chatId: String, sessionKey: String) {
        val previous = getActiveSessionKey(chatId)
        if (previous == sessionKey) return
        closeRuntime(previous, removeBinding = false)
        activateSession(chatId, sessionKey)
    }

    fun discardSession(chatId: String, sessionKey: String) {
        closeRuntime(sessionKey, removeBinding = true)
        if (getActiveSessionKey(chatId) == sessionKey) {
            activeSessionKeys.edit().remove(chatId).apply()
        }
    }

    fun rotateSession(chatId: String): String {
        val previous = getActiveSessionKey(chatId)
        closeRuntime(previous, removeBinding = true)
        val replacement = "$chatId::room::${UUID.randomUUID()}"
        activateSession(chatId, replacement)
        return replacement
    }

    fun closeSession(chatId: String): Boolean {
        val sessionKey = getActiveSessionKey(chatId)
        val existed = runtimes.containsKey(sessionKey) || bindings.contains(sessionKey)
        closeRuntime(sessionKey, removeBinding = true)
        activeSessionKeys.edit().remove(chatId).apply()
        usageByChatId.remove(chatId)
        return existed
    }

    fun cancel(chatId: String): Boolean {
        val runtime = runtimes[getActiveSessionKey(chatId)] ?: return false
        runtime.turnGate.cancelActive(CancellationException("User cancelled"))
        scope.launch { runCatching { runtime.client.abort() } }
        return true
    }

    suspend fun compact(
        chatId: String,
        instructions: String,
        onState: suspend (InputProcessingState) -> Unit,
    ): Boolean {
        val runtime = runtimeFor(getActiveSessionKey(chatId))
        return runtime.turnGate.exclusive {
            ensureSession(runtime, getActiveSessionKey(chatId), null)
            onState(InputProcessingState.Summarizing("Pi 正在压缩上下文"))
            val response = runtime.client.compact(instructions.takeIf(String::isNotBlank))
            if (!response.success) {
                onState(InputProcessingState.Error(response.error ?: "Pi 上下文压缩失败"))
                false
            } else {
                onState(InputProcessingState.Idle)
                true
            }
        }
    }

    fun send(request: TurnRequest, streamScope: CoroutineScope): SharedStream<String> {
        val output = stream<String> {
            val runtime = runtimeFor(request.sessionKey)
            val turnJob = requireNotNull(coroutineContext[Job]) { "Pi turn requires a coroutine job" }
            runtime.turnGate.run(turnJob) {
                val events = Channel<PiEvent>(Channel.UNLIMITED)
                val collectionJob = scope.launch {
                    runtime.client.events.collect { event ->
                        val sessionId = runtime.session?.sessionId
                        if (event.sessionId == null || event.sessionId == sessionId) {
                            updateIdentity(runtime, event)
                            events.send(event)
                        }
                    }
                }
                usageByChatId[request.chatId] = Usage()

                var thinkingOpen = false
                suspend fun closeThinking() {
                    if (thinkingOpen) {
                        emit("</think>\n")
                        thinkingOpen = false
                    }
                }

                try {
                    request.onState(InputProcessingState.Connecting("正在连接 Pi"))
                    val session =
                        ensureSession(
                            runtime = runtime,
                            sessionKey = request.sessionKey,
                            cwd = request.workingDirectory,
                            forkFromSessionKey = request.forkFromSessionKey,
                            forkUserMessageTimestamp = request.userMessageTimestamp,
                            forkUserMessage = request.forkUserMessage,
                            modelBinding = request.modelBinding,
                        )
                    request.onState(InputProcessingState.Receiving("正在接收 Pi 响应"))
                    val accepted = runtime.client.prompt(request.message)
                    if (!accepted.success) {
                        emitInlineError(accepted.error ?: "Pi 拒绝了消息")
                        request.onState(InputProcessingState.Error(accepted.error ?: "Pi 拒绝了消息"))
                        return@run
                    }
                    val userEntryId = promptUserEntryId(accepted.data)
                    request.userMessageTimestamp?.let { timestamp ->
                        entryBindings.edit()
                            .putString(entryBindingKey(request.sessionKey, timestamp), userEntryId)
                            .apply()
                    }

                    val renderedToolCalls = mutableSetOf<String>()
                    val renderedToolResults = mutableSetOf<String>()
                    var terminal = false
                    while (!terminal) {
                        val event = events.receive()
                        if (event.sessionId != null && event.sessionId != session.sessionId) continue
                        when (event) {
                            is PiEvent.TextDelta -> {
                                closeThinking()
                                emit(event.delta)
                            }
                            is PiEvent.ThinkingDelta -> {
                                if (!thinkingOpen) {
                                    emit("\n<think>")
                                    thinkingOpen = true
                                }
                                emit(event.delta)
                            }
                            is PiEvent.ToolStart -> {
                                closeThinking()
                                request.onToolInvocation(event.name)
                                request.onState(InputProcessingState.ExecutingTool(event.name))
                                if (renderedToolCalls.add(event.callId)) emit(renderToolCall(event))
                            }
                            is PiEvent.ToolUpdate -> {
                                request.onState(InputProcessingState.ProcessingToolResult(event.name))
                            }
                            is PiEvent.ToolEnd -> {
                                closeThinking()
                                if (renderedToolResults.add(event.callId)) emit(renderToolResult(event))
                                request.onState(InputProcessingState.Receiving("正在接收 Pi 响应"))
                            }
                            is PiEvent.RuntimeError -> {
                                closeThinking()
                                emitInlineError(event.message)
                                request.onState(InputProcessingState.Error(event.message))
                            }
                            is PiEvent.ExtensionError -> {
                                closeThinking()
                                emitInlineError(event.error)
                            }
                            is PiEvent.ProtocolError -> {
                                closeThinking()
                                emitInlineError("Pi 协议错误：${event.message}")
                            }
                            is PiEvent.CommandError -> {
                                closeThinking()
                                emitInlineError(event.response.error ?: "Pi 命令执行失败")
                            }
                            is PiEvent.AgentSettled -> terminal = true
                            is PiEvent.PromptCompleted -> if (!event.isRunning) terminal = true
                            is PiEvent.Other -> updateUsage(request.chatId, event)
                            // `agent_end` is deliberately non-terminal: Pi may retry/compact/continue.
                            is PiEvent.AgentEnd,
                            is PiEvent.AgentStart,
                            is PiEvent.ExtensionUiRequest,
                            is PiEvent.SessionRecovered -> Unit
                        }
                    }
                    closeThinking()
                } catch (error: CancellationException) {
                    scope.launch { runCatching { runtime.client.abort() } }
                    throw error
                } catch (error: Exception) {
                    AppLogger.e(TAG, "Pi turn failed", error)
                    val message = readableError(error)
                    closeThinking()
                    emitInlineError(message)
                    request.onState(InputProcessingState.Error(message))
                } finally {
                    collectionJob.cancel()
                    events.close()
                }
            }
        }
        return output.share(scope = streamScope, replay = REPLAY_CHUNKS)
    }

    private fun runtimeFor(sessionKey: String): SessionRuntime =
        runtimes.getOrPut(sessionKey) { SessionRuntime(WuxianPiClient(serviceConfig)) }

    private suspend fun ensureSession(
        runtime: SessionRuntime,
        sessionKey: String,
        cwd: String?,
        forkFromSessionKey: String? = null,
        forkUserMessageTimestamp: Long? = null,
        forkUserMessage: String? = null,
        modelBinding: PiModelBinding? = null,
    ): PiSessionRef {
        runtime.session?.let {
            ensureModelBinding(runtime, modelBinding)
            return it
        }
        if (runtime.client.connection.value !is PiConnectionState.Connected) {
            runtime.client.connect()
        }
        val storedPath = bindings.getString(sessionKey, null)
        val session = if (!storedPath.isNullOrBlank()) {
            runCatching { runtime.client.openSession(storedPath) }
                .onFailure { AppLogger.w(TAG, "Unable to reopen Pi session $storedPath", it) }
                .getOrNull()
        } else {
            null
        } ?: if (!forkFromSessionKey.isNullOrBlank()) {
            forkSession(
                runtime = runtime,
                sourceSessionKey = forkFromSessionKey,
                userMessageTimestamp = requireNotNull(forkUserMessageTimestamp) {
                    "A Pi regeneration fork requires the original user message timestamp"
                },
                userMessage = requireNotNull(forkUserMessage) {
                    "A Pi regeneration fork requires the original user message"
                },
            )
        } else {
            runtime.client.createSession(cwd)
        }
        runtime.session = session
        runtime.modelBinder.onSessionAttached()
        ensureModelBinding(runtime, modelBinding)
        bindings.edit().putString(sessionKey, session.sessionPath).apply()
        runtime.client.runtimeReady.value?.connectionId?.let { connectionId ->
            session.eventStreamId?.let { streamId ->
                runtime.identity = SessionIdentity(connectionId, session.sessionId, streamId)
            }
        }
        return session
    }

    private suspend fun ensureModelBinding(
        runtime: SessionRuntime,
        binding: PiModelBinding?,
    ) {
        binding ?: return
        runtime.modelBinder.ensure(binding) { selected ->
            val response = runtime.client.command(
                "session.setModel",
                JSONObject()
                    .put("provider", selected.provider)
                    .put("modelId", selected.modelId),
                timeoutMillis = 60_000,
            )
            if (!response.success) {
                throw IllegalStateException(response.error ?: "Pi session model switch failed")
            }
        }
    }

    private suspend fun forkSession(
        runtime: SessionRuntime,
        sourceSessionKey: String,
        userMessageTimestamp: Long,
        userMessage: String,
    ): PiSessionRef {
        val sourcePath = bindings.getString(sourceSessionKey, null)
            ?: throw IllegalStateException(
                "当前 Operit 对话尚未绑定 Pi JSONL，无法安全重新生成；请先在该对话发送一条消息。"
            )
        // Node intentionally shares one active runtime slot for the same JSONL across sockets.
        // Detach that slot before opening it on the variant client; otherwise `session.fork`
        // would also rebind the source chat's live runtime. The source JSONL binding is retained,
        // so a failed regeneration can reopen it on the next send without reconstructing history.
        detachRuntime(sourceSessionKey)
        runtime.client.openSession(sourcePath)
        val mappingKey = entryBindingKey(sourceSessionKey, userMessageTimestamp)
        val mappedEntryId = entryBindings.getString(mappingKey, null)
        val forkPoint = if (!mappedEntryId.isNullOrBlank()) {
            mappedEntryId
        } else {
            val entriesResponse = runtime.client.command("session.entries")
            if (!entriesResponse.success) {
                throw IllegalStateException(entriesResponse.error ?: "无法读取完整 Pi 会话 entries")
            }
            val root = entriesResponse.data as? JSONObject
                ?: throw IllegalStateException("Pi session.entries 返回无效")
            val entries = root.optJSONArray("entries")
                ?: throw IllegalStateException("Pi session.entries 缺少 entries")
            selectUnambiguousUserEntryId(entries, userMessage).also { entryId ->
                entryBindings.edit().putString(mappingKey, entryId).apply()
            }
        }
        return runtime.client.fork(forkPoint, position = "before")
    }

    private suspend fun detachRuntime(sessionKey: String) {
        val runtime = runtimes.remove(sessionKey) ?: return
        runtime.turnGate.cancelActive(CancellationException("Pi session detached for regeneration"))
        if (runtime.session != null) {
            runCatching { runtime.client.closeSession() }
                .onFailure { AppLogger.w(TAG, "Unable to detach Pi source session", it) }
        }
        runtime.client.close()
    }

    private fun updateIdentity(runtime: SessionRuntime, event: PiEvent) {
        val json = runCatching { JSONObject(event.rawJson) }.getOrNull() ?: return
        val connectionId = json.optString("connectionId")
        val sessionId = json.optString("sessionId")
        val eventStreamId = json.optString("eventStreamId")
        if (connectionId.isNotBlank() && sessionId.isNotBlank() && eventStreamId.isNotBlank()) {
            runtime.identity = SessionIdentity(connectionId, sessionId, eventStreamId)
        }
    }

    private fun closeRuntime(sessionKey: String, removeBinding: Boolean) {
        val runtime = runtimes.remove(sessionKey)
        runtime?.turnGate?.cancelActive(CancellationException("Pi session closed"))
        if (runtime != null) {
            scope.launch { runCatching { runtime.client.closeSession() } }
            runtime.client.close()
        }
        if (removeBinding) bindings.edit().remove(sessionKey).apply()
    }

    private fun updateUsage(chatId: String, event: PiEvent.Other) {
        if (event.type !in setOf("usage", "turn_usage", "token_usage")) return
        val payload = event.payload.optJSONObject("usage") ?: event.payload
        usageByChatId[chatId] =
            Usage(
                inputTokens = payload.optInt("inputTokens", payload.optInt("input", 0)),
                outputTokens = payload.optInt("outputTokens", payload.optInt("output", 0)),
                cachedInputTokens = payload.optInt("cachedInputTokens", payload.optInt("cacheRead", 0)),
            )
    }

    private fun renderToolCall(event: PiEvent.ToolStart): String {
        val tag = ChatMarkupRegex.generateRandomToolTagName()
        return buildString {
            append('\n').append('<').append(tag).append(" name=\"")
                .append(escapeXml(event.name)).append("\">")
            event.arguments.keys().forEach { key ->
                append("\n<param name=\"").append(escapeXml(key)).append("\">")
                    .append(escapeXml(jsonParameter(event.arguments.opt(key))))
                    .append("</param>")
            }
            append("\n</").append(tag).append(">\n")
        }
    }

    private fun renderToolResult(event: PiEvent.ToolEnd): String {
        val content = resultText(event.result)
        val result =
            ToolResult(
                toolName = event.name,
                success = !event.isError,
                result = StringResultData(content),
                error = if (event.isError) content else null,
            )
        return "\n${ConversationMarkupManager.formatToolResultForMessage(result)}\n"
    }

    private fun resultText(result: JSONObject): String {
        val value = result.opt("content")
        return when (value) {
            is String -> value
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val block = value.optJSONObject(index)
                    if (block != null) {
                        if (isNotEmpty()) append('\n')
                        append(block.optString("text", block.toString()))
                    } else {
                        if (isNotEmpty()) append('\n')
                        append(value.opt(index)?.toString().orEmpty())
                    }
                }
            }
            null, JSONObject.NULL -> result.optString("error").ifBlank { result.toString() }
            else -> value.toString()
        }
    }

    private fun readableError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .toList()
        val joined = chain.joinToString(" | ")
        return if (joined.contains("402", ignoreCase = true) ||
            joined.contains("insufficient balance", ignoreCase = true) ||
            joined.contains("insufficient quota", ignoreCase = true)
        ) {
            "API 余额或额度不足，请更换模型/API 配置后重试。"
        } else {
            chain.firstOrNull() ?: "Pi 运行失败"
        }
    }

    private suspend fun com.ai.assistance.operit.util.stream.StreamCollector<String>.emitInlineError(message: String) {
        emit("\n\n> **Pi 错误**：${message.trim()}\n\n")
    }

    private fun jsonParameter(value: Any?): String = when (value) {
        is JSONObject, is JSONArray -> value.toString()
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

internal class PiSessionTurnGate {
    private val mutex = Mutex()
    @Volatile private var activeTurn: Job? = null

    suspend fun <T> run(turnJob: Job, block: suspend () -> T): T = mutex.withLock {
        activeTurn = turnJob
        try {
            block()
        } finally {
            if (activeTurn === turnJob) activeTurn = null
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    fun cancelActive(cause: CancellationException): Boolean {
        val active = activeTurn ?: return false
        active.cancel(cause)
        return true
    }
}

internal class PiSessionModelBinder {
    private var appliedBinding: PiModelBinding? = null

    fun onSessionAttached() {
        appliedBinding = null
    }

    suspend fun ensure(
        binding: PiModelBinding,
        setModel: suspend (PiModelBinding) -> Unit,
    ) {
        if (appliedBinding == binding) return
        setModel(binding)
        appliedBinding = binding
    }
}

internal fun promptUserEntryId(data: Any?): String {
    val json = data as? JSONObject
        ?: throw IllegalStateException("Pi prompt acceptance response is invalid")
    return json.optString("userEntryId").takeIf(String::isNotBlank)
        ?: throw IllegalStateException("Pi accepted the prompt without userEntryId")
}

internal fun entryBindingKey(sessionKey: String, userMessageTimestamp: Long): String =
    "${sessionKey.length}:$sessionKey:$userMessageTimestamp"

internal fun selectUnambiguousUserEntryId(entries: JSONArray, targetMessage: String): String {
    val normalizedTarget = normalizeEntryMessage(targetMessage)
    val matches = buildList {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val message = entry.optJSONObject("message") ?: continue
            if (message.optString("role") != "user") continue
            if (normalizeEntryMessage(entryMessageText(message.opt("content"))) != normalizedTarget) continue
            entry.optString("id").takeIf(String::isNotBlank)?.let(::add)
        }
    }
    return when (matches.size) {
        1 -> matches.single()
        0 -> throw IllegalStateException(
            "无法在 Pi JSONL 中定位要重新生成的用户消息；不会通过重新注入 Android 历史来降级。"
        )
        else -> throw IllegalStateException(
            "Pi JSONL 中存在 ${matches.size} 条内容相同的用户消息且缺少时间戳映射，无法安全选择重新生成位置。"
        )
    }
}

private fun normalizeEntryMessage(value: String): String =
    value.replace(Regex("\\s+"), " ").trim()

private fun entryMessageText(content: Any?): String = when (content) {
    is String -> content
    is JSONArray -> buildString {
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            val text = block.optString("text")
            if (text.isNotEmpty()) append(text)
        }
    }
    else -> content?.toString().orEmpty()
}
