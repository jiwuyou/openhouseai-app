package com.ai.assistance.operit.rescue.remote

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

data class RescuePiRemoteEvent(
    val chatId: String,
    val type: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val argumentsJson: String? = null,
    val content: String? = null,
    val detail: String? = null,
    val isError: Boolean = false,
    val timestampMs: Long,
)

class RescuePiRemoteEventHub {
    private val mutableEvents =
        MutableSharedFlow<RescuePiRemoteEvent>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<RescuePiRemoteEvent> = mutableEvents.asSharedFlow()

    fun publish(event: RescuePiRemoteEvent): Boolean = mutableEvents.tryEmit(event)

    fun publishNative(chatId: String, event: JSONObject, timestampMs: Long = System.currentTimeMillis()): Boolean {
        val args = event.optJSONObject("args")
        val details = event.optJSONObject("details")
        return publish(
            RescuePiRemoteEvent(
                chatId = chatId,
                type = event.optString("type"),
                toolCallId = event.optString("toolCallId").takeIf(String::isNotBlank),
                toolName = event.optString("toolName").takeIf(String::isNotBlank),
                argumentsJson = args?.toString(),
                content = event.optString("content").takeIf(String::isNotBlank),
                detail =
                    event.optString("message").takeIf(String::isNotBlank)
                        ?: event.optString("reason").takeIf(String::isNotBlank)
                        ?: details?.toString(),
                isError = event.optBoolean("isError", false),
                timestampMs = timestampMs.coerceAtLeast(0),
            ),
        )
    }
}
