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
    operationsProvider: () -> OperitHostOperations,
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
                    if (deferUserActions && toolName in DEFERRED_USER_ACTION_TOOLS) {
                        deferredUserAction(toolName)
                    } else {
                        hostCompletion(setupToolExecutor.execute(toolName, appContext))
                    }
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
                    success(
                        requireNotNull(
                            pluginManager.completeApkResourceOffer(status, args.getString("detail"))
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

    private fun deferredUserAction(toolName: String): Completion {
        val message = "请先阅读说明，然后点击聊天中的操作卡片继续。"
        val details =
            JSONObject()
                .put(WuxianPiSetupContract.DETAIL_OPERATION, toolName)
                .put(WuxianPiSetupContract.DETAIL_SUPPORTED, true)
                .put(WuxianPiSetupContract.DETAIL_SUCCESS, false)
                .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
                .put(WuxianPiSetupContract.DETAIL_DEFERRED_USER_ACTION, toolName)
                .put("message", message)
        return Completion(
            content = message,
            details = details,
            isError = false,
            error = null,
            userActionRequired = true,
        )
    }

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
        private val DEFERRED_USER_ACTION_TOOLS =
            setOf(
                WuxianPiSetupContract.TOOL_REQUEST_TERMUX_HOME_ACCESS,
                WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION,
            )
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
