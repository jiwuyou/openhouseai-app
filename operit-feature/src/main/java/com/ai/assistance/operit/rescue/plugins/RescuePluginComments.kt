package com.ai.assistance.operit.rescue.plugins

import java.io.File
import java.util.UUID
import org.json.JSONObject

internal class RescuePluginCommentDraftStore(private val root: File) {
    @Synchronized
    fun create(
        pluginId: String,
        pluginVersion: String,
        type: String,
        rating: Int?,
        content: String,
        environment: JSONObject,
    ): JSONObject {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Comment content must not be blank" }
        require(rating == null || rating in 1..5) { "Comment rating must be between 1 and 5" }
        val draftId = UUID.randomUUID().toString()
        val draft =
            JSONObject()
                .put("draftId", draftId)
                .put("pluginId", RescuePluginContract.requirePluginId(pluginId))
                .put("pluginVersion", RescuePluginContract.requireVersion(pluginVersion))
                .put("authorType", "agent")
                .put("authorName", "WuxianPi Rescue")
                .put("type", type.trim().ifEmpty { "compatibility_report" })
                .put("rating", rating ?: JSONObject.NULL)
                .put("content", normalizedContent)
                .put("environment", JSONObject(environment.toString()))
                .put("createdAtEpochMs", System.currentTimeMillis())
        root.mkdirs()
        draftFile(draftId).writeText(draft.toString(2))
        return draft
    }

    @Synchronized
    fun get(draftId: String): JSONObject {
        val file = draftFile(draftId)
        require(file.isFile) { "Comment draft does not exist: $draftId" }
        return JSONObject(file.readText())
    }

    @Synchronized
    fun delete(draftId: String) {
        draftFile(draftId).delete()
    }

    private fun draftFile(draftId: String): File {
        val normalized = draftId.trim()
        require(UUID_REGEX.matches(normalized)) { "Invalid comment draft id" }
        return File(root, "$normalized.json")
    }

    private companion object {
        val UUID_REGEX = Regex("[0-9a-fA-F-]{36}")
    }
}

class RescuePluginWorkflowRunner(private val store: RescuePluginStore) {
    suspend fun start(pluginId: String, requestedPath: String? = null): JSONObject {
        val installed = store.getInstalled(pluginId) ?: error("Plugin $pluginId is not installed")
        val workflowPath =
            requestedPath?.trim()?.takeIf { it.isNotEmpty() }
                ?: installed.manifest.entryWorkflow
                ?: error("Plugin $pluginId has no entry workflow")
        val workflow = JSONObject(store.readFile(pluginId, workflowPath))
        require(workflow.optInt("schemaVersion", 0) == RescuePluginContract.SCHEMA_VERSION) {
            "Unsupported workflow schemaVersion"
        }
        val steps = workflow.optJSONArray("steps") ?: error("Workflow has no steps")
        require(steps.length() > 0) { "Workflow has no executable steps" }
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            require(step.optString("id").isNotBlank()) { "Workflow step $index has no id" }
            require(step.optString("kind").isNotBlank()) { "Workflow step $index has no kind" }
        }
        return JSONObject()
            .put("status", "ready")
            .put("pluginId", installed.manifest.id)
            .put("pluginVersion", installed.activeVersion)
            .put("workflowPath", workflowPath)
            .put("workflow", workflow)
            .put(
                "instruction",
                "Follow the workflow in order. Call the named existing rescue tools; do not reimplement SAF, RUN_COMMAND, tmux, or setup scripts.",
            )
    }
}
