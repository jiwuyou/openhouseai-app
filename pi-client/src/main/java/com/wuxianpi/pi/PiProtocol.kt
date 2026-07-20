package com.wuxianpi.pi

import org.json.JSONArray
import org.json.JSONObject

const val WUXIANPI_PROTOCOL_VERSION = "wuxianpi-sdk-v1"

data class PiResponse(
    val id: String,
    val command: String?,
    val success: Boolean,
    val data: Any?,
    val error: String?,
    val rawJson: String,
    val errorCode: String? = null,
)

data class PiSessionRef(
    val sessionId: String,
    val sessionPath: String,
    val eventStreamId: String? = null,
    val cwd: String? = null,
    val isRunning: Boolean? = null,
    val isIdle: Boolean? = null,
) {
    companion object {
        fun from(value: Any?): PiSessionRef {
            val json = value as? JSONObject
                ?: throw IllegalArgumentException("Pi service returned an invalid session")
            val sessionId = json.optString("sessionId")
            val sessionPath = json.optString("sessionPath")
            require(sessionId.isNotBlank()) { "Pi service did not return a sessionId" }
            require(sessionPath.isNotBlank()) { "Pi service did not return a sessionPath" }
            return PiSessionRef(
                sessionId = sessionId,
                sessionPath = sessionPath,
                eventStreamId = json.optNullableString("eventStreamId"),
                cwd = json.optNullableString("cwd"),
                isRunning = if (json.has("isRunning")) json.optBoolean("isRunning") else null,
                isIdle = if (json.has("isIdle")) json.optBoolean("isIdle") else null,
            )
        }
    }
}

sealed interface PiEvent {
    val sessionId: String?
    val sessionPath: String?
    val sequence: Long?
    val rawJson: String

    data class AgentStart(
        override val sessionId: String?,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    /** A low-level run ended. Pi may still retry, compact, or process queued continuations. */
    data class AgentEnd(
        override val sessionId: String?,
        val willRetry: Boolean,
        val messages: JSONArray?,
        val error: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    /** The only event that proves Pi has no automatic continuation left. */
    data class AgentSettled(
        override val sessionId: String?,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class TextDelta(
        val delta: String,
        val fullText: String? = null,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ThinkingDelta(
        val delta: String,
        val fullThinking: String? = null,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ToolStart(
        val callId: String,
        val name: String,
        val arguments: JSONObject,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ToolUpdate(
        val callId: String,
        val name: String,
        val partialResult: JSONObject,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ToolEnd(
        val callId: String,
        val name: String,
        val result: JSONObject,
        val isError: Boolean,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ExtensionUiRequest(
        val requestId: String,
        val method: String,
        val payload: JSONObject,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ExtensionError(
        val extensionId: String?,
        val event: String?,
        val error: String,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    /** Host/SDK failure reporting. It never implies that the conversation is settled. */
    data class RuntimeError(
        val phase: String,
        val commandType: String?,
        val message: String,
        val recoverable: Boolean,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class PromptCompleted(
        val handledWithoutAgent: Boolean,
        val isRunning: Boolean,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class CommandError(
        val response: PiResponse,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class SessionRecovered(
        val response: PiResponse,
        override val sessionId: String?,
        override val sessionPath: String?,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class ProtocolError(
        val message: String,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent

    data class Other(
        val type: String,
        val payload: JSONObject,
        override val sessionId: String? = null,
        override val sessionPath: String? = null,
        override val sequence: Long? = null,
        override val rawJson: String,
    ) : PiEvent
}

sealed interface ParsedPiFrame {
    data class Response(val value: PiResponse) : ParsedPiFrame
    data class Event(val value: PiEvent) : ParsedPiFrame
}

/** Wire codec for the direct Node SDK service. No model or tool orchestration is performed here. */
object PiProtocol {
    fun request(
        id: String,
        type: String,
        sessionId: String? = null,
        payload: JSONObject = JSONObject(),
    ): String = JSONObject()
        .put("id", id)
        .put("type", type)
        .apply {
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
            if (payload.length() > 0) put("payload", payload)
        }
        .toString()

    fun parse(raw: String): ParsedPiFrame {
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            return ParsedPiFrame.Event(PiEvent.ProtocolError(error.message ?: "Invalid JSON", rawJson = raw))
        }

        if (json.has("id") && json.has("ok")) {
            val errorObject = json.optJSONObject("error")
            return ParsedPiFrame.Response(
                PiResponse(
                    id = json.optString("id"),
                    command = json.optNullableString("command"),
                    success = json.optBoolean("ok", false),
                    data = json.opt("result").takeUnless { it === JSONObject.NULL },
                    error = errorObject?.optString("message")?.takeIf(String::isNotBlank)
                        ?: json.optNullableString("error"),
                    errorCode = errorObject?.optNullableString("code"),
                    rawJson = raw,
                ),
            )
        }

        // Keep direct SDK event parsing useful for fixtures and protocol diagnostics.
        val wrapper = if (json.optString("type") == "agent.event") json else null
        val payload = wrapper?.optJSONObject("payload") ?: json
        val sessionId = wrapper?.optNullableString("sessionId") ?: payload.optNullableString("sessionId")
        val sessionPath = wrapper?.optNullableString("sessionPath") ?: payload.optNullableString("sessionPath")
        val sequence = wrapper?.optLongOrNull("sequence")
        return ParsedPiFrame.Event(parseEvent(payload, sessionId, sessionPath, sequence, raw))
    }

    private fun parseEvent(
        json: JSONObject,
        sessionId: String?,
        sessionPath: String?,
        sequence: Long?,
        raw: String,
    ): PiEvent = when (val type = json.optString("type", "unknown")) {
        "agent_start" -> PiEvent.AgentStart(sessionId, sessionPath, sequence, raw)
        "agent_end" -> PiEvent.AgentEnd(
            sessionId = sessionId,
            willRetry = json.optBoolean("willRetry", false),
            messages = json.optJSONArray("messages"),
            error = json.optNullableString("error"),
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "agent_settled" -> PiEvent.AgentSettled(sessionId, sessionPath, sequence, raw)
        "message_update" -> parseMessageUpdate(json, sessionId, sessionPath, sequence, raw)
        "message_end" -> {
            val message = json.optJSONObject("message")
            val errorMessage = message?.optNullableString("errorMessage")
            if (errorMessage != null) {
                PiEvent.RuntimeError(
                    phase = "provider",
                    commandType = null,
                    message = errorMessage,
                    recoverable = true,
                    sessionId = sessionId,
                    sessionPath = sessionPath,
                    sequence = sequence,
                    rawJson = raw,
                )
            } else {
                PiEvent.Other(type, json, sessionId, sessionPath, sequence, raw)
            }
        }
        "text_delta" -> PiEvent.TextDelta(
            json.optString("delta"),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "thinking_delta" -> PiEvent.ThinkingDelta(
            json.optString("delta"),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "tool_execution_start" -> PiEvent.ToolStart(
            callId = json.optString("toolCallId"),
            name = json.optString("toolName"),
            arguments = json.optJSONObject("args") ?: JSONObject(),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "tool_execution_update" -> PiEvent.ToolUpdate(
            callId = json.optString("toolCallId"),
            name = json.optString("toolName"),
            partialResult = json.optObject("partialResult"),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "tool_execution_end" -> PiEvent.ToolEnd(
            callId = json.optString("toolCallId"),
            name = json.optString("toolName"),
            result = json.optObject("result"),
            isError = json.optBoolean("isError", false),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "extension_ui_request" -> PiEvent.ExtensionUiRequest(
            requestId = json.optString("requestId").ifBlank { json.optString("id") },
            method = json.optString("method"),
            payload = json,
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "extension_error" -> PiEvent.ExtensionError(
            extensionId = json.optNullableString("extensionId"),
            event = json.optNullableString("event"),
            error = json.optString("error", "Extension failed"),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "runtime_error" -> PiEvent.RuntimeError(
            phase = json.optString("phase", "runtime"),
            commandType = json.optNullableString("commandType"),
            message = json.optString("message", "Pi runtime failed"),
            recoverable = json.optBoolean("recoverable", true),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "prompt_completed" -> PiEvent.PromptCompleted(
            handledWithoutAgent = json.optBoolean("handledWithoutAgent", false),
            isRunning = json.optBoolean("isRunning", false),
            sessionId = sessionId,
            sessionPath = sessionPath,
            sequence = sequence,
            rawJson = raw,
        )
        "auto_retry_end" -> {
            val finalError = json.optNullableString("finalError")
            if (!json.optBoolean("success", true) && finalError != null) {
                PiEvent.RuntimeError(
                    phase = "provider",
                    commandType = null,
                    message = finalError,
                    recoverable = true,
                    sessionId = sessionId,
                    sessionPath = sessionPath,
                    sequence = sequence,
                    rawJson = raw,
                )
            } else {
                PiEvent.Other(type, json, sessionId, sessionPath, sequence, raw)
            }
        }
        else -> PiEvent.Other(type, json, sessionId, sessionPath, sequence, raw)
    }

    private fun parseMessageUpdate(
        json: JSONObject,
        sessionId: String?,
        sessionPath: String?,
        sequence: Long?,
        raw: String,
    ): PiEvent {
        val update = json.optJSONObject("assistantMessageEvent")
            ?: return PiEvent.Other("message_update", json, sessionId, sessionPath, sequence, raw)
        return when (update.optString("type")) {
            "text_delta" -> PiEvent.TextDelta(
                delta = update.optString("delta"),
                fullText = extractMessageBlock(json.optJSONObject("message"), "text", "text"),
                sessionId = sessionId,
                sessionPath = sessionPath,
                sequence = sequence,
                rawJson = raw,
            )
            "thinking_delta" -> PiEvent.ThinkingDelta(
                delta = update.optString("delta"),
                fullThinking = extractMessageBlock(json.optJSONObject("message"), "thinking", "thinking"),
                sessionId = sessionId,
                sessionPath = sessionPath,
                sequence = sequence,
                rawJson = raw,
            )
            "error" -> PiEvent.RuntimeError(
                phase = "provider",
                commandType = null,
                message = update.optString("error").ifBlank { update.optString("reason", "Model request failed") },
                recoverable = true,
                sessionId = sessionId,
                sessionPath = sessionPath,
                sequence = sequence,
                rawJson = raw,
            )
            else -> PiEvent.Other("message_update", json, sessionId, sessionPath, sequence, raw)
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
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else runCatching { getLong(key) }.getOrNull()

private fun JSONObject.optObject(key: String): JSONObject = when (val value = opt(key)) {
    is JSONObject -> value
    null, JSONObject.NULL -> JSONObject()
    else -> JSONObject().put("value", value)
}
