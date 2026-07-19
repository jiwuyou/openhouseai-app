package com.wuxianpi.pi

import org.json.JSONArray
import org.json.JSONObject

data class PiResponse(
    val id: String,
    val command: String?,
    val success: Boolean,
    val data: Any?,
    val error: String?,
    val rawJson: String,
)

sealed interface PiEvent {
    val rawJson: String

    data class AgentStart(val sessionId: String?, override val rawJson: String) : PiEvent
    data class AgentEnd(
        val sessionId: String?,
        val error: String?,
        val messages: JSONArray?,
        override val rawJson: String,
    ) : PiEvent

    data class TextDelta(
        val delta: String,
        val fullText: String? = null,
        override val rawJson: String,
    ) : PiEvent
    data class ThinkingDelta(
        val delta: String,
        val fullThinking: String? = null,
        override val rawJson: String,
    ) : PiEvent
    data class ToolStart(
        val callId: String,
        val name: String,
        val arguments: JSONObject,
        override val rawJson: String,
    ) : PiEvent

    data class ToolUpdate(
        val callId: String,
        val name: String,
        val partialResult: JSONObject,
        override val rawJson: String,
    ) : PiEvent

    data class ToolEnd(
        val callId: String,
        val name: String,
        val result: JSONObject,
        val isError: Boolean,
        override val rawJson: String,
    ) : PiEvent

    data class ExtensionUiRequest(
        val requestId: String,
        val method: String,
        val payload: JSONObject,
        override val rawJson: String,
    ) : PiEvent

    data class ExtensionError(
        val extensionId: String?,
        val event: String?,
        val error: String,
        override val rawJson: String,
    ) : PiEvent

    data class CommandError(val response: PiResponse, override val rawJson: String) : PiEvent
    data class SessionRecovered(val response: PiResponse, override val rawJson: String) : PiEvent
    data class ProtocolError(val message: String, override val rawJson: String) : PiEvent
    data class Other(val type: String, val payload: JSONObject, override val rawJson: String) : PiEvent
}

sealed interface ParsedPiFrame {
    data class Response(val value: PiResponse) : ParsedPiFrame
    data class Event(val value: PiEvent) : ParsedPiFrame
}

/** Parses Pi's native JSONL frames without translating them to a second agent protocol. */
object PiProtocol {
    fun request(id: String, type: String, parameters: JSONObject = JSONObject()): String {
        val value = JSONObject().put("id", id).put("type", type)
        parameters.keys().forEach { key -> value.put(key, parameters.get(key)) }
        return value.toString()
    }

    fun parse(raw: String): ParsedPiFrame {
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            return ParsedPiFrame.Event(PiEvent.ProtocolError(error.message ?: "Invalid JSON", raw))
        }

        if (json.optString("type") == "response" && json.has("id")) {
            val response = PiResponse(
                id = json.optString("id"),
                command = json.optNullableString("command"),
                success = json.optBoolean("success", false),
                data = json.opt("data").takeUnless { it === JSONObject.NULL },
                error = json.optNullableString("error"),
                rawJson = raw,
            )
            return ParsedPiFrame.Response(response)
        }

        val type = json.optString("type", "unknown")
        val event = when (type) {
            "agent_start" -> PiEvent.AgentStart(json.optNullableString("sessionId"), raw)
            "agent_end" -> PiEvent.AgentEnd(
                sessionId = json.optNullableString("sessionId"),
                error = json.optNullableString("error"),
                messages = json.optJSONArray("messages"),
                rawJson = raw,
            )
            "message_update" -> parseMessageUpdate(json, raw)
            // Compatibility with early pi_agent_rust docs and transparent gateways that flatten deltas.
            "text_delta" -> PiEvent.TextDelta(json.optString("delta"), rawJson = raw)
            "thinking_delta" -> PiEvent.ThinkingDelta(json.optString("delta"), rawJson = raw)
            "tool_execution_start" -> PiEvent.ToolStart(
                callId = json.optString("toolCallId"),
                name = json.optString("toolName"),
                arguments = json.optJSONObject("args") ?: JSONObject(),
                rawJson = raw,
            )
            "tool_execution_update" -> PiEvent.ToolUpdate(
                callId = json.optString("toolCallId"),
                name = json.optString("toolName"),
                partialResult = json.optJSONObject("partialResult") ?: JSONObject(),
                rawJson = raw,
            )
            "tool_execution_end" -> PiEvent.ToolEnd(
                callId = json.optString("toolCallId"),
                name = json.optString("toolName"),
                result = json.optJSONObject("result") ?: JSONObject(),
                isError = json.optBoolean("isError", false),
                rawJson = raw,
            )
            "extension_ui_request" -> PiEvent.ExtensionUiRequest(
                requestId = json.optString("id"),
                method = json.optString("method"),
                payload = json,
                rawJson = raw,
            )
            "extension_error" -> PiEvent.ExtensionError(
                extensionId = json.optNullableString("extensionId"),
                event = json.optNullableString("event"),
                error = json.optString("error", "Extension failed"),
                rawJson = raw,
            )
            else -> PiEvent.Other(type, json, raw)
        }
        return ParsedPiFrame.Event(event)
    }

    private fun parseMessageUpdate(json: JSONObject, raw: String): PiEvent {
        val update = json.optJSONObject("assistantMessageEvent") ?: return PiEvent.Other(
            "message_update",
            json,
            raw,
        )
        return when (update.optString("type")) {
            "text_delta" -> PiEvent.TextDelta(
                delta = update.optString("delta"),
                fullText = extractMessageBlock(json.optJSONObject("message"), "text", "text"),
                rawJson = raw,
            )
            "thinking_delta" -> PiEvent.ThinkingDelta(
                delta = update.optString("delta"),
                fullThinking = extractMessageBlock(json.optJSONObject("message"), "thinking", "thinking"),
                rawJson = raw,
            )
            else -> PiEvent.Other("message_update", json, raw)
        }
    }

    private fun extractMessageBlock(message: JSONObject?, type: String, field: String): String? {
        val content = message?.optJSONArray("content") ?: return null
        val text = buildString {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") == type) append(block.optString(field))
            }
        }
        return text.takeIf(String::isNotEmpty)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
