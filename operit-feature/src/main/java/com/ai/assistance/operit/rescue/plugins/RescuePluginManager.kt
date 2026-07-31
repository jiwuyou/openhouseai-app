package com.ai.assistance.operit.rescue.plugins

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RescuePluginManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    val settings = RescuePluginHubSettings(appContext)
    private val catalogClient = RescuePluginCatalogClient(settings)
    private val store = RescuePluginStore(appContext, catalogClient)
    private val workflowRunner = RescuePluginWorkflowRunner(store)
    private val drafts =
        RescuePluginCommentDraftStore(
            java.io.File(appContext.filesDir, "rescue-plugins/comment-drafts")
        )

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
        store.install(pluginId, version)

    suspend fun update(pluginId: String): InstalledRescuePlugin = store.update(pluginId)

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

    suspend fun startWorkflow(pluginId: String, path: String?): JSONObject =
        workflowRunner.start(pluginId, path)

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

    companion object {
        @Volatile private var instance: RescuePluginManager? = null

        fun get(context: Context): RescuePluginManager =
            instance ?: synchronized(this) {
                instance ?: RescuePluginManager(context.applicationContext).also { instance = it }
            }
    }
}
