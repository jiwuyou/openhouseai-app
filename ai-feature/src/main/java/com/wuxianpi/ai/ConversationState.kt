package com.wuxianpi.ai

import com.wuxianpi.pi.PiEvent
import com.wuxianpi.pi.PiResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MessageRole { USER, ASSISTANT, NOTICE }
enum class ToolStatus { RUNNING, SUCCEEDED, FAILED }

data class ToolCardState(
    val id: String,
    val name: String,
    val arguments: String,
    val output: String = "",
    val status: ToolStatus = ToolStatus.RUNNING,
)

data class ChatMessageState(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String = "",
    val thinking: String = "",
    val tools: List<ToolCardState> = emptyList(),
    val isError: Boolean = false,
)

data class ConversationState(
    val messages: List<ChatMessageState> = emptyList(),
    val isAgentRunning: Boolean = false,
    val extensionRequest: PiEvent.ExtensionUiRequest? = null,
)

object ConversationReducer {
    fun setAgentRunning(state: ConversationState, active: Boolean): ConversationState =
        state.copy(isAgentRunning = active)

    fun reduce(state: ConversationState, event: PiEvent): ConversationState = when (event) {
        is PiEvent.AgentStart -> state.copy(
            isAgentRunning = true,
            messages = state.messages.ensureAssistant(),
        )
        is PiEvent.TextDelta -> state.copy(messages = state.messages.updateAssistant { message ->
            message.copy(text = event.fullText ?: (message.text + event.delta))
        })
        is PiEvent.ThinkingDelta -> state.copy(messages = state.messages.updateAssistant { message ->
            message.copy(thinking = event.fullThinking ?: (message.thinking + event.delta))
        })
        is PiEvent.ToolStart -> state.copy(messages = state.messages.updateAssistant { message ->
            if (state.messages.any { existing -> existing.tools.any { it.id == event.callId } }) message
            else message.copy(
                tools = message.tools + ToolCardState(
                    id = event.callId,
                    name = event.name,
                    arguments = event.arguments.toString(2),
                ),
            )
        })
        is PiEvent.ToolUpdate -> state.copy(messages = state.messages.updateTool(event.callId) { tool ->
            tool.copy(output = extractText(event.partialResult).ifBlank { event.partialResult.toString(2) })
        })
        is PiEvent.ToolEnd -> state.copy(messages = state.messages.updateTool(event.callId) { tool ->
            tool.copy(
                output = extractText(event.result).ifBlank { event.result.toString(2) },
                status = if (event.isError) ToolStatus.FAILED else ToolStatus.SUCCEEDED,
            )
        })
        is PiEvent.AgentEnd -> {
            val withError = event.error?.takeIf(String::isNotBlank)?.let { error ->
                state.messages + ChatMessageState(
                    role = MessageRole.NOTICE,
                    text = error,
                    isError = true,
                )
            } ?: state.messages
            // agent_end is only one low-level run. Retry, compaction, or queued follow-up may follow.
            state.copy(messages = withError)
        }
        is PiEvent.AgentSettled -> state.copy(isAgentRunning = false)
        is PiEvent.ExtensionUiRequest -> when (event.method) {
            "select", "confirm", "input", "editor" -> state.copy(extensionRequest = event)
            "notify" -> {
                val text = event.payload.optString("message")
                if (text.isBlank()) state else state.copy(
                    messages = state.messages + ChatMessageState(
                        role = MessageRole.NOTICE,
                        text = text,
                        isError = event.payload.optString("notifyType") == "error",
                    ),
                )
            }
            // Status, title, widget and editor setters are fire-and-forget host UI hints.
            else -> state
        }
        is PiEvent.ExtensionError -> state.copy(
            messages = state.messages + ChatMessageState(
                role = MessageRole.NOTICE,
                text = "Extension error: ${event.error}",
                isError = true,
            ),
            // Deliberately preserve isAgentRunning. Only agent_settled may end the turn.
        )
        is PiEvent.RuntimeError -> state.copy(
            messages = state.messages.appendUniqueError(
                "${event.phase.replaceFirstChar { it.uppercase() }} error: ${event.message}",
            ),
        )
        is PiEvent.CommandError -> state.copy(
            messages = state.messages.appendUniqueError(event.response.error ?: "Pi command failed"),
        )
        is PiEvent.SessionRecovered -> restore(event.response, state)
        else -> state
    }

    fun addUser(state: ConversationState, text: String): ConversationState = state.copy(
        messages = state.messages + ChatMessageState(role = MessageRole.USER, text = text),
    )

    fun clearExtension(state: ConversationState): ConversationState = state.copy(extensionRequest = null)

    fun restore(response: PiResponse, current: ConversationState): ConversationState {
        if (!response.success || response.command !in setOf("get_messages", "session.history")) return current
        val data = response.data as? JSONObject ?: return current
        val messages = data.optJSONArray("messages") ?: return current
        return current.copy(messages = parseHistory(messages))
    }

    private fun parseHistory(history: JSONArray): List<ChatMessageState> = buildList {
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "user" -> add(
                    ChatMessageState(
                        role = MessageRole.USER,
                        text = extractMessageContent(message.opt("content")),
                    ),
                )
                "assistant" -> add(
                    ChatMessageState(
                        role = MessageRole.ASSISTANT,
                        text = extractBlockType(message.optJSONArray("content"), "text"),
                        thinking = extractBlockType(message.optJSONArray("content"), "thinking"),
                    ),
                )
                "toolResult" -> add(
                    ChatMessageState(
                        role = MessageRole.ASSISTANT,
                        tools = listOf(
                            ToolCardState(
                                id = message.optString("toolCallId", "history-$index"),
                                name = message.optString("toolName", "tool"),
                                arguments = "",
                                output = extractMessageContent(message.opt("content")),
                                status = if (message.optBoolean("isError")) {
                                    ToolStatus.FAILED
                                } else {
                                    ToolStatus.SUCCEEDED
                                },
                            ),
                        ),
                    ),
                )
                "bashExecution" -> add(
                    ChatMessageState(
                        role = MessageRole.ASSISTANT,
                        tools = listOf(
                            ToolCardState(
                                id = "bash-history-$index",
                                name = "bash",
                                arguments = message.optString("command"),
                                output = message.optString("output"),
                                status = if (message.optInt("exitCode") == 0) {
                                    ToolStatus.SUCCEEDED
                                } else {
                                    ToolStatus.FAILED
                                },
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private fun extractMessageContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> extractBlockType(value, "text")
        else -> value?.toString().orEmpty()
    }

    private fun extractBlockType(blocks: JSONArray?, type: String): String {
        if (blocks == null) return ""
        return buildString {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                if (block.optString("type") == type) {
                    append(if (type == "thinking") block.optString("thinking") else block.optString("text"))
                }
            }
        }
    }

    private fun extractText(result: JSONObject): String = extractMessageContent(result.opt("content"))

    private fun List<ChatMessageState>.ensureAssistant(): List<ChatMessageState> =
        if (lastOrNull()?.role == MessageRole.ASSISTANT) this
        else this + ChatMessageState(role = MessageRole.ASSISTANT)

    private fun List<ChatMessageState>.updateAssistant(
        transform: (ChatMessageState) -> ChatMessageState,
    ): List<ChatMessageState> {
        val source = ensureAssistant().toMutableList()
        val index = source.indexOfLast { it.role == MessageRole.ASSISTANT }
        source[index] = transform(source[index])
        return source
    }

    private fun List<ChatMessageState>.updateTool(
        callId: String,
        transform: (ToolCardState) -> ToolCardState,
    ): List<ChatMessageState> = map { message ->
        if (message.tools.none { it.id == callId }) message
        else message.copy(tools = message.tools.map { if (it.id == callId) transform(it) else it })
    }

    private fun List<ChatMessageState>.appendUniqueError(text: String): List<ChatMessageState> {
        if (lastOrNull()?.let { it.isError && it.text == text } == true) return this
        return this + ChatMessageState(role = MessageRole.NOTICE, text = text, isError = true)
    }
}
