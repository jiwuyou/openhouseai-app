package com.ai.assistance.operit.rescue.plugins

import org.json.JSONArray
import org.json.JSONObject

object RescuePluginContract {
    const val SCHEMA_VERSION = 1
    const val HOST_API_VERSION = 12
    const val DEFAULT_HUB_URL = "https://wuxianpirescue.webefficacy.com"
    const val FIRST_INSTALL_PLUGIN_ID = "wuxianpi.first-install"

    val supportedCapabilities =
        setOf(
            "setup-tools",
            "termux",
            "persistent-terminal",
            "service-manager",
            "pi-model-api",
            "ubuntu",
        )

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
    fun requirePluginId(value: String): String =
        value.trim().also { require(SAFE_PLUGIN_ID.matches(it)) { "Invalid plugin id: $value" } }

    fun requireVersion(value: String): String =
        value.trim().also { SemanticVersion.parse(it) }

    internal fun compareSemanticVersions(left: String, right: String): Int =
        SemanticVersion.parse(left).compareTo(SemanticVersion.parse(right))

    fun requireCompatible(manifest: RescuePluginManifest): RescuePluginManifest =
        manifest.also {
            require(it.minHostVersion <= HOST_API_VERSION) {
                "Plugin ${it.id} ${it.version} requires host API ${it.minHostVersion}, " +
                    "but this host provides $HOST_API_VERSION"
            }
            val unsupported = it.requiredCapabilities.toSet() - supportedCapabilities
            require(unsupported.isEmpty()) {
                "Plugin ${it.id} ${it.version} requires unsupported capabilities: " +
                    unsupported.sorted().joinToString()
            }
        }

    fun normalizeHubUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Hub URL must start with http:// or https://"
        }
        return normalized
    }
}

private data class SemanticVersion(
    val major: String,
    val minor: String,
    val patch: String,
    val preRelease: List<String>?,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareNumeric(major, other.major).takeIf { it != 0 }?.let { return it }
        compareNumeric(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareNumeric(patch, other.patch).takeIf { it != 0 }?.let { return it }

        val leftPreRelease = preRelease ?: return if (other.preRelease == null) 0 else 1
        val rightPreRelease = other.preRelease ?: return -1
        val sharedSize = minOf(leftPreRelease.size, rightPreRelease.size)
        for (index in 0 until sharedSize) {
            val leftIdentifier = leftPreRelease[index]
            val rightIdentifier = rightPreRelease[index]
            val comparison = comparePreReleaseIdentifier(leftIdentifier, rightIdentifier)
            if (comparison != 0) return comparison
        }
        return leftPreRelease.size.compareTo(rightPreRelease.size)
    }

    companion object {
        private val STRICT_SEMVER =
            Regex(
                "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                    "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)" +
                    "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
                    "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
            )

        fun parse(value: String): SemanticVersion {
            val normalized = value.trim()
            val match = STRICT_SEMVER.matchEntire(normalized)
                ?: throw IllegalArgumentException("Version is not strict SemVer: $value")
            return SemanticVersion(
                major = match.groupValues[1],
                minor = match.groupValues[2],
                patch = match.groupValues[3],
                preRelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.'),
            )
        }

        private fun compareNumeric(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)

        private fun comparePreReleaseIdentifier(left: String, right: String): Int {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric -> compareNumeric(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }
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
    val assistantInstructions: List<RescuePluginAssistantInstruction> = emptyList(),
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
            .put(
                "assistantInstructions",
                JSONArray(assistantInstructions.map(RescuePluginAssistantInstruction::toJson)),
            )
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
            val assistantInstructions =
                json.optJSONArray("assistantInstructions")?.objectList().orEmpty().mapIndexed {
                        index,
                        instruction,
                    ->
                    RescuePluginAssistantInstruction(
                        path = requireRelativePath(instruction.getString("path")),
                    ).also {
                        require(instruction.length() == 1) {
                            "assistantInstructions[$index] may only contain path"
                        }
                    }
                }
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
                assistantInstructions = assistantInstructions,
            )
        }

        private fun requireRelativePath(path: String): String {
            val normalized = path.trim()
            require(
                normalized.isNotEmpty() &&
                    !normalized.startsWith('/') &&
                    !normalized.contains('\\') &&
                    normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
            ) { "Invalid plugin relative path: $path" }
            return normalized
        }
    }
}

data class RescuePluginAssistantInstruction(val path: String) {
    fun toJson(): JSONObject = JSONObject().put("path", path)
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
