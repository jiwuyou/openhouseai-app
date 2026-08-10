package com.ai.assistance.operit.rescue.session

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ai.assistance.operit.rescue.memory.RescueMemoryStore
import com.ai.assistance.operit.rescue.plugins.RescuePluginSetUpdateResult

enum class RescueSessionPhase {
    CREATED,
    LOCAL_BOOTSTRAP,
    MARKET_REFRESH,
    PLUGIN_UPDATE,
    ACTIVATE_PLUGIN_SET,
    SESSION_RUNTIME,
    EXECUTE_TASK,
    WRITE_MEMORY,
    COMPLETE,
}

/** Monotonic phase guard shared by persisted session state and unit tests. */
internal fun nextRescueSessionPhase(
    current: RescueSessionPhase,
    target: RescueSessionPhase,
): RescueSessionPhase {
    require(!(current >= RescueSessionPhase.SESSION_RUNTIME && target == RescueSessionPhase.LOCAL_BOOTSTRAP)) {
        "Session Runtime cannot re-enter Bootstrap"
    }
    return if (target.ordinal < current.ordinal) current else target
}

data class PreparedRescueSession(
    val conversationId: String,
    val bootstrapRunId: String,
    val pluginRevision: String,
    val memoryRevision: Long,
    val systemContext: String,
    val usedOfflinePluginSet: Boolean,
)

/** Runs the local-first bootstrap once per conversation and persists the loop guard. */
class RescueSessionCoordinator(
    context: Context,
    private val memoryStore: RescueMemoryStore,
    private val loadBootstrapContext: suspend () -> String,
    private val beforeMemorySnapshot: suspend () -> Unit = {},
    private val updatePluginSet: suspend () -> RescuePluginSetUpdateResult,
    private val loadRuntimeContext: suspend () -> String,
    private val loadBusinessContext: suspend () -> String,
    private val onPluginSetActivated: suspend () -> Unit,
) {
    private val root = File(context.applicationContext.filesDir, "rescue-sessions/bootstrap")
    private val mutex = Mutex()

    suspend fun prepare(conversationId: String): PreparedRescueSession =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val normalizedId = conversationId.trim().also {
                    require(it.isNotEmpty()) { "conversationId must not be blank" }
                }
                val stateFile = stateFile(normalizedId)
                readPrepared(stateFile, normalizedId)?.let { return@withContext it }

                val existing = readState(stateFile)
                val bootstrapRunId =
                    existing?.optString("bootstrapRunId")?.takeIf(String::isNotBlank)
                        ?: UUID.randomUUID().toString()
                var state =
                    existing ?: baseState(normalizedId, bootstrapRunId).also {
                        writeState(stateFile, it)
                    }

                val frozenBootstrap =
                    state.optString("bootstrapContext").takeIf(String::isNotBlank)
                        ?: loadBootstrapContext().also { context ->
                            state = transition(stateFile, state, RescueSessionPhase.LOCAL_BOOTSTRAP)
                            state.put("bootstrapContext", context)
                            writeState(stateFile, state)
                        }
                val frozenMemory =
                    state.optString("memoryContext").takeIf(String::isNotBlank)
                        ?: run {
                            beforeMemorySnapshot()
                            val memorySnapshot = memoryStore.read()
                            memoryStore.promptSnapshot(memorySnapshot).also { context ->
                                state.put("memoryRevision", memorySnapshot.revision)
                                state.put("memoryContext", context)
                                writeState(stateFile, state)
                            }
                        }

                state = transition(stateFile, state, RescueSessionPhase.MARKET_REFRESH)
                state = transition(stateFile, state, RescueSessionPhase.PLUGIN_UPDATE)
                val updateResult = updatePluginSet()
                state = transition(stateFile, state, RescueSessionPhase.ACTIVATE_PLUGIN_SET)
                onPluginSetActivated()

                val runtimeContext = loadRuntimeContext()
                val businessContext = loadBusinessContext()
                val updateSummary =
                    if (updateResult.usedOfflineSet) {
                        "维修市场刷新失败或插件集合校验未完成；本轮继续使用本地完整插件集合。"
                    } else if (updateResult.updatedPluginIds.isEmpty()) {
                        "维修市场刷新完成；已安装官方插件均为当前兼容版本。"
                    } else {
                        "维修市场刷新完成并已原子更新：${updateResult.updatedPluginIds.joinToString()}。"
                    }
                val systemContext =
                    listOf(frozenBootstrap, frozenMemory, updateSummary, runtimeContext, businessContext)
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .joinToString("\n\n")
                state = transition(stateFile, state, RescueSessionPhase.SESSION_RUNTIME)
                state
                    .put("bootstrapCompleted", true)
                    .put("activePluginRevision", updateResult.revision)
                    .put("usedOfflinePluginSet", updateResult.usedOfflineSet)
                    .put("sessionContext", systemContext)
                    .put("updatedAt", Instant.now().toString())
                writeState(stateFile, state)
                trimStates()
                PreparedRescueSession(
                    conversationId = normalizedId,
                    bootstrapRunId = bootstrapRunId,
                    pluginRevision = updateResult.revision,
                    memoryRevision = state.optLong("memoryRevision", 0L),
                    systemContext = systemContext,
                    usedOfflinePluginSet = updateResult.usedOfflineSet,
                )
            }
        }

    suspend fun markExecuting(conversationId: String) =
        advanceIfPrepared(conversationId, RescueSessionPhase.EXECUTE_TASK)

    suspend fun markWritingMemory(conversationId: String) =
        advanceIfPrepared(conversationId, RescueSessionPhase.WRITE_MEMORY)

    suspend fun markComplete(conversationId: String) =
        advanceIfPrepared(conversationId, RescueSessionPhase.COMPLETE)

    private suspend fun advanceIfPrepared(conversationId: String, phase: RescueSessionPhase) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = stateFile(conversationId)
                val state = readState(file) ?: return@withContext
                if (!state.optBoolean("bootstrapCompleted", false)) return@withContext
                transition(file, state, phase)
            }
        }
    }

    private fun readPrepared(file: File, conversationId: String): PreparedRescueSession? {
        val state = readState(file) ?: return null
        if (!state.optBoolean("bootstrapCompleted", false)) return null
        val phase = runCatching { RescueSessionPhase.valueOf(state.getString("phase")) }.getOrNull()
            ?: return null
        require(phase >= RescueSessionPhase.SESSION_RUNTIME) {
            "Completed Rescue bootstrap has an invalid phase"
        }
        val context = state.optString("sessionContext").takeIf(String::isNotBlank) ?: return null
        return PreparedRescueSession(
            conversationId = conversationId,
            bootstrapRunId = state.getString("bootstrapRunId"),
            pluginRevision = state.getString("activePluginRevision"),
            memoryRevision = state.optLong("memoryRevision", 0L),
            systemContext = context,
            usedOfflinePluginSet = state.optBoolean("usedOfflinePluginSet", false),
        )
    }

    private fun transition(
        file: File,
        state: JSONObject,
        target: RescueSessionPhase,
    ): JSONObject {
        val current =
            runCatching { RescueSessionPhase.valueOf(state.optString("phase", "CREATED")) }
                .getOrDefault(RescueSessionPhase.CREATED)
        val next = nextRescueSessionPhase(current, target)
        if (next == current) return state
        state.put("phase", next.name).put("updatedAt", Instant.now().toString())
        writeState(file, state)
        return state
    }

    private fun baseState(conversationId: String, bootstrapRunId: String): JSONObject =
        JSONObject()
            .put("conversationId", conversationId)
            .put("phase", RescueSessionPhase.CREATED.name)
            .put("bootstrapRunId", bootstrapRunId)
            .put("bootstrapCompleted", false)
            .put("createdAt", Instant.now().toString())
            .put("updatedAt", Instant.now().toString())

    private fun stateFile(conversationId: String): File =
        File(root, sha256(conversationId) + ".json")

    private fun readState(file: File): JSONObject? =
        file.takeIf(File::isFile)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun writeState(file: File, state: JSONObject) {
        root.mkdirs()
        val temporary = File(root, ".${file.name}-${UUID.randomUUID()}.tmp")
        temporary.writeText(state.toString(2))
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun trimStates() {
        root.listFiles().orEmpty().filter(File::isFile).sortedByDescending(File::lastModified)
            .drop(MAX_SESSION_STATES).forEach(File::delete)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SESSION_STATES = 200
    }
}
