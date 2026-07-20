package com.wuxianpi.pi

import org.json.JSONObject

data class PiRuntimeCapabilities(
    val eventAck: Boolean,
    val eventStreamId: Boolean,
    val persistentDiagnostics: Boolean,
    val multiSessionSubscriptions: Boolean,
)

data class PiRuntimeReady(
    val connectionId: String,
    val version: String?,
    val protocol: String?,
    val protocolVersion: Int?,
    val capabilities: PiRuntimeCapabilities,
)

data class PiNodeDiagnosticsStatus(
    val enabled: Boolean,
    val detailEnabled: Boolean,
    val detailUntil: Long?,
    val path: String?,
    val size: Long?,
    val raw: JSONObject,
) {
    companion object {
        fun from(value: Any?): PiNodeDiagnosticsStatus {
            val json = value as? JSONObject ?: JSONObject()
            return PiNodeDiagnosticsStatus(
                enabled = json.optBoolean("enabled", true),
                detailEnabled = json.optBoolean("detailEnabled", json.optBoolean("detail", false)),
                detailUntil = json.optLongOrNullValue("detailUntil"),
                path = json.optString("path").takeIf(String::isNotBlank),
                size = json.optLongOrNullValue("size"),
                raw = json,
            )
        }
    }
}

data class PiNodeDiagnosticsExport(
    val content: String,
    val path: String?,
    val size: Long?,
) {
    companion object {
        fun from(value: Any?): PiNodeDiagnosticsExport {
            val json = value as? JSONObject ?: throw IllegalArgumentException("Invalid diagnostics export")
            val content = json.optString("content").ifBlank { json.optString("nodeJsonl") }
            return PiNodeDiagnosticsExport(
                content = content,
                path = json.optString("path").takeIf(String::isNotBlank),
                size = json.optLongOrNullValue("size") ?: content.toByteArray().size.toLong(),
            )
        }
    }
}

internal data class PiWireFrameMetadata(
    val type: String,
    val id: String?,
    val connectionId: String?,
    val sessionId: String?,
    val eventStreamId: String?,
    val sequence: Long?,
    val eventType: String?,
    val updateType: String?,
)

internal fun parseWireFrameMetadata(raw: String): PiWireFrameMetadata? = runCatching {
    val json = JSONObject(raw)
    val payload = json.optJSONObject("payload")
    PiWireFrameMetadata(
        type = json.optString("type", if (json.has("ok")) "response" else "unknown"),
        id = json.optString("id").takeIf(String::isNotBlank),
        connectionId = json.optString("connectionId").takeIf(String::isNotBlank),
        sessionId = json.optString("sessionId").takeIf(String::isNotBlank),
        eventStreamId = json.optString("eventStreamId").takeIf(String::isNotBlank),
        sequence = json.optLongOrNullValue("sequence"),
        eventType = payload?.optString("type")?.takeIf(String::isNotBlank),
        updateType = payload?.optJSONObject("assistantMessageEvent")
            ?.optString("type")?.takeIf(String::isNotBlank),
    )
}.getOrNull()

internal fun parseRuntimeReady(raw: String): PiRuntimeReady? = runCatching {
    val json = JSONObject(raw)
    if (json.optString("type") != "runtime.ready") return@runCatching null
    val connectionId = json.optString("connectionId")
    if (connectionId.isBlank()) return@runCatching null
    val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
    PiRuntimeReady(
        connectionId = connectionId,
        version = json.optString("version").takeIf(String::isNotBlank),
        protocol = json.optString("protocol").takeIf(String::isNotBlank),
        protocolVersion = json.optInt("protocolVersion", 0).takeIf { it > 0 },
        capabilities = PiRuntimeCapabilities(
            eventAck = capabilities.optInt("eventAck", 0) > 0,
            eventStreamId = capabilities.optInt("eventStreamId", 0) > 0,
            persistentDiagnostics = capabilities.optInt("persistentDiagnostics", 0) > 0,
            multiSessionSubscriptions = capabilities.optInt("multiSessionSubscriptions", 0) > 0,
        ),
    )
}.getOrNull()

internal fun buildEventAckRequest(
    id: String,
    sessionId: String?,
    connectionId: String,
    eventStreamId: String,
    sequence: Long,
    eventType: String,
): String = PiProtocol.request(
    id = id,
    type = "event.ack",
    sessionId = sessionId,
    payload = JSONObject()
        .put("connectionId", connectionId)
        .put("eventStreamId", eventStreamId)
        .put("sequence", sequence)
        .put("eventType", eventType)
        .apply { if (!sessionId.isNullOrBlank()) put("sessionId", sessionId) },
)

internal fun shouldAcknowledgeTerminalEvent(event: PiEvent): Boolean = when (event) {
    is PiEvent.AgentSettled -> true
    is PiEvent.PromptCompleted -> !event.isRunning
    else -> false
}

internal fun shouldSendEventAck(ready: PiRuntimeReady?, event: PiEvent): Boolean =
    ready?.capabilities?.eventAck == true && shouldAcknowledgeTerminalEvent(event)

internal fun diagnosticAggregationCategory(metadata: PiWireFrameMetadata?): String? = when {
    metadata?.eventType == "tool_execution_update" -> "tool_update"
    metadata?.eventType == "message_update" && metadata.updateType in HIGH_FREQUENCY_UPDATES -> metadata.updateType
    else -> null
}

internal class PendingAckTracker(private val capacity: Int = 64) {
    private val order = ArrayDeque<String>()
    private val values = HashSet<String>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun add(id: String): String? {
        if (!values.add(id)) return null
        order.addLast(id)
        if (order.size <= capacity) return null
        return order.removeFirst().also(values::remove)
    }

    @Synchronized fun remove(id: String): Boolean {
        if (!values.remove(id)) return false
        order.remove(id)
        return true
    }

    @Synchronized fun clear() {
        order.clear()
        values.clear()
    }

    @Synchronized fun size(): Int = values.size
}

internal fun isAckResponseId(id: String): Boolean = id.startsWith("ack-")

internal data class DiagnosticAggregateSnapshot(
    val counts: Map<String, Long>,
    val bytes: Long,
    val reason: String,
    val startedAt: Long,
    val endedAt: Long,
)

internal class DiagnosticEventAggregator(
    private val flushIntervalMs: Long = 5_000,
    private val flushCount: Long = 100,
) {
    private val counts = linkedMapOf<String, Long>()
    private var bytes = 0L
    private var startedAt = 0L

    @Synchronized
    fun add(category: String, byteCount: Int, now: Long): DiagnosticAggregateSnapshot? {
        if (startedAt == 0L) startedAt = now
        counts[category] = (counts[category] ?: 0) + 1
        bytes += byteCount.coerceAtLeast(0)
        val total = counts.values.sum()
        return if (total >= flushCount || now - startedAt >= flushIntervalMs) drainLocked("periodic", now) else null
    }

    @Synchronized fun drain(reason: String, now: Long = System.currentTimeMillis()): DiagnosticAggregateSnapshot? =
        drainLocked(reason, now)

    private fun drainLocked(reason: String, now: Long): DiagnosticAggregateSnapshot? {
        if (counts.isEmpty()) return null
        return DiagnosticAggregateSnapshot(counts.toMap(), bytes, reason, startedAt, now).also {
            counts.clear()
            bytes = 0
            startedAt = 0
        }
    }
}

private val HIGH_FREQUENCY_UPDATES = setOf("text_delta", "thinking_delta", "toolcall_delta")

private fun JSONObject.optLongOrNullValue(key: String): Long? =
    if (!has(key) || isNull(key)) null else runCatching { getLong(key) }.getOrNull()
