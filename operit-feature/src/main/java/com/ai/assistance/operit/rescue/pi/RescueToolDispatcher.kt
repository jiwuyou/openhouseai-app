package com.ai.assistance.operit.rescue.pi

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.setup.WuxianPiSetupContract
import com.ai.assistance.operit.host.setup.WuxianPiSetupToolExecutor
import com.ai.assistance.operit.rescue.plugins.RescuePluginContract
import com.ai.assistance.operit.rescue.plugins.RescuePluginManager
import com.ai.assistance.operit.rescue.memory.RescueMemoryPatch
import com.ai.assistance.operit.rescue.resources.ApkResourceOfferStatus
import com.ai.assistance.operit.rescue.resources.ApkResourceOfferStore
import com.ai.assistance.operit.rescue.ui.plugins.RescuePluginMarketActivity

/** Executes existing Operit tools plus the fixed WuxianPi repair tools exposed to Rescue Pi. */
class RescueToolDispatcher private constructor(
    private val appContext: Context,
    private val toolHandler: AIToolHandler?,
    private val operationsProvider: () -> OperitHostOperations,
    private val deferUserActions: Boolean,
) {
    data class Completion(
        val content: String,
        val details: JSONObject,
        val isError: Boolean,
        val error: String?,
        val userActionRequired: Boolean = false,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("content", content)
                .put("details", details)
                .put("isError", isError)
                .put("userActionRequired", userActionRequired)
                .put("error", error ?: JSONObject.NULL)
    }

    constructor(context: Context) : this(
        appContext = context.applicationContext,
        toolHandler = AIToolHandler.getInstance(context.applicationContext),
        operationsProvider = OperitHostProvider::operationsOrUnsupported,
        deferUserActions = context.packageName == NATIVE_APPLICATION_ID,
    )

    internal constructor(
        context: Context,
        operationsProvider: () -> OperitHostOperations,
        deferUserActions: Boolean = false,
    ) : this(context, null, operationsProvider, deferUserActions)

    private val setupToolExecutor = WuxianPiSetupToolExecutor(operationsProvider)
    private val pluginManager by lazy { RescuePluginManager.get(appContext) }
    private val resourceOfferStore by lazy { ApkResourceOfferStore.get(appContext) }
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()

    init {
        toolHandler?.registerDefaultTools()
    }

    suspend fun execute(
        catalog: RescueToolCatalog,
        toolName: String,
        args: JSONObject,
        onUpdate: suspend (ToolResult) -> Unit,
    ): Completion {
        catalog.requireTool(toolName)
        return if (catalog.isOperitTool(toolName)) {
            executeOperitTool(toolName, args, onUpdate)
        } else {
            executeRepairTool(toolName, args)
        }
    }

    private suspend fun executeOperitTool(
        toolName: String,
        args: JSONObject,
        onUpdate: suspend (ToolResult) -> Unit,
    ): Completion {
        val effectiveArgs = JSONObject(args.toString())
        if (
            deferUserActions &&
            toolName in FILE_SYSTEM_TOOL_NAMES &&
            effectiveArgs.optString("environment").isBlank()
        ) {
            effectiveArgs.put("environment", RescueToolCatalog.TERMUX_HOME_ENVIRONMENT)
        }
        val tool =
            AITool(
                name = toolName,
                parameters =
                    effectiveArgs.keys().asSequence().map { key ->
                        ToolParameter(key, jsonValueToParameter(effectiveArgs.get(key)))
                    }.toList(),
            )
        var finalResult: ToolResult? = null
        requireNotNull(toolHandler) { "Operit tool execution is unavailable in this dispatcher" }
            .executeToolAndStream(tool).collect { result ->
            finalResult = result
            onUpdate(result)
        }
        val result = requireNotNull(finalResult) {
            "Tool $toolName completed without producing a result"
        }
        val resultContent = result.result.toString()
        val content =
            if (result.success) {
                resultContent
            } else {
                result.error?.takeIf { it.isNotBlank() }
                    ?: resultContent.takeIf { it.isNotBlank() }
                    ?: "Tool $toolName failed"
            }
        return Completion(
            content = content,
            details = JSONObject(result.result.toJson()),
            isError = !result.success,
            error = result.error,
        )
    }

    private suspend fun executeRepairTool(toolName: String, args: JSONObject): Completion =
        try {
            when (toolName) {
                in WuxianPiSetupContract.toolNames ->
                    hostCompletion(setupToolExecutor.execute(toolName, appContext))
                RescuePluginContract.TOOL_SEARCH ->
                    success(pluginManager.search(args.optString("query")))
                RescuePluginContract.TOOL_LIST_INSTALLED ->
                    success(pluginManager.listInstalled())
                RescuePluginContract.TOOL_INSTALL ->
                    success(
                        pluginManager.install(
                            args.getString("pluginId"),
                            args.optString("version").takeIf { it.isNotBlank() },
                        ).toJson()
                    )
                RescuePluginContract.TOOL_UPDATE ->
                    success(pluginManager.update(args.getString("pluginId")).toJson())
                RescuePluginContract.TOOL_READ_DOCUMENT ->
                    success(
                        pluginManager.readDocument(
                            args.getString("pluginId"),
                            args.optString("path").takeIf { it.isNotBlank() },
                        )
                    )
                RescuePluginContract.TOOL_START_WORKFLOW ->
                    success(
                        pluginManager.startWorkflow(
                            args.getString("pluginId"),
                            args.optString("path").takeIf { it.isNotBlank() },
                        )
                    )
                RescuePluginContract.TOOL_GET_COMMENTS ->
                    success(
                        pluginManager.getComments(
                            args.getString("pluginId"),
                            args.optString("version").takeIf { it.isNotBlank() },
                        )
                    )
                RescuePluginContract.TOOL_DRAFT_COMMENT ->
                    success(
                        pluginManager.draftAgentComment(
                            pluginId = args.getString("pluginId"),
                            pluginVersion = args.getString("pluginVersion"),
                            type = args.optString("type", "compatibility_report"),
                            rating =
                                args.optInt("rating", 0).takeIf { args.has("rating") && it in 1..5 },
                            content = args.getString("content"),
                            environment = args.optJSONObject("environment") ?: JSONObject(),
                        )
                    )
                RescuePluginContract.TOOL_PUBLISH_COMMENT ->
                    deferredPluginCommentPublish(args.getString("draftId"))
                RescuePluginContract.TOOL_OPEN_MARKET -> openPluginMarket(args)
                RescuePluginContract.TOOL_READ_MEMORY ->
                    success(pluginManager.readMemory().toJson())
                RescuePluginContract.TOOL_PATCH_MEMORY ->
                    success(
                        pluginManager.patchMemory(
                            RescueMemoryPatch(
                                expectedRevision = args.getLong("expectedRevision"),
                                section = args.getString("section"),
                                content = args.getString("content"),
                                source = args.getString("source"),
                                confidence = args.getString("confidence"),
                                userConfirmed = args.getBoolean("userConfirmed"),
                            )
                        ).toJson()
                    )
                RescuePluginContract.TOOL_UNDO_MEMORY ->
                    success(pluginManager.undoMemory().toJson())
                RescuePluginContract.TOOL_INSPECT_APK_RESOURCE_OFFER ->
                    success(
                        (resourceOfferStore.current()?.toJson()
                            ?: JSONObject().put("available", false))
                    )
                RescuePluginContract.TOOL_STAGE_APK_RESOURCE_BUNDLE ->
                    hostCompletion(
                        OperitHostProvider.operationsOrUnsupported().stageApkInstallBundle()
                    )
                RescuePluginContract.TOOL_COMPLETE_APK_RESOURCE_OFFER -> {
                    val status =
                        when (args.getString("status").trim().lowercase()) {
                            "satisfied" -> ApkResourceOfferStatus.SATISFIED
                            "superseded" -> ApkResourceOfferStatus.SUPERSEDED
                            "failed" -> ApkResourceOfferStatus.FAILED
                            else -> throw IllegalArgumentException(
                                "Unsupported APK resource offer status"
                            )
                        }
                    val detail = args.getString("detail")
                    if (status == ApkResourceOfferStatus.SATISFIED) {
                        val offer = requireNotNull(resourceOfferStore.current()) {
                            "APK resource offer is unavailable"
                        }
                        val hostStatus = operationsProvider().wuxianPiSetupStatus()
                        require(hostStatus.success) {
                            hostStatus.error ?: "Unable to verify the real Termux setup status"
                        }
                        val gate = verifySatisfiedApkResourceOffer(offer.toJson(), hostStatus.details)
                        require(gate.accepted) {
                            "APK resource offer cannot be satisfied: ${gate.failures.joinToString(", ")}"
                        }
                    }
                    success(
                        requireNotNull(
                            pluginManager.completeApkResourceOffer(status, detail)
                        ) { "APK resource offer is unavailable" }.toJson()
                    )
                }
                "runtime_status" -> hostCompletion(OperitHostProvider.operationsOrUnsupported().runtimeStatus())
                "connection_test" -> {
                    val url =
                        args.optString("url").trim().ifEmpty { DEFAULT_RUNTIME_HEALTH_URL }
                    success(connectionTest(url))
                }
                "read_diagnostics" -> {
                    val maxBytes = args.optInt("maxBytes", DEFAULT_DIAGNOSTIC_BYTES)
                        .coerceIn(1024, MAX_DIAGNOSTIC_BYTES)
                    hostCompletion(
                        OperitHostProvider.operationsOrUnsupported().readRuntimeDiagnostics(maxBytes)
                    )
                }
                "restart_runtime" -> hostCompletion(
                    OperitHostProvider.operationsOrUnsupported().restartRuntime()
                )
                "redeploy_runtime" -> hostCompletion(startRedeployRuntime())
                "verify_payload" -> success(verifyPayload())
                "repair_job_status" -> hostCompletion(
                    OperitHostProvider.operationsOrUnsupported()
                        .repairJobStatus(args.getString("jobId"))
                )
                "open_host" -> hostCompletion(
                    OperitHostProvider.operationsOrUnsupported().openHostApp(appContext)
                )
                "export_logs" -> exportLogs()
                else -> error("Unknown rescue tool: $toolName")
            }
        } catch (failure: Exception) {
            error(failure.message ?: "$toolName failed", failure)
        }

    private fun connectionTest(url: String): JSONObject {
        val startedAt = System.nanoTime()
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            return JSONObject()
                .put("url", url)
                .put("status", response.code)
                .put("successful", response.isSuccessful)
                .put("latencyMs", elapsedMillis)
                .put("body", body.take(MAX_HTTP_BODY_CHARS))
        }
    }

    private suspend fun startRedeployRuntime(): OperitHostOperationResult {
        val assetPath = runtimePayloadAssetPath()
        val payload = appContext.assets.open(assetPath).use { it.readBytes() }
        return OperitHostProvider.operationsOrUnsupported().redeployRuntime(
            payload = payload,
            fileName = PAYLOAD_FILE_NAME,
            mimeType = PAYLOAD_MIME_TYPE,
        )
    }

    private fun verifyPayload(): JSONObject {
        val assetPath = runtimePayloadAssetPath()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        appContext.assets.open(assetPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return JSONObject()
            .put("asset", assetPath)
            .put("size", size)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("valid", size > 0)
    }

    private fun runtimePayloadAssetPath(): String =
        PAYLOAD_ASSET_PATHS.firstOrNull { assetPath ->
            runCatching { appContext.assets.open(assetPath).use { } }.isSuccess
        } ?: throw IllegalStateException("Bundled WuxianPi Runtime asset is missing")

    private suspend fun exportLogs(): Completion {
        val diagnostics =
            OperitHostProvider.operationsOrUnsupported()
                .readRuntimeDiagnostics(MAX_DIAGNOSTIC_BYTES)
        if (!diagnostics.success) return hostCompletion(diagnostics)
        return hostCompletion(
            OperitHostProvider.operationsOrUnsupported()
                .exportDiagnostics(diagnostics.details.toString(2))
        )
    }

    private fun hostCompletion(result: OperitHostOperationResult): Completion {
        val details = WuxianPiSetupContract.normalizedDetails(result)
        val userActionRequired = WuxianPiSetupContract.requiresUserAction(result)
        val isError = !result.success && !userActionRequired
        val content = details.toString()
        return Completion(
            content = content,
            details = details,
            isError = isError,
            error = (result.error ?: result.message.takeIf { !result.success }).takeIf { isError },
            userActionRequired = userActionRequired,
        )
    }

    private fun success(details: JSONObject): Completion =
        Completion(details.toString(), details, isError = false, error = null)

    private suspend fun deferredPluginCommentPublish(draftId: String): Completion {
        val details = pluginManager.preparePublish(draftId)
        val message = details.getString("message")
        return Completion(
            content = message,
            details = details,
            isError = false,
            error = null,
            userActionRequired = true,
        )
    }

    private fun openPluginMarket(args: JSONObject): Completion {
        val intent =
            RescuePluginMarketActivity.createIntent(
                appContext,
                args.optString("pluginId").takeIf { it.isNotBlank() },
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return success(JSONObject().put("opened", true).put("message", "Rescue Plugin Market opened"))
    }

    private fun error(message: String, failure: Throwable? = null): Completion {
        val details = JSONObject().put("message", message)
        failure?.javaClass?.name?.let { details.put("exception", it) }
        return Completion(message, details, isError = true, error = message)
    }

    private fun jsonValueToParameter(value: Any): String =
        when (value) {
            is JSONObject, is JSONArray -> value.toString()
            JSONObject.NULL -> "null"
            else -> value.toString()
        }

    companion object {
        private const val NATIVE_APPLICATION_ID = "com.wuxianpi"
        private const val DEFAULT_RUNTIME_HEALTH_URL = "http://127.0.0.1:8765/health"
        private val PAYLOAD_ASSET_PATHS =
            listOf(
                "openhouse-resources-v2/runtime-aarch64.tgz",
                "openhouse/product-payloads/runtime-aarch64.tgz",
            )
        private const val PAYLOAD_FILE_NAME = "runtime-aarch64.tgz"
        private const val PAYLOAD_MIME_TYPE = "application/gzip"
        private const val DEFAULT_DIAGNOSTIC_BYTES = 32 * 1024
        private const val MAX_DIAGNOSTIC_BYTES = 256 * 1024
        private const val MAX_HTTP_BODY_CHARS = 64 * 1024
        private val FILE_SYSTEM_TOOL_NAMES =
            com.ai.assistance.operit.core.config.SystemToolPrompts.fileSystemTools.tools
                .mapTo(linkedSetOf()) { it.name }
    }
}

internal data class ApkResourceOfferVerification(
    val accepted: Boolean,
    val failures: List<String>,
)

/** SATISFIED means the host independently proved both installed content and live activation. */
internal fun verifySatisfiedApkResourceOffer(
    offer: JSONObject,
    hostDetails: JSONObject,
): ApkResourceOfferVerification {
    val status = hostDetails.optJSONObject("status") ?: hostDetails
    val failures = mutableListOf<String>()
    val expectedOfferId = offer.optString("offerId")
    val expectedSequence = offer.optLong("resourceSetSequence", -1L)

    fun state(vararg keys: String): String? = findStatusValue(status, keys.toSet())
    fun requireState(label: String, expected: String, vararg keys: String) {
        val actual = state(*keys)
        if (!actual.equals(expected, ignoreCase = true)) failures += "$label=${actual ?: "missing"}"
    }
    fun requireOk(label: String, vararg keys: String) {
        val actual = state(*keys)
        if (actual?.lowercase() !in VERIFIED_OK_VALUES) failures += "$label=${actual ?: "missing"}"
    }

    requireState("delivery", "ready", "delivery", "deliveryState", "resourceDelivery")
    requireState("content", "installed", "content", "contentState", "installState")
    requireState("activation", "ready", "activation", "activationState")

    val actualOfferId = state("offerId", "apkOfferId", "resourceOfferId")
    if (expectedOfferId.isBlank() || actualOfferId != expectedOfferId) {
        failures += "offerId=${actualOfferId ?: "missing"}"
    }
    val actualSequence = findStatusLong(status, setOf("resourceSetSequence", "setSequence", "sequence"))
    if (expectedSequence < 0L || actualSequence != expectedSequence) {
        failures += "sequence=${actualSequence ?: "missing"}"
    }

    requireOk("canonicalAuth", "canonicalAuth", "canonicalAuthReady", "canonicalAuthentication")
    requireOk(
        "serviceList",
        "serviceList",
        "serviceListReady",
        "serviceListQuery",
        "canonicalServiceList",
    )
    val unifiedRegistry = state("registry", "registryStatus")
    if (unifiedRegistry?.lowercase() !in VERIFIED_OK_VALUES) {
        requireOk("registryFile", "registryFile", "registryFileReady")
        requireOk("registryApi", "registryApi", "registryApiReady")
    }
    requireOk("wuxianpiHealth", "wuxianpiHealth", "runtimeHealth", "wuxianPiHealth")

    return ApkResourceOfferVerification(failures.isEmpty(), failures)
}

private val VERIFIED_OK_VALUES = setOf("ok", "ready", "success", "healthy", "true")

private fun findStatusValue(root: JSONObject, keys: Set<String>, depth: Int = 0): String? {
    if (depth > 4) return null
    keys.forEach { key ->
        if (root.has(key) && !root.isNull(key)) {
            val value = root.get(key)
            return when (value) {
                is Boolean -> value.toString()
                is Number, is String -> value.toString().trim()
                is JSONObject ->
                    listOf("state", "status", "result", "value", "ok")
                        .firstNotNullOfOrNull { nested ->
                            value.takeIf { it.has(nested) && !it.isNull(nested) }
                                ?.get(nested)?.toString()?.trim()
                        }
                else -> null
            }
        }
    }
    root.keys().forEach { key ->
        val child = root.optJSONObject(key) ?: return@forEach
        findStatusValue(child, keys, depth + 1)?.let { return it }
    }
    return null
}

private fun findStatusLong(root: JSONObject, keys: Set<String>, depth: Int = 0): Long? {
    if (depth > 4) return null
    keys.forEach { key ->
        if (root.has(key) && !root.isNull(key)) {
            root.optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { return it }
            root.optString(key).toLongOrNull()?.let { return it }
        }
    }
    root.keys().forEach { key ->
        val child = root.optJSONObject(key) ?: return@forEach
        findStatusLong(child, keys, depth + 1)?.let { return it }
    }
    return null
}
