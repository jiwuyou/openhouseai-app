package com.wuxianpi.tools

import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
)

data class ToolFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JSONObject? = null,
)

data class ToolResult(
    val callId: String,
    val content: JSONObject = JSONObject(),
    val isError: Boolean = false,
    val error: ToolFailure? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("callId", callId)
        .put("isError", isError)
        .put("content", content)
        .apply {
            error?.let {
                put(
                    "error",
                    JSONObject()
                        .put("code", it.code)
                        .put("message", it.message)
                        .put("retryable", it.retryable)
                        .apply { if (it.details != null) put("details", it.details) },
                )
            }
        }

    companion object {
        fun success(callId: String, content: JSONObject = JSONObject()) =
            ToolResult(callId = callId, content = content)

        fun failure(
            callId: String,
            code: String,
            message: String,
            retryable: Boolean = false,
            details: JSONObject? = null,
        ) = ToolResult(
            callId = callId,
            isError = true,
            error = ToolFailure(code, message, retryable, details),
        )
    }
}

fun interface AndroidToolHandler {
    fun execute(call: ToolCall): ToolResult
}

class AndroidToolRegistry {
    private val handlers = LinkedHashMap<String, AndroidToolHandler>()

    @Synchronized
    fun register(name: String, handler: AndroidToolHandler): AndroidToolRegistry = apply {
        require(name.matches(TOOL_NAME)) { "Invalid tool name: $name" }
        handlers[name] = handler
    }

    @Synchronized
    fun names(): Set<String> = handlers.keys.toSet()

    fun execute(call: ToolCall): ToolResult {
        val handler = synchronized(this) { handlers[call.name] }
            ?: return ToolResult.failure(call.id, "tool_not_found", "Unknown Android tool: ${call.name}")
        return try {
            handler.execute(call)
        } catch (error: SecurityException) {
            ToolResult.failure(
                call.id,
                "permission_denied",
                error.message ?: "Android denied this operation",
            )
        } catch (error: IllegalArgumentException) {
            ToolResult.failure(call.id, "invalid_arguments", error.message ?: "Invalid arguments")
        } catch (error: Throwable) {
            ToolResult.failure(call.id, "android_error", error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}
