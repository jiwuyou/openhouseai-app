package com.ai.assistance.operit.rescue.plugins

import org.json.JSONArray
import org.json.JSONObject

object RescuePluginContract {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_HUB_URL = "https://wuxianpirescue.webefficacy.com"
    const val FIRST_INSTALL_PLUGIN_ID = "wuxianpi.first-install"

    const val TOOL_SEARCH = "search_rescue_plugins"
    const val TOOL_LIST_INSTALLED = "list_installed_rescue_plugins"
    const val TOOL_INSTALL = "install_rescue_plugin"
    const val TOOL_UPDATE = "update_rescue_plugin"
    const val TOOL_READ_DOCUMENT = "read_rescue_plugin_document"
    const val TOOL_START_WORKFLOW = "start_rescue_plugin_workflow"
    const val TOOL_GET_COMMENTS = "get_rescue_plugin_comments"
    const val TOOL_DRAFT_COMMENT = "draft_rescue_plugin_comment"
    const val TOOL_PUBLISH_COMMENT = "publish_rescue_plugin_comment"
    const val TOOL_OPEN_MARKET = "open_rescue_plugin_market"

    val toolNames =
        setOf(
            TOOL_SEARCH,
            TOOL_LIST_INSTALLED,
            TOOL_INSTALL,
            TOOL_UPDATE,
            TOOL_READ_DOCUMENT,
            TOOL_START_WORKFLOW,
            TOOL_GET_COMMENTS,
            TOOL_DRAFT_COMMENT,
            TOOL_PUBLISH_COMMENT,
            TOOL_OPEN_MARKET,
        )

    private val SAFE_PLUGIN_ID = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
    private val SAFE_VERSION = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")

    fun requirePluginId(value: String): String =
        value.trim().also { require(SAFE_PLUGIN_ID.matches(it)) { "Invalid plugin id: $value" } }

    fun requireVersion(value: String): String =
        value.trim().also { require(SAFE_VERSION.matches(it)) { "Invalid plugin version: $value" } }

    fun normalizeHubUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Hub URL must start with http:// or https://"
        }
        return normalized
    }
}

data class RescuePluginManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val category: String,
    val entryWorkflow: String?,
    val documents: List<RescuePluginDocument>,
    val requiredCapabilities: List<String>,
    val tags: List<String>,
    val minHostVersion: Int,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("schemaVersion", RescuePluginContract.SCHEMA_VERSION)
            .put("id", id)
            .put("version", version)
            .put("name", name)
            .put("description", description)
            .put("category", category)
            .put("entryWorkflow", entryWorkflow ?: JSONObject.NULL)
            .put("documents", JSONArray(documents.map(RescuePluginDocument::toJson)))
            .put("requiredCapabilities", JSONArray(requiredCapabilities))
            .put("tags", JSONArray(tags))
            .put("minHostVersion", minHostVersion)

    companion object {
        fun parse(json: JSONObject): RescuePluginManifest {
            require(json.optInt("schemaVersion", 0) == RescuePluginContract.SCHEMA_VERSION) {
                "Unsupported rescue plugin schemaVersion"
            }
            val id = RescuePluginContract.requirePluginId(json.getString("id"))
            val version = RescuePluginContract.requireVersion(json.getString("version"))
            val name = json.getString("name").trim()
            require(name.isNotEmpty()) { "Plugin name must not be blank" }
            val description = json.getString("description").trim()
            require(description.isNotEmpty()) { "Plugin description must not be blank" }
            val category = json.getString("category").trim()
            require(category.isNotEmpty()) { "Plugin category must not be blank" }
            val entryWorkflow = json.optionalString("entryWorkflow")?.let(::requireRelativePath)
            val documents =
                json.getJSONArray("documents").objectList().mapIndexed { index, document ->
                    val path = requireRelativePath(document.getString("path"))
                    val title = document.getString("title").trim()
                    require(title.isNotEmpty()) { "documents[$index].title must not be blank" }
                    RescuePluginDocument(path, title)
                }
            return RescuePluginManifest(
                id = id,
                version = version,
                name = name,
                description = description,
                category = category,
                entryWorkflow = entryWorkflow,
                documents = documents,
                requiredCapabilities = json.stringList("requiredCapabilities"),
                tags = json.stringList("tags"),
                minHostVersion = json.getInt("minHostVersion").also {
                    require(it >= 1) { "minHostVersion must be positive" }
                },
            )
        }

        private fun requireRelativePath(path: String): String {
            val normalized = path.replace('\\', '/').trim().trimStart('/')
            require(
                normalized.isNotEmpty() &&
                    normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
            ) { "Invalid plugin relative path: $path" }
            return normalized
        }
    }
}

data class RescuePluginDocument(val path: String, val title: String) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("title", title)
}

data class RescuePluginListing(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val downloadUrl: String?,
    val sha256: String?,
    val manifest: RescuePluginManifest,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("version", version)
            .put("name", name)
            .put("description", description)
            .put("downloadUrl", downloadUrl ?: JSONObject.NULL)
            .put("sha256", sha256 ?: JSONObject.NULL)
            .put("manifest", manifest.toJson())

    companion object {
        fun parseCatalogPlugin(json: JSONObject, requestedVersion: String? = null): RescuePluginListing {
            val catalogId = RescuePluginContract.requirePluginId(json.getString("id"))
            val targetVersion =
                RescuePluginContract.requireVersion(
                    requestedVersion?.trim()?.takeIf { it.isNotEmpty() }
                        ?: json.getString("latestVersion")
                )
            val releases = json.getJSONArray("versions").objectList()
            val release =
                releases.firstOrNull { candidate ->
                    candidate.optJSONObject("manifest")?.optString("version") == targetVersion
                } ?: error("Plugin $catalogId has no release $targetVersion")
            val manifest = RescuePluginManifest.parse(release.getJSONObject("manifest"))
            require(manifest.id == catalogId) {
                "Catalog id $catalogId does not match release manifest ${manifest.id}"
            }
            return RescuePluginListing(
                id = manifest.id,
                version = manifest.version,
                name = manifest.name,
                description = manifest.description,
                downloadUrl = release.getString("downloadUrl"),
                sha256 = release.getString("sha256").lowercase(),
                manifest = manifest,
            )
        }
    }
}

data class RescuePluginComment(
    val id: String,
    val pluginId: String,
    val pluginVersion: String,
    val authorType: String,
    val authorName: String,
    val type: String,
    val rating: Int?,
    val content: String,
    val environment: JSONObject,
    val createdAt: String?,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("pluginId", pluginId)
            .put("pluginVersion", pluginVersion)
            .put("authorType", authorType)
            .put("authorName", authorName)
            .put("type", type)
            .put("rating", rating ?: JSONObject.NULL)
            .put("content", content)
            .put("environment", environment)
            .put("createdAt", createdAt ?: JSONObject.NULL)

    companion object {
        fun parse(json: JSONObject): RescuePluginComment =
            RescuePluginComment(
                id = json.optionalString("id") ?: "",
                pluginId = json.optionalString("pluginId") ?: "",
                pluginVersion = json.optionalString("version") ?: "",
                authorType = json.optionalString("authorType") ?: "user",
                authorName = json.optionalString("authorName") ?: "Anonymous",
                type = json.optionalString("type") ?: "feedback",
                rating = json.optInt("rating", 0).takeIf { it in 1..5 },
                content = json.optString("content"),
                environment = json.optJSONObject("environment") ?: JSONObject(),
                createdAt = json.optionalString("createdAt"),
            )
    }
}

internal fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).map { index -> array.getString(index).trim() }
}

internal fun JSONArray.objectList(): List<JSONObject> =
    (0 until length()).map { index -> getJSONObject(index) }
