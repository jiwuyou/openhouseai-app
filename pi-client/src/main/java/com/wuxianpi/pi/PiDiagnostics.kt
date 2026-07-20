package com.wuxianpi.pi

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface PiDiagnosticSink {
    fun record(
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        detailedJson: String? = null,
    )

    fun enableDetailedMode(durationMs: Long = MAX_DIAGNOSTIC_DETAIL_MS)
    fun disableDetailedMode()
    fun detailedUntilMillis(): Long
    fun isDetailedMode(): Boolean
    fun droppedEntries(): Long
    fun snapshotJsonl(): ByteArray
}

const val MAX_DIAGNOSTIC_DETAIL_MS = 120_000L

object NoOpPiDiagnosticSink : PiDiagnosticSink {
    override fun record(event: String, fields: Map<String, Any?>, detailedJson: String?) = Unit
    override fun enableDetailedMode(durationMs: Long) = Unit
    override fun disableDetailedMode() = Unit
    override fun detailedUntilMillis(): Long = 0
    override fun isDetailedMode(): Boolean = false
    override fun droppedEntries(): Long = 0
    override fun snapshotJsonl(): ByteArray = ByteArray(0)
}

/** Fixed-capacity, non-blocking JSONL diagnostics. Disk failures never escape into business code. */
class RollingJsonlDiagnostics(
    directory: File,
    private val maxBytes: Long = 2L * 1024 * 1024,
    queueCapacity: Int = 512,
) : PiDiagnosticSink, AutoCloseable {
    private sealed interface Work {
        data class Entry(
            val timestamp: Long,
            val event: String,
            val fields: Map<String, Any?>,
            val detailedJson: String?,
            val detailed: Boolean,
        ) : Work
        data class Barrier(val latch: CountDownLatch) : Work
        data object Stop : Work
    }

    private val current = File(directory, "android.jsonl")
    private val previous = File(directory, "android.jsonl.1")
    private val queue = ArrayBlockingQueue<Work>(queueCapacity.coerceAtLeast(8))
    private val detailedUntil = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val worker = Thread(::writeLoop, "wuxianpi-diagnostics").apply {
        isDaemon = true
        start()
    }

    init {
        runCatching { directory.mkdirs() }
    }

    override fun record(event: String, fields: Map<String, Any?>, detailedJson: String?) {
        if (closed.get()) return
        val work = Work.Entry(
            timestamp = System.currentTimeMillis(),
            event = event.take(120),
            fields = fields,
            detailedJson = detailedJson,
            detailed = System.currentTimeMillis() < detailedUntil.get(),
        )
        if (!queue.offer(work)) dropped.incrementAndGet()
    }

    override fun enableDetailedMode(durationMs: Long) {
        detailedUntil.set(System.currentTimeMillis() + durationMs.coerceIn(1, MAX_DIAGNOSTIC_DETAIL_MS))
        record("diagnostics.detail.enabled", mapOf("durationMs" to durationMs.coerceIn(1, MAX_DIAGNOSTIC_DETAIL_MS)))
    }

    override fun disableDetailedMode() {
        detailedUntil.set(0)
        record("diagnostics.detail.disabled")
    }

    override fun detailedUntilMillis(): Long = detailedUntil.get()
    override fun isDetailedMode(): Boolean = System.currentTimeMillis() < detailedUntil.get()
    override fun droppedEntries(): Long = dropped.get()

    override fun snapshotJsonl(): ByteArray {
        flush(2_000)
        return runCatching {
            ByteArrayOutputStream().use { output ->
                if (previous.isFile) previous.inputStream().use { it.copyTo(output) }
                if (current.isFile) current.inputStream().use { it.copyTo(output) }
                output.toByteArray()
            }
        }.getOrDefault(ByteArray(0))
    }

    private fun flush(timeoutMs: Long) {
        if (closed.get()) return
        val latch = CountDownLatch(1)
        if (!queue.offer(Work.Barrier(latch))) return
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun writeLoop() {
        while (true) {
            when (val work = runCatching { queue.take() }.getOrNull() ?: continue) {
                is Work.Entry -> write(work)
                is Work.Barrier -> work.latch.countDown()
                Work.Stop -> return
            }
        }
    }

    private fun write(entry: Work.Entry) {
        runCatching {
            val json = JSONObject()
                .put("timestamp", entry.timestamp)
                .put("event", entry.event)
                .put("detailMode", entry.detailed)
                .put("fields", sanitizeValue(JSONObject(entry.fields), 0))
            if (entry.detailed && !entry.detailedJson.isNullOrBlank()) {
                val parsed = runCatching { JSONObject(entry.detailedJson) }.getOrNull()
                json.put("detail", parsed?.let { sanitizeValue(it, 0) } ?: "<invalid-json>")
            }
            json.put("droppedBefore", dropped.get())
            val bytes = (json.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                .let { if (it.size <= MAX_ENTRY_BYTES) it else it.copyOf(MAX_ENTRY_BYTES) + '\n'.code.toByte() }
            rotateIfNeeded(bytes.size.toLong())
            FileOutputStream(current, true).use { it.write(bytes) }
        }.onFailure { dropped.incrementAndGet() }
    }

    private fun rotateIfNeeded(incoming: Long) {
        val segmentLimit = (maxBytes / 2).coerceAtLeast(16 * 1024)
        if (current.length() + incoming <= segmentLimit) return
        previous.delete()
        if (current.isFile && !current.renameTo(previous)) current.delete()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.offer(Work.Stop)
        runCatching { worker.join(500) }
    }

    private fun sanitizeValue(value: Any?, depth: Int): Any? {
        if (depth > 8) return "<max-depth>"
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().also { target ->
                value.keys().forEach { key ->
                    target.put(
                        key,
                        when {
                            isSecretKey(key) -> "<redacted>"
                            isBodyKey(key) -> "<redacted-body>"
                            else -> sanitizeValue(value.opt(key), depth + 1)
                        },
                    )
                }
            }
            is JSONArray -> JSONArray().also { target ->
                for (index in 0 until value.length()) target.put(sanitizeValue(value.opt(index), depth + 1))
            }
            is String -> sanitizeString(value).take(MAX_STRING_CHARS)
            is Number, is Boolean -> value
            else -> value.toString().take(MAX_STRING_CHARS)
        }
    }

    private fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("token") || normalized.contains("apikey") ||
            normalized.contains("api_key") || normalized.contains("authorization") ||
            normalized.contains("secret") || normalized.contains("password")
    }

    private fun isBodyKey(key: String): Boolean = key.lowercase() in BODY_KEYS

    private fun sanitizeString(value: String): String = value
        .replace(BEARER_PATTERN, "Bearer <redacted>")
        .replace(SK_PATTERN, "sk-<redacted>")

    private companion object {
        const val MAX_ENTRY_BYTES = 64 * 1024
        const val MAX_STRING_CHARS = 16 * 1024
        val BEARER_PATTERN = Regex("(?i)Bearer\\s+[^\\s\\\"']+")
        val SK_PATTERN = Regex("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b")
        val BODY_KEYS = setOf(
            "content", "text", "delta", "thinking", "message", "messages", "prompt", "input",
            "output", "args", "arguments", "result", "partialresult", "toolcall", "toolresult",
            "image", "images", "command", "stdout", "stderr", "data",
        )
    }
}
