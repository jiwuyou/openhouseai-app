package com.ai.assistance.operit.rescue.memory

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RescueMemorySnapshot(
    val revision: Long,
    val markdown: String,
    val updatedAt: String,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("revision", revision)
            .put("updatedAt", updatedAt)
            .put("markdown", markdown)
}

data class RescueMemoryPatch(
    val expectedRevision: Long,
    val section: String,
    val content: String,
    val source: String,
    val confidence: String,
    val userConfirmed: Boolean,
)

/** Android-private, revisioned Rescue memory with an All-in-One Termux mirror. */
class RescueMemoryStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "rescue-memory")
    private val documentFile = File(root, "assistant-memory.md")
    private val stateFile = File(root, "state.json")
    private val deviceStateFile = File(root, "device-state.json")
    private val pendingSyncFile = File(root, "pending-sync.json")
    private val historyRoot = File(root, "history")
    private val lock = Any()
    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<RescueMemorySnapshot> = _snapshot.asStateFlow()

    suspend fun read(): RescueMemorySnapshot = withContext(Dispatchers.IO) {
        synchronized(lock) { loadSnapshot().also { _snapshot.value = it } }
    }

    suspend fun replace(expectedRevision: Long, markdown: String): RescueMemorySnapshot =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireSafeContent(markdown)
                val current = loadSnapshot()
                check(current.revision == expectedRevision) {
                    "Memory changed from revision $expectedRevision to ${current.revision}; reload before saving"
                }
                commit(current, normalizeDocument(markdown))
            }
        }

    suspend fun patch(patch: RescueMemoryPatch): RescueMemorySnapshot =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val sectionTitle = SECTION_TITLES[patch.section]
                    ?: error("Unsupported memory section: ${patch.section}")
                val content = patch.content.trim()
                require(content.isNotEmpty()) { "Memory patch content must not be blank" }
                require(content.length <= MAX_PATCH_CHARS) { "Memory patch is too large" }
                requireSafeContent(content)
                require(patch.source in SOURCES) { "Unsupported memory source" }
                require(patch.confidence in CONFIDENCE_LEVELS) { "Unsupported confidence" }
                if (patch.section == "preferences" || patch.section == "user_notes") {
                    require(patch.userConfirmed) {
                        "Preferences and user notes require explicit user confirmation"
                    }
                }
                val current = loadSnapshot()
                check(current.revision == patch.expectedRevision) {
                    "Memory changed from revision ${patch.expectedRevision} to ${current.revision}; read it again"
                }
                val metadata =
                    if (patch.section == "device_facts") {
                        " (verified=${Instant.now()}, source=${patch.source}, confidence=${patch.confidence})"
                    } else {
                        ""
                    }
                val updated = appendToSection(current.markdown, sectionTitle, "$content$metadata")
                if (patch.section == "device_facts") {
                    writeDeviceFact(content, patch.source, patch.confidence)
                }
                commit(current, updated)
            }
        }

    suspend fun undo(): RescueMemorySnapshot = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val history = historyRoot.listFiles().orEmpty().filter(File::isFile).maxByOrNull(File::lastModified)
                ?: error("No Rescue memory history is available")
            val current = loadSnapshot()
            val restored = history.readText()
            requireSafeContent(restored)
            history.delete()
            commit(current, normalizeDocument(restored), saveHistory = false)
        }
    }

    suspend fun exportSyncPayload(): ByteArray = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = loadSnapshot()
            JSONObject()
                .put("schemaVersion", 1)
                .put("revision", current.revision)
                .put("updatedAt", current.updatedAt)
                .put("sha256", sha256(current.markdown))
                .put("markdown", current.markdown)
                .put("deviceState", readDeviceState())
                .toString(2)
                .toByteArray(Charsets.UTF_8)
        }
    }

    suspend fun mergeSyncPayload(payload: ByteArray): RescueMemorySnapshot =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                require(payload.size <= MAX_SYNC_BYTES) { "Rescue memory sync payload is too large" }
                val remote = JSONObject(payload.toString(Charsets.UTF_8))
                require(remote.optInt("schemaVersion") == 1) {
                    "Unsupported Rescue memory sync schema"
                }
                val remoteMarkdown = normalizeDocument(remote.getString("markdown"))
                requireSafeContent(remoteMarkdown)
                require(remote.optString("sha256") == sha256(remoteMarkdown)) {
                    "Rescue memory sync checksum mismatch"
                }
                mergeDeviceState(remote.optJSONObject("deviceState"))
                val current = loadSnapshot()
                if (current.markdown == remoteMarkdown) {
                    pendingSyncFile.delete()
                    return@synchronized current
                }
                val remoteRevision = remote.getLong("revision")
                val merged =
                    when {
                        current.revision == 0L -> remoteMarkdown
                        remoteRevision > current.revision -> mergeDocuments(remoteMarkdown, current.markdown)
                        else -> mergeDocuments(current.markdown, remoteMarkdown)
                    }
                commit(
                    current = current,
                    markdown = merged,
                    nextRevision = maxOf(current.revision, remoteRevision) + 1L,
                )
            }
        }

    suspend fun markSynced(revision: Long, sha256: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = loadSnapshot()
            if (current.revision == revision && this@RescueMemoryStore.sha256(current.markdown) == sha256) {
                pendingSyncFile.delete()
            }
        }
    }

    fun promptSnapshot(snapshot: RescueMemorySnapshot): String {
        val content = snapshot.markdown.take(MAX_PROMPT_CHARS)
        return """
            # AI 记忆快照

            memoryRevision=${snapshot.revision}
            这是本轮 Bootstrap 读取的不可变快照。实时探测优先于其中的设备事实；写回时必须使用 revision 门禁。

            $content
        """.trimIndent()
    }

    private fun commit(
        current: RescueMemorySnapshot,
        markdown: String,
        saveHistory: Boolean = true,
        nextRevision: Long = current.revision + 1L,
    ): RescueMemorySnapshot {
        require(markdown.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Rescue memory exceeds the supported size"
        }
        root.mkdirs()
        historyRoot.mkdirs()
        if (saveHistory && documentFile.isFile) {
            val history = File(historyRoot, "memory-${current.revision}-${System.currentTimeMillis()}.md")
            writeAtomically(history, current.markdown)
            trimHistory()
        }
        val next =
            RescueMemorySnapshot(
                revision = nextRevision,
                markdown = markdown,
                updatedAt = Instant.now().toString(),
            )
        writeAtomically(documentFile, next.markdown)
        writeAtomically(
            stateFile,
            JSONObject().put("revision", next.revision).put("updatedAt", next.updatedAt).toString(2),
        )
        writePendingSync(next)
        mirrorToEmbeddedTermux(next)
        _snapshot.value = next
        return next
    }

    private fun loadSnapshot(): RescueMemorySnapshot {
        root.mkdirs()
        val markdown =
            documentFile.takeIf(File::isFile)?.readText()?.let(::normalizeDocument)
                ?: DEFAULT_DOCUMENT
        val state =
            stateFile.takeIf(File::isFile)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        return RescueMemorySnapshot(
            revision = state?.optLong("revision", 0L) ?: 0L,
            markdown = markdown,
            updatedAt = state?.optString("updatedAt")?.takeIf(String::isNotBlank) ?: Instant.EPOCH.toString(),
        )
    }

    private fun writeDeviceFact(content: String, source: String, confidence: String) {
        val state = readDeviceState()
        state.getJSONArray("facts").put(
            JSONObject()
                .put("value", content)
                .put("verifiedAt", Instant.now().toString())
                .put("source", source)
                .put("confidence", confidence)
        )
        writeAtomically(deviceStateFile, state.toString(2))
    }

    private fun readDeviceState(): JSONObject =
        deviceStateFile.takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?.takeIf { it.optJSONArray("facts") != null }
            ?: JSONObject().put("facts", JSONArray())

    private fun mergeDeviceState(remote: JSONObject?) {
        val remoteFacts = remote?.optJSONArray("facts") ?: return
        val local = readDeviceState()
        val localFacts = local.getJSONArray("facts")
        val seen = mutableSetOf<String>()
        val merged = JSONArray()
        fun appendUnique(source: JSONArray) {
            for (index in 0 until source.length()) {
                val fact = source.optJSONObject(index) ?: continue
                val key = listOf(
                    fact.optString("value"),
                    fact.optString("verifiedAt"),
                    fact.optString("source"),
                    fact.optString("confidence"),
                ).joinToString("\u0000")
                if (seen.add(key)) merged.put(JSONObject(fact.toString()))
                if (merged.length() >= MAX_DEVICE_FACTS) return
            }
        }
        appendUnique(localFacts)
        appendUnique(remoteFacts)
        if (merged.toString() != localFacts.toString()) {
            writeAtomically(deviceStateFile, JSONObject().put("facts", merged).toString(2))
        }
    }

    private fun appendToSection(document: String, title: String, content: String): String {
        val marker = "## $title"
        val start = document.indexOf(marker)
        require(start >= 0) { "Memory document is missing section: $title" }
        val next = document.indexOf("\n## ", start + marker.length).let { if (it < 0) document.length else it }
        val insertion = "\n- ${content.replace(Regex("[\\r\\n]+"), " ").trim()}"
        return document.substring(0, next).trimEnd() + insertion + "\n\n" + document.substring(next).trimStart()
    }

    private fun mergeDocuments(primary: String, secondary: String): String {
        var merged = normalizeDocument(primary)
        SECTION_TITLES.values.forEach { title ->
            val existing = sectionLines(merged, title).toMutableSet()
            sectionLines(secondary, title).forEach { line ->
                if (existing.add(line)) merged = appendToSection(merged, title, line.removePrefix("- "))
            }
        }
        return normalizeDocument(merged)
    }

    private fun sectionLines(document: String, title: String): List<String> {
        val marker = "## $title"
        val start = document.indexOf(marker)
        if (start < 0) return emptyList()
        val contentStart = start + marker.length
        val end = document.indexOf("\n## ", contentStart).let { if (it < 0) document.length else it }
        return document.substring(contentStart, end).lineSequence()
            .map(String::trim)
            .filter { it.startsWith("- ") && it.length > 2 }
            .toList()
    }

    private fun normalizeDocument(value: String): String {
        var result = value.trim().ifEmpty { DEFAULT_DOCUMENT }
        SECTION_TITLES.values.forEach { title ->
            if (!result.contains("## $title")) result += "\n\n## $title\n"
        }
        return result.trim() + "\n"
    }

    private fun requireSafeContent(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "Memory content is too large"
        }
        require(SECRET_PATTERNS.none { it.containsMatchIn(value) }) {
            "Memory appears to contain a password, token, API key, or private key"
        }
    }

    private fun writePendingSync(snapshot: RescueMemorySnapshot) {
        writeAtomically(
            pendingSyncFile,
            JSONObject()
                .put("revision", snapshot.revision)
                .put("sha256", sha256(snapshot.markdown))
                .put("termuxPath", "~/.local/share/openhouseai/memory/assistant-memory.md")
                .put("createdAt", snapshot.updatedAt)
                .toString(2),
        )
    }

    private fun mirrorToEmbeddedTermux(snapshot: RescueMemorySnapshot) {
        if (appContext.packageName != EMBEDDED_TERMUX_PACKAGE) return
        val home = File(appContext.filesDir, "home/.local/share/openhouseai/memory")
        runCatching {
            home.mkdirs()
            writeAtomically(File(home, "assistant-memory.md"), snapshot.markdown)
            writeAtomically(File(home, "device-state.json"), deviceStateFile.takeIf(File::isFile)?.readText() ?: "{}")
            writeAtomically(
                File(home, SYNC_FILE_NAME),
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("revision", snapshot.revision)
                    .put("updatedAt", snapshot.updatedAt)
                    .put("sha256", sha256(snapshot.markdown))
                    .put("markdown", snapshot.markdown)
                    .put("deviceState", readDeviceState())
                    .toString(2),
            )
            pendingSyncFile.delete()
        }
    }

    private fun trimHistory() {
        historyRoot.listFiles().orEmpty().filter(File::isFile).sortedByDescending(File::lastModified)
            .drop(MAX_HISTORY_FILES).forEach(File::delete)
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
        temporary.writeText(content)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: IOException) {
            temporary.delete()
            throw failure
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val EMBEDDED_TERMUX_PACKAGE = "com.termux"
        private const val MAX_PATCH_CHARS = 2000
        private const val MAX_PROMPT_CHARS = 12_000
        private const val MAX_DOCUMENT_BYTES = 64 * 1024
        private const val MAX_HISTORY_FILES = 20
        private const val MAX_DEVICE_FACTS = 100
        private const val MAX_SYNC_BYTES = 128 * 1024
        const val SYNC_FILE_NAME = "memory-sync.json"
        private val SECTION_TITLES =
            linkedMapOf(
                "preferences" to "用户偏好",
                "user_notes" to "用户明确要求记住的事项",
                "device_facts" to "当前设备状态摘要",
                "completed" to "已完成事项",
                "follow_up" to "最近失败和后续建议",
            )
        private val SOURCES = setOf("user", "android", "termux", "service-manager", "plugin")
        private val CONFIDENCE_LEVELS = setOf("high", "medium", "low")
        private val SECRET_PATTERNS =
            listOf(
                Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----", RegexOption.IGNORE_CASE),
                Regex("(?i)(password|passwd|api[_ -]?key|access[_ -]?token|bearer)\\s*[:=]\\s*\\S{8,}"),
                Regex("(?i)sk-[A-Za-z0-9_-]{16,}"),
            )
        private val DEFAULT_DOCUMENT =
            """
            # AI 记忆

            ## 用户偏好

            ## 用户明确要求记住的事项

            ## 当前设备状态摘要

            ## 已完成事项

            ## 最近失败和后续建议
            """.trimIndent() + "\n"

        @Volatile private var instance: RescueMemoryStore? = null

        fun get(context: Context): RescueMemoryStore =
            instance ?: synchronized(this) {
                instance ?: RescueMemoryStore(context.applicationContext).also { instance = it }
            }
    }
}
