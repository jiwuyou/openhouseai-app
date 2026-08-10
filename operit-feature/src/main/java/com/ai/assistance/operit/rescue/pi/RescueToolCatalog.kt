package com.ai.assistance.operit.rescue.pi

import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.host.setup.WuxianPiSetupContract
import com.ai.assistance.operit.rescue.plugins.RescuePluginContract
import org.json.JSONArray
import org.json.JSONObject

/** Per-turn tool snapshot for the Android rescue agent. */
class RescueToolCatalog private constructor(
    private val serializedDefinitions: String,
    private val operitToolNames: Set<String>,
) {
    fun toJsonArray(): JSONArray = JSONArray(serializedDefinitions)

    fun isOperitTool(toolName: String): Boolean = toolName in operitToolNames

    fun requireTool(toolName: String) {
        require(toolName in operitToolNames || toolName in RESCUE_TOOL_NAMES) {
            "Rescue Pi requested an unknown Android tool: $toolName"
        }
    }

    companion object {
        const val TERMUX_HOME_ENVIRONMENT = "repo:termux-home"
        private val SUPPORTED_PARAMETER_TYPES =
            setOf("string", "boolean", "integer", "number", "object", "array")

        private val RESCUE_DEFINITIONS =
            listOf(
                definition(
                    name = WuxianPiSetupContract.TOOL_INSPECT,
                    description =
                        "Inspect the real Termux, permissions, persistent tmux, resources, service-manager, WuxianPi, and Ubuntu setup state before changing anything.",
                ),
                definition(
                    name = WuxianPiSetupContract.TOOL_PREPARE_RUNTIME_HOST,
                    description =
                        "Prepare the correct Termux runtime host. This may open or install the WuxianPi All-in-One host and can require a user action.",
                ),
                definition(
                    name = WuxianPiSetupContract.TOOL_REQUEST_TERMUX_HOME_ACCESS,
                    description =
                        "Request Termux Home file access when the active host needs SAF. Success registers repo:termux-home and proves real read/write access for rescue file tools.",
                ),
                definition(
                    name = WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION,
                    description =
                        "Request external Termux RUN_COMMAND access when needed. The result accurately reports any pending user action.",
                ),
                definition(
                    name = WuxianPiSetupContract.TOOL_PREPARE_PERSISTENT_TERMUX,
                    description =
                        "Install only the minimum tmux prerequisites, verify tmux, and prepare the durable managed Termux terminal used by setup. Reuses the host tmux backend.",
                ),
                definition(
                    name = WuxianPiSetupContract.TOOL_START_SETUP,
                    description =
                        "Stage bundled setup resources for service-manager, WuxianPi, and Ubuntu, then return the foreground install command. Launch that command with termux_exec_command using the returned session_name and yield_time_ms.",
                ),
                definition(
                    name = WuxianPiSetupContract.TOOL_SETUP_STATUS,
                    description =
                        "Read durable WuxianPi setup status from the active host and persistent Termux state. Use it after starting setup and after Rescue AI restarts.",
                ),
                definition(
                    name = RescuePluginContract.TOOL_SEARCH,
                    description = "Search the online Rescue Plugin Hub for repair knowledge and workflows.",
                    properties =
                        JSONObject().put(
                            "query",
                            JSONObject().put("type", "string").put("description", "Optional search text"),
                        ),
                ),
                definition(
                    name = RescuePluginContract.TOOL_LIST_INSTALLED,
                    description = "List installed Rescue plugins, including active and previous versions.",
                ),
                definition(
                    name = RescuePluginContract.TOOL_INSTALL,
                    description = "Download, verify, and activate a Rescue plugin. A failed install keeps the current version active.",
                    properties =
                        JSONObject()
                            .put("pluginId", JSONObject().put("type", "string").put("description", "Plugin id"))
                            .put("version", JSONObject().put("type", "string").put("description", "Optional version")),
                    required = JSONArray().put("pluginId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_UPDATE,
                    description = "Update an installed Rescue plugin to the Hub's latest version.",
                    properties =
                        JSONObject().put(
                            "pluginId",
                            JSONObject().put("type", "string").put("description", "Plugin id"),
                        ),
                    required = JSONArray().put("pluginId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_READ_DOCUMENT,
                    description = "Read a document from an installed Rescue plugin.",
                    properties =
                        JSONObject()
                            .put("pluginId", JSONObject().put("type", "string").put("description", "Plugin id"))
                            .put("path", JSONObject().put("type", "string").put("description", "Optional document path")),
                    required = JSONArray().put("pluginId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_START_WORKFLOW,
                    description = "Load an installed Rescue workflow. Follow its ordered steps by calling existing high-level tools; the plugin does not replace those tools.",
                    properties =
                        JSONObject()
                            .put("pluginId", JSONObject().put("type", "string").put("description", "Plugin id"))
                            .put("path", JSONObject().put("type", "string").put("description", "Optional workflow path")),
                    required = JSONArray().put("pluginId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_GET_COMMENTS,
                    description = "Read user comments and Agent compatibility reports for a plugin version.",
                    properties =
                        JSONObject()
                            .put("pluginId", JSONObject().put("type", "string").put("description", "Plugin id"))
                            .put("version", JSONObject().put("type", "string").put("description", "Optional plugin version")),
                    required = JSONArray().put("pluginId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_DRAFT_COMMENT,
                    description = "Save an Agent plugin comment as a local draft. This never publishes the comment.",
                    properties =
                        JSONObject()
                            .put("pluginId", JSONObject().put("type", "string").put("description", "Plugin id"))
                            .put("pluginVersion", JSONObject().put("type", "string").put("description", "Tested plugin version"))
                            .put("type", JSONObject().put("type", "string").put("description", "Comment type, usually compatibility_report"))
                            .put("rating", JSONObject().put("type", "integer").put("description", "Optional rating from 1 to 5"))
                            .put("content", JSONObject().put("type", "string").put("description", "Review text without secrets or raw logs"))
                            .put("environment", JSONObject().put("type", "object").put("description", "Optional structured device compatibility fields")),
                    required = JSONArray().put("pluginId").put("pluginVersion").put("content"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_PUBLISH_COMMENT,
                    description = "Prepare a saved Agent comment draft for user-confirmed publication. This call never sends it; the user must click the returned action card.",
                    properties =
                        JSONObject().put(
                            "draftId",
                            JSONObject().put("type", "string").put("description", "Local draft id returned by draft_rescue_plugin_comment"),
                        ),
                    required = JSONArray().put("draftId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_OPEN_MARKET,
                    description = "Open the Rescue Plugin Market activity for user browsing, installation, updates, and comments.",
                    properties =
                        JSONObject().put(
                            "pluginId",
                            JSONObject().put("type", "string").put("description", "Optional plugin id to focus"),
                        ),
                ),
                definition(
                    name = RescuePluginContract.TOOL_READ_MEMORY,
                    description =
                        "Read the revisioned Rescue AI memory document. Use the returned revision for a later patch.",
                ),
                definition(
                    name = RescuePluginContract.TOOL_PATCH_MEMORY,
                    description =
                        "Append one verified, durable fact or user-approved note to Rescue memory. Never store secrets, full logs, or entire conversations.",
                    properties =
                        JSONObject()
                            .put("expectedRevision", JSONObject().put("type", "integer").put("description", "Revision returned by read_rescue_memory"))
                            .put("section", JSONObject().put("type", "string").put("description", "preferences, user_notes, device_facts, completed, or follow_up"))
                            .put("content", JSONObject().put("type", "string").put("description", "Short durable memory entry"))
                            .put("source", JSONObject().put("type", "string").put("description", "user, android, termux, service-manager, or plugin"))
                            .put("confidence", JSONObject().put("type", "string").put("description", "high, medium, or low"))
                            .put("userConfirmed", JSONObject().put("type", "boolean").put("description", "True only when the user explicitly confirmed a preference or note")),
                    required =
                        JSONArray()
                            .put("expectedRevision")
                            .put("section")
                            .put("content")
                            .put("source")
                            .put("confidence")
                            .put("userConfirmed"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_UNDO_MEMORY,
                    description = "Restore the most recent Rescue memory history version.",
                ),
                definition(
                    name = RescuePluginContract.TOOL_INSPECT_APK_RESOURCE_OFFER,
                    description =
                        "Inspect the Android-private APK resource offer and its five bundled resource digests without copying archives into Termux.",
                ),
                definition(
                    name = RescuePluginContract.TOOL_STAGE_APK_RESOURCE,
                    description =
                        "After comparison shows that one bundled resource is needed, verify and stage only that resource into the Termux APK-offer cache.",
                    properties =
                        JSONObject().put(
                            "resourceId",
                            JSONObject().put("type", "string").put("description", "service-manager, openhouse-control-plane, openhouse-runtime, wuyou, or openhouse-web"),
                        ),
                    required = JSONArray().put("resourceId"),
                ),
                definition(
                    name = RescuePluginContract.TOOL_COMPLETE_APK_RESOURCE_OFFER,
                    description =
                        "Record the verified APK resource result. satisfied and superseded require concrete verification evidence; failed keeps the reminder active.",
                    properties =
                        JSONObject()
                            .put("status", JSONObject().put("type", "string").put("description", "satisfied, superseded, or failed"))
                            .put("detail", JSONObject().put("type", "string").put("description", "Short verification evidence or failure reason")),
                    required = JSONArray().put("status").put("detail"),
                ),
                definition(
                    name = "runtime_status",
                    description =
                        "Inspect host availability and whether the WuxianPi Node runtime health endpoint is responding.",
                ),
                definition(
                    name = "connection_test",
                    description = "Test an HTTP endpoint and report status, latency, and response body.",
                    properties =
                        JSONObject().put(
                            "url",
                            JSONObject()
                                .put("type", "string")
                                .put("description", "HTTP URL to test; defaults to the local runtime health endpoint"),
                        ),
                ),
                definition(
                    name = "read_diagnostics",
                    description =
                        "Read the local runtime health result and recent runtime or repair logs provided by the active host.",
                    properties =
                        JSONObject().put(
                            "maxBytes",
                            JSONObject()
                                .put("type", "integer")
                                .put("description", "Maximum number of UTF-8 bytes returned from each log")
                                .put("default", 32768),
                        ),
                ),
                definition(
                    name = "restart_runtime",
                    description =
                        "Ask the active host to restart the installed WuxianPi Node runtime.",
                ),
                definition(
                    name = "redeploy_runtime",
                    description =
                        "Ask the active host to stage and redeploy the bundled WuxianPi runtime.",
                ),
                definition(
                    name = "verify_payload",
                    description =
                        "Verify that the APK-bundled WuxianPi runtime payload is readable and return its size and SHA-256 digest.",
                ),
                definition(
                    name = "repair_job_status",
                    description = "Read status and recent output for a previously started repair job.",
                    properties =
                        JSONObject().put(
                            "jobId",
                            JSONObject()
                                .put("type", "string")
                                .put("description", "Job identifier returned by redeploy_runtime or restart_runtime"),
                        ),
                    required = JSONArray().put("jobId"),
                ),
                definition(
                    name = "open_host",
                    description = "Open the active host application for user-visible recovery work.",
                ),
                definition(
                    name = "export_logs",
                    description =
                        "Export a rescue diagnostic report through the active host.",
                ),
            )

        private val RESCUE_TOOL_NAMES =
            RESCUE_DEFINITIONS.mapTo(LinkedHashSet()) { it.getString("name") }

        fun default(useTermuxHomeRepository: Boolean = false): RescueToolCatalog =
            from(
                (
                    SystemToolPrompts.fileSystemTools.tools +
                        SystemToolPrompts.httpTools.tools +
                        SystemToolPrompts.getHostTerminalToolCategoryEn().tools
                )
                    .distinctBy(ToolPrompt::name),
                useTermuxHomeRepository,
            )

        fun from(
            tools: List<ToolPrompt>,
            useTermuxHomeRepository: Boolean = false,
        ): RescueToolCatalog {
            val names = LinkedHashSet<String>()
            val definitions = JSONArray()
            tools.forEach { tool ->
                val toolName = tool.name.trim()
                require(toolName.isNotEmpty()) { "Android tool name must not be blank" }
                require(toolName !in RESCUE_TOOL_NAMES) {
                    "Operit tool name conflicts with a fixed rescue tool: $toolName"
                }
                require(names.add(toolName)) { "Duplicate Android tool name: $toolName" }
                require(tool.description.isNotBlank()) {
                    "Android tool description must not be blank: $toolName"
                }

                val properties = JSONObject()
                val required = JSONArray()
                val parameterNames = HashSet<String>()
                tool.parametersStructured.orEmpty().forEach { parameter ->
                    val parameterName = parameter.name.trim()
                    require(parameterName.isNotEmpty()) {
                        "Android tool parameter name must not be blank: $toolName"
                    }
                    require(parameterNames.add(parameterName)) {
                        "Duplicate parameter $parameterName in Android tool $toolName"
                    }
                    require(parameter.type in SUPPORTED_PARAMETER_TYPES) {
                        "Unsupported parameter type ${parameter.type} for $toolName.$parameterName"
                    }
                    val schema =
                        JSONObject()
                            .put("type", parameter.type)
                            .put("description", parameter.description)
                    parameter.default?.let { rawDefault ->
                        parseDefault(rawDefault, parameter.type)?.let { schema.put("default", it) }
                    }
                    if (
                        useTermuxHomeRepository &&
                        toolName in FILE_SYSTEM_TOOL_NAMES &&
                        parameterName == "environment"
                    ) {
                        schema.put("default", TERMUX_HOME_ENVIRONMENT)
                        schema.put(
                            "description",
                            parameter.description +
                                "; Rescue AI defaults to $TERMUX_HOME_ENVIRONMENT for Termux Home files",
                        )
                    }
                    properties.put(parameterName, schema)
                    if (parameter.required) required.put(parameterName)
                }

                definitions.put(
                    definition(
                        name = toolName,
                        description = tool.description,
                        properties = properties,
                        required = required,
                    )
                )
            }
            RESCUE_DEFINITIONS.forEach(definitions::put)
            return RescueToolCatalog(definitions.toString(), names.toSet())
        }

        private fun definition(
            name: String,
            description: String,
            properties: JSONObject = JSONObject(),
            required: JSONArray = JSONArray(),
        ): JSONObject =
            JSONObject()
                .put("name", name)
                .put("label", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)
                        .put("additionalProperties", false),
                )

        private fun parseDefault(value: String, type: String): Any? {
            val trimmed = value.trim()
            return when (type) {
                "object" -> runCatching { JSONObject(trimmed) }.getOrNull()
                "array" -> runCatching { JSONArray(trimmed) }.getOrNull()
                "boolean" ->
                    when (trimmed) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                "integer" -> trimmed.toLongOrNull()
                "number" -> trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
                else -> value
            }
        }

        private val FILE_SYSTEM_TOOL_NAMES =
            SystemToolPrompts.fileSystemTools.tools.mapTo(linkedSetOf(), ToolPrompt::name)
    }
}
