package com.ai.assistance.operit.rescue.pi

import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.data.model.ToolPrompt
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
        private val SUPPORTED_PARAMETER_TYPES =
            setOf("string", "boolean", "integer", "number", "object", "array")

        private val RESCUE_DEFINITIONS =
            listOf(
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

        fun default(): RescueToolCatalog =
            from(
                (SystemToolPrompts.fileSystemTools.tools + SystemToolPrompts.httpTools.tools)
                    .distinctBy(ToolPrompt::name)
            )

        fun from(tools: List<ToolPrompt>): RescueToolCatalog {
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
    }
}
