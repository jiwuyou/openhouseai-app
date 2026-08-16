package com.ai.assistance.operit.rescue.plugins

import android.content.Context
import com.ai.assistance.operit.core.tools.javascript.JsEngine
import com.ai.assistance.operit.core.tools.javascript.extractJsExecutionFailure
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.rescue.memory.RescueMemoryPatch
import com.ai.assistance.operit.rescue.memory.RescueMemorySnapshot
import com.ai.assistance.operit.rescue.memory.RescueMemoryStore
import com.ai.assistance.operit.rescue.session.PreparedRescueSession
import com.ai.assistance.operit.rescue.session.RescueSessionCoordinator
import com.ai.assistance.operit.rescue.resources.ApkResourceOffer
import com.ai.assistance.operit.rescue.resources.ApkResourceOfferStore
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FirstInstallPreparation(
    val initialVersion: String?,
    val activeVersion: String?,
    val updateStatus: String,
    val updated: Boolean,
    val detail: String? = null,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("updateStatus", updateStatus)
            .put("updated", updated)
            .put("initialVersion", initialVersion ?: JSONObject.NULL)
            .put("activeVersion", activeVersion ?: JSONObject.NULL)
            .apply { detail?.let { put("detail", it) } }

    companion object {
        fun timedOut(): FirstInstallPreparation =
            FirstInstallPreparation(
                initialVersion = null,
                activeVersion = null,
                updateStatus = "timeout_using_available",
                updated = false,
                detail = "The online first-install update check timed out; using the available plugin.",
            )
    }
}

internal class FirstInstallPreparationCoordinator<T>(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val onTimeout: () -> T,
    private val prepare: suspend () -> T,
) {
    private val mutex = Mutex()
    private var inFlightPreparation: Deferred<T>? = null

    fun prewarm() {
        scope.launch {
            try {
                awaitPreparation()
            } catch (_: CancellationException) {
                // The application scope owns this task; callers do not need a prewarm failure.
            } catch (_: Exception) {
                // A later workflow start performs the normal bundled fallback and reports errors.
            }
        }
    }

    suspend fun awaitPreparation(): T = getOrStart().await()

    private suspend fun getOrStart(): Deferred<T> =
        mutex.withLock {
            inFlightPreparation?.takeIf { it.isActive }
                ?: scope.async {
                    try {
                        withTimeout(timeoutMillis) { prepare() }
                    } catch (_: TimeoutCancellationException) {
                        onTimeout()
                    }
                }.also { created ->
                    inFlightPreparation = created
                    created.invokeOnCompletion {
                        scope.launch {
                            mutex.withLock {
                                if (inFlightPreparation === created) {
                                    inFlightPreparation = null
                                }
                            }
                        }
                    }
                }
        }
}

class RescuePluginManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    val settings = RescuePluginHubSettings(appContext)
    private val catalogClient = RescuePluginCatalogClient(settings)
    private val store = RescuePluginStore(appContext, catalogClient)
    private val workflowRunner = RescuePluginWorkflowRunner(store)
    private val memoryStore = RescueMemoryStore.get(appContext)
    val memorySnapshot: StateFlow<RescueMemorySnapshot> = memoryStore.snapshot
    private val resourceOfferStore = ApkResourceOfferStore.get(appContext)
    val activeResourceOffer: StateFlow<ApkResourceOffer?> = resourceOfferStore.activeOffer
    private val contextJsEngine by lazy { JsEngine(appContext) }
    private val contextJsLock = Any()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeActions = MutableStateFlow<List<ActiveRescuePluginAction>>(emptyList())
    val activeActions: StateFlow<List<ActiveRescuePluginAction>> = _activeActions.asStateFlow()
    private val sessionCoordinator =
        RescueSessionCoordinator(
            context = appContext,
            memoryStore = memoryStore,
            loadBootstrapContext = {
                assistantContextForRoles("session", setOf("bootstrap"))
            },
            beforeMemorySnapshot = ::synchronizeMemoryBeforeSession,
            updatePluginSet = store::updateInstalledOfficialPluginSet,
            loadRuntimeContext = {
                assistantContextForRoles("session", setOf("runtime"))
            },
            loadBusinessContext = {
                assistantContextForRoles("session", setOf("business"))
            },
            onPluginSetActivated = ::reloadActiveActions,
        )
    private val firstInstallPreparation =
        FirstInstallPreparationCoordinator(
            scope = applicationScope,
            timeoutMillis = FIRST_INSTALL_PREPARATION_TIMEOUT_MILLIS,
            onTimeout = FirstInstallPreparation::timedOut,
            prepare = ::prepareFirstInstall,
        )
    private val drafts =
        RescuePluginCommentDraftStore(
            java.io.File(appContext.filesDir, "rescue-plugins/comment-drafts")
        )

    init {
        applicationScope.launch {
            resourceOfferStore.recordCurrentApk()
            store.ensureBundledCorePlugins()
            reloadActiveActions()
        }
        applicationScope.launch {
            resourceOfferStore.activeOffer.collect { reloadActiveActions() }
        }
    }

    suspend fun search(query: String): JSONObject =
        JSONObject().put(
            "plugins",
            JSONArray(catalogClient.search(query).map(RescuePluginListing::toJson)),
        )

    suspend fun listInstalled(): JSONObject =
        JSONObject().put(
            "plugins",
            JSONArray(store.listInstalled().map(InstalledRescuePlugin::toJson)),
        )

    suspend fun install(pluginId: String, version: String?): InstalledRescuePlugin =
        store.install(pluginId, version).also { reloadActiveActions() }

    suspend fun update(pluginId: String): InstalledRescuePlugin =
        store.update(pluginId).also { reloadActiveActions() }

    suspend fun readDocument(pluginId: String, requestedPath: String?): JSONObject {
        val installed = store.getInstalled(pluginId) ?: error("Plugin $pluginId is not installed")
        val path =
            requestedPath?.trim()?.takeIf { it.isNotEmpty() }
                ?: installed.manifest.documents.firstOrNull()?.path
                ?: error("Plugin $pluginId has no documents")
        return JSONObject()
            .put("pluginId", installed.manifest.id)
            .put("pluginVersion", installed.activeVersion)
            .put("path", path)
            .put("content", store.readFile(pluginId, path))
    }

    suspend fun assistantContext(scope: String, userMessage: String? = null): String {
        val roles =
            if (scope.trim().lowercase() == "turn") {
                // Bootstrap is one-shot setup context. A Runtime turn must never re-enter it.
                setOf("runtime", "business")
            } else {
                setOf("bootstrap", "runtime", "business")
            }
        return assistantContextForRoles(scope, roles, userMessage)
    }

    private suspend fun assistantContextForRoles(
        scope: String,
        roles: Set<String>,
        userMessage: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val normalizedScope = scope.trim().lowercase()
                require(normalizedScope == "session" || normalizedScope == "turn") {
                    "assistant context scope must be session or turn"
                }
                val resolved = mutableListOf<String>()
                var executableContexts = 0
                store.readActiveAssistantContexts(normalizedScope, roles)
                    .forEach { candidate ->
                        if (
                            candidate.context.provider == "javascript" &&
                                executableContexts++ >= MAX_EXECUTABLE_ASSISTANT_CONTEXTS
                        ) {
                            return@forEach
                        }
                        try {
                            resolveAssistantContext(candidate, normalizedScope, userMessage)
                                ?.let(resolved::add)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A broken executable provider must not suppress other active plugins.
                        }
                    }
                selectRescuePluginAssistantContexts(resolved).joinToString("\n\n")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
        }

    suspend fun prepareSession(conversationId: String): PreparedRescueSession =
        sessionCoordinator.prepare(conversationId)

    suspend fun markSessionExecuting(conversationId: String) =
        sessionCoordinator.markExecuting(conversationId)

    suspend fun markSessionWritingMemory(conversationId: String) =
        sessionCoordinator.markWritingMemory(conversationId)

    suspend fun markSessionComplete(conversationId: String) =
        sessionCoordinator.markComplete(conversationId)

    suspend fun readMemory(): RescueMemorySnapshot = memoryStore.read()

    suspend fun patchMemory(patch: RescueMemoryPatch): RescueMemorySnapshot =
        memoryStore.patch(patch).also { pushMemoryMirrorBestEffort() }

    suspend fun replaceMemory(expectedRevision: Long, markdown: String): RescueMemorySnapshot =
        memoryStore.replace(expectedRevision, markdown).also { pushMemoryMirrorBestEffort() }

    suspend fun undoMemory(): RescueMemorySnapshot =
        memoryStore.undo().also { pushMemoryMirrorBestEffort() }

    suspend fun completeApkResourceOffer(
        status: com.ai.assistance.operit.rescue.resources.ApkResourceOfferStatus,
        detail: String,
    ): ApkResourceOffer? = resourceOfferStore.complete(status, detail).also { reloadActiveActions() }

    private suspend fun synchronizeMemoryBeforeSession() {
        try {
            val operations = OperitHostProvider.operationsOrUnsupported()
            val remote = operations.readRescueMemoryMirror()
            if (remote.success) {
                remote.details.optString("payload").takeIf(String::isNotBlank)?.let { payload ->
                    memoryStore.mergeSyncPayload(payload.toByteArray(Charsets.UTF_8))
                }
            }
            // A merge may produce a new revision. A missing remote mirror should also be seeded.
            pushMemoryMirrorBestEffort(operations)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Memory synchronization is best effort; pending-sync.json preserves retry intent.
        }
    }

    private suspend fun pushMemoryMirrorBestEffort(
        operations: OperitHostOperations = OperitHostProvider.operationsOrUnsupported(),
    ) {
        try {
            val payload = memoryStore.exportSyncPayload()
            val metadata = JSONObject(payload.toString(Charsets.UTF_8))
            val result = operations.writeRescueMemoryMirror(payload)
            if (result.success) {
                memoryStore.markSynced(
                    revision = metadata.getLong("revision"),
                    sha256 = metadata.getString("sha256"),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later session or memory mutation retries the pending mirror write.
        }
    }

    private suspend fun reloadActiveActions() {
        _activeActions.value =
            store.readActiveActions().filter { action ->
                when (action.action.visibleWhen) {
                    "always" -> true
                    "apk-update-pending" -> resourceOfferStore.current()?.requiresReminder == true
                    // Completion and maintenance state become visible only after their owning
                    // business plugin contributes a concrete local status source.
                    "first-install-incomplete", "maintenance-due" -> false
                    else -> false
                }
            }
    }

    private fun resolveAssistantContext(
        candidate: ActiveRescuePluginAssistantContext,
        scope: String,
        userMessage: String?,
    ): String? {
        if (candidate.context.provider == "static") return candidate.content
        val functionName = candidate.context.functionName ?: return null
        val now = OffsetDateTime.now()
        val params =
            mapOf<String, Any?>(
                "pluginId" to candidate.pluginId,
                "pluginVersion" to candidate.pluginVersion,
                "pluginRoot" to candidate.pluginRoot,
                "contextPath" to candidate.path,
                "scope" to scope,
                "message" to userMessage,
                "__operit_plugin_id" to candidate.pluginId,
                "nowEpochMs" to System.currentTimeMillis(),
                "currentTimeIso" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone" to ZoneId.systemDefault().id,
            )
        val result =
            synchronized(contextJsLock) {
                contextJsEngine.executeScriptFunction(
                    script = candidate.content,
                    functionName = functionName,
                    params = params,
                    timeoutSec = ASSISTANT_CONTEXT_TIMEOUT_SECONDS,
                )
            }
        if (extractJsExecutionFailure(result) != null) return null
        return when (result) {
            null -> null
            is JSONObject -> {
                if (!result.optBoolean("success", true)) null
                else result.opt("value")?.toString() ?: result.optString("content").takeIf { it.isNotBlank() }
            }
            else -> result.toString()
        }
    }

    fun prewarmFirstInstall() {
        firstInstallPreparation.prewarm()
    }

    suspend fun startWorkflow(pluginId: String, path: String?): JSONObject {
        if (pluginId == RescuePluginContract.RESOURCE_UPDATE_PLUGIN_ID) {
            store.updateResourceUpdateIfAvailable()
            reloadActiveActions()
            return workflowRunner.start(pluginId, path)
        }
        if (pluginId != RescuePluginContract.FIRST_INSTALL_PLUGIN_ID) {
            return workflowRunner.start(pluginId, path)
        }

        val preparation = firstInstallPreparation.awaitPreparation()
        return startFirstInstallWorkflowWithFallback(
            preparation = preparation,
            start = { workflowRunner.start(pluginId, path) },
            rollback = { store.rollbackToPrevious(pluginId) != null },
            restoreBundled = {
                store.restoreBundledFirstInstall()
                Unit
            },
        )
    }

    suspend fun getComments(pluginId: String, version: String?): JSONObject =
        JSONObject().put(
            "comments",
            JSONArray(catalogClient.getComments(pluginId, version).map(RescuePluginComment::toJson)),
        )

    suspend fun draftAgentComment(
        pluginId: String,
        pluginVersion: String,
        type: String,
        rating: Int?,
        content: String,
        environment: JSONObject,
    ): JSONObject = withContext(Dispatchers.IO) {
        drafts.create(pluginId, pluginVersion, type, rating, content, environment)
    }

    suspend fun preparePublish(draftId: String): JSONObject = withContext(Dispatchers.IO) {
        val draft = drafts.get(draftId)
        JSONObject()
            .put("draftId", draft.getString("draftId"))
            .put("pluginId", draft.getString("pluginId"))
            .put("pluginVersion", draft.getString("pluginVersion"))
            .put("content", draft.getString("content"))
            .put("userActionRequired", true)
            .put("deferredUserAction", RescuePluginContract.TOOL_PUBLISH_COMMENT)
            .put("message", "评论草稿已保存。请由用户点击聊天中的发布卡片确认。")
    }

    suspend fun publishDraft(draftId: String): RescuePluginComment {
        val draft = withContext(Dispatchers.IO) { drafts.get(draftId) }
        val published = catalogClient.publishComment(draft)
        withContext(Dispatchers.IO) { drafts.delete(draftId) }
        return published
    }

    suspend fun publishUserComment(
        pluginId: String,
        pluginVersion: String,
        rating: Int?,
        content: String,
    ): RescuePluginComment {
        require(content.isNotBlank()) { "Comment content must not be blank" }
        require(rating == null || rating in 1..5) { "Rating must be between 1 and 5" }
        return catalogClient.publishComment(
            JSONObject()
                .put("pluginId", RescuePluginContract.requirePluginId(pluginId))
                .put("pluginVersion", RescuePluginContract.requireVersion(pluginVersion))
                .put("authorType", "user")
                .put("authorName", "WuxianPi User")
                .put("type", "feedback")
                .put("rating", rating ?: JSONObject.NULL)
                .put("content", content.trim())
                .put("environment", JSONObject()),
        )
    }

    suspend fun marketPlugins(query: String = ""): List<RescuePluginListing> =
        catalogClient.search(query)

    suspend fun installedPlugin(pluginId: String): InstalledRescuePlugin? =
        store.getInstalled(pluginId)

    suspend fun marketComments(pluginId: String, version: String?): List<RescuePluginComment> =
        catalogClient.getComments(pluginId, version)

    suspend fun ensureBundledFirstInstall(): InstalledRescuePlugin =
        store.ensureBundledFirstInstall()

    private suspend fun prepareFirstInstall(): FirstInstallPreparation {
        val available =
            try {
                store.ensureBundledFirstInstall()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return FirstInstallPreparation(
                    initialVersion = null,
                    activeVersion = null,
                    updateStatus = "bundled_prepare_failed",
                    updated = false,
                    detail = failure.message ?: failure::class.java.simpleName,
                )
            }
        val updated =
            try {
                store.updateFirstInstallIfAvailable()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return FirstInstallPreparation(
                    initialVersion = available.activeVersion,
                    activeVersion = available.activeVersion,
                    updateStatus = "update_failed_using_available",
                    updated = false,
                    detail = failure.message ?: failure::class.java.simpleName,
                )
            }
        val didUpdate = updated.activeVersion != available.activeVersion
        return FirstInstallPreparation(
            initialVersion = available.activeVersion,
            activeVersion = updated.activeVersion,
            updateStatus = if (didUpdate) "updated" else "current_or_offline",
            updated = didUpdate,
            detail =
                if (didUpdate) {
                    "Updated the first-install plugin before starting the workflow."
                } else {
                    "No newer compatible release was activated; using the available plugin."
                },
        )
    }

    companion object {
        private const val FIRST_INSTALL_PREPARATION_TIMEOUT_MILLIS = 30_000L
        private const val ASSISTANT_CONTEXT_TIMEOUT_SECONDS = 2L
        private const val MAX_EXECUTABLE_ASSISTANT_CONTEXTS = 4

        @Volatile private var instance: RescuePluginManager? = null

        fun get(context: Context): RescuePluginManager =
            instance ?: synchronized(this) {
                instance ?: RescuePluginManager(context.applicationContext).also { instance = it }
            }
    }
}

internal fun selectRescuePluginAssistantContexts(
    candidates: List<String>,
    maxBytesPerContext: Int = 4 * 1024,
    maxTotalBytes: Int = 16 * 1024,
): List<String> {
    require(maxBytesPerContext > 0) { "maxBytesPerContext must be positive" }
    require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
    val selected = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var totalBytes = 0
    candidates.forEach { candidate ->
        val content = candidate.trim()
        if (content.isEmpty() || !seen.add(content)) return@forEach
        val size = content.toByteArray(Charsets.UTF_8).size
        if (size > maxBytesPerContext || totalBytes + size > maxTotalBytes) return@forEach
        selected += content
        totalBytes += size
    }
    return selected
}

internal suspend fun startFirstInstallWorkflowWithFallback(
    preparation: FirstInstallPreparation,
    start: suspend () -> JSONObject,
    rollback: suspend () -> Boolean,
    restoreBundled: suspend () -> Unit,
): JSONObject {
    val failures = JSONArray()

    suspend fun attempt(source: String): JSONObject? =
        try {
            start().apply {
                put(
                    "firstInstallPreparation",
                    preparation.toJson()
                        .put("workflowSource", source)
                        .put("fallbackUsed", source != "active")
                        .put("fallbackFailures", JSONArray(failures.toString())),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures.put(
                JSONObject()
                    .put("source", source)
                    .put("error", failure.message ?: failure::class.java.simpleName)
            )
            null
        }

    attempt("active")?.let { return it }

    val rolledBack =
        try {
            rollback()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures.put(
                JSONObject()
                    .put("source", "rollback")
                    .put("error", failure.message ?: failure::class.java.simpleName)
            )
            false
        }
    if (rolledBack) {
        attempt("previous")?.let { return it }
    }

    try {
        restoreBundled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw IllegalStateException(
            "Unable to restore the bundled first-install plugin after workflow failure: " +
                (failure.message ?: failure::class.java.simpleName),
            failure,
        )
    }
    attempt("bundled")?.let { return it }

    throw IllegalStateException(
        "The first-install workflow could not be started from the active, previous, or bundled plugin: " +
            failures.toString(),
    )
}
