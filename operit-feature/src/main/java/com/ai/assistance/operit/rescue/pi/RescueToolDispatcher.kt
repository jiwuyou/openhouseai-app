package com.ai.assistance.operit.rescue.pi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Executes existing Operit tools plus the fixed WuxianPi repair tools exposed to Rescue Pi. */
class RescueToolDispatcher(context: Context) {
    data class Completion(
        val content: String,
        val details: JSONObject,
        val isError: Boolean,
        val error: String?,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("content", content)
                .put("details", details)
                .put("isError", isError)
                .put("error", error ?: JSONObject.NULL)
    }

    private val appContext = context.applicationContext
    private val toolHandler = AIToolHandler.getInstance(appContext)
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()

    init {
        toolHandler.registerDefaultTools()
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
        val tool =
            AITool(
                name = toolName,
                parameters =
                    args.keys().asSequence().map { key ->
                        ToolParameter(key, jsonValueToParameter(args.get(key)))
                    }.toList(),
            )
        var finalResult: ToolResult? = null
        toolHandler.executeToolAndStream(tool).collect { result ->
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

    private fun executeRepairTool(toolName: String, args: JSONObject): Completion =
        try {
            when (toolName) {
                "runtime_status" -> success(runtimeStatus())
                "connection_test" -> {
                    val url =
                        args.optString("url").trim().ifEmpty { DEFAULT_RUNTIME_HEALTH_URL }
                    success(connectionTest(url))
                }
                "read_diagnostics" -> {
                    val maxBytes = args.optInt("maxBytes", DEFAULT_DIAGNOSTIC_BYTES)
                        .coerceIn(1024, MAX_DIAGNOSTIC_BYTES)
                    success(readDiagnostics(maxBytes))
                }
                "restart_runtime" -> success(startRestartJob())
                "redeploy_runtime" -> success(startRedeployJob())
                "verify_payload" -> success(verifyPayload())
                "repair_job_status" -> success(repairJobStatus(args.getString("jobId")))
                "open_termux" -> success(openTermux())
                "export_logs" -> success(exportLogs())
                else -> error("Unknown rescue tool: $toolName")
            }
        } catch (failure: Exception) {
            error(failure.message ?: "$toolName failed", failure)
        }

    private fun runtimeStatus(): JSONObject {
        val termuxInstalled =
            runCatching { appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.isSuccess
        val health = runCatching { connectionTest(DEFAULT_RUNTIME_HEALTH_URL) }
        val runtimeReachable = health.getOrNull()?.optBoolean("successful", false) == true
        val persistedTrees =
            appContext.contentResolver.persistedUriPermissions.map { permission ->
                JSONObject()
                    .put("uri", permission.uri.toString())
                    .put("read", permission.isReadPermission)
                    .put("write", permission.isWritePermission)
            }
        return JSONObject()
            .put("termuxInstalled", termuxInstalled)
            .put("runtimeReachable", runtimeReachable)
            .put("runtime", health.getOrElse { JSONObject().put("error", it.message) })
            .put("persistedSafTrees", JSONArray(persistedTrees))
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

    private fun readDiagnostics(maxBytes: Int): JSONObject {
        val output = JSONObject().put("runtime", runtimeStatus())
        val root = termuxHomeTree()
            ?: return output.put("safError", "No persisted writable Termux Home SAF grant")
        val logs = JSONArray()
        DIAGNOSTIC_PATHS.forEach { relativePath ->
            findDocument(root, relativePath)?.takeIf { it.isFile }?.let { document ->
                logs.put(
                    JSONObject()
                        .put("path", relativePath)
                        .put("content", readDocumentText(document, maxBytes))
                )
            }
        }
        findDocument(root, ".wuxianpi/jobs")
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.forEach { job ->
                listOf("status.json", "output.log").forEach { name ->
                    job.findFile(name)?.takeIf { it.isFile }?.let { document ->
                        logs.put(
                            JSONObject()
                                .put("path", ".wuxianpi/jobs/${job.name}/$name")
                                .put("content", readDocumentText(document, maxBytes))
                        )
                    }
                }
            }
        return output.put("logs", logs)
    }

    private fun startRestartJob(): JSONObject {
        val jobId = "restart-${UUID.randomUUID()}"
        val command =
            repairJobCommand(
                jobId,
                """
                if [ ! -x "${'$'}HOME/.local/bin/wuxianpi-node-start" ]; then
                  echo "wuxianpi-node-start is not installed" >&2
                  exit 12
                fi
                pkill -f 'wuxianpi-node.*8765' 2>/dev/null || true
                nohup "${'$'}HOME/.local/bin/wuxianpi-node-start" >"${'$'}HOME/.local/share/openhouseai/runtime/node.log" 2>&1 &
                i=0
                while [ "${'$'}i" -lt 20 ]; do
                  if curl -fsS --max-time 2 "$DEFAULT_RUNTIME_HEALTH_URL" >/dev/null 2>&1; then exit 0; fi
                  i=${'$'}((i + 1))
                  sleep 1
                done
                echo "runtime health endpoint did not recover" >&2
                exit 13
                """.trimIndent(),
            )
        submitTermuxCommand(command, "Restart WuxianPi runtime")
        return JSONObject().put("submitted", true).put("jobId", jobId)
    }

    private fun startRedeployJob(): JSONObject {
        val payload = stageBundledPayload()
        val jobId = "redeploy-${UUID.randomUUID()}"
        val command =
            repairJobCommand(
                jobId,
                """
                payload="${'$'}HOME/.wuxianpi/payload/$PAYLOAD_FILE_NAME"
                test -s "${'$'}payload"
                work="${'$'}HOME/.wuxianpi/tmp/$jobId"
                rm -rf "${'$'}work"
                mkdir -p "${'$'}work"
                tar -xzf "${'$'}payload" -C "${'$'}work"
                "${'$'}work/install.sh"
                pkill -f 'wuxianpi-node.*8765' 2>/dev/null || true
                nohup "${'$'}HOME/.local/bin/wuxianpi-node-start" >"${'$'}HOME/.local/share/openhouseai/runtime/node.log" 2>&1 &
                """.trimIndent(),
            )
        submitTermuxCommand(command, "Redeploy WuxianPi runtime")
        return JSONObject()
            .put("submitted", true)
            .put("jobId", jobId)
            .put("payload", payload)
    }

    private fun verifyPayload(): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        appContext.assets.open(PAYLOAD_ASSET_PATH).use { input ->
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
            .put("asset", PAYLOAD_ASSET_PATH)
            .put("size", size)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("valid", size > 0)
    }

    private fun stageBundledPayload(): JSONObject {
        val root = requireNotNull(termuxHomeTree()) {
            "A writable Termux Home SAF grant is required to redeploy the bundled runtime"
        }
        val payloadDirectory = requireDirectory(root, ".wuxianpi/payload")
        payloadDirectory.findFile(PAYLOAD_FILE_NAME)?.delete()
        val payload = requireNotNull(payloadDirectory.createFile(PAYLOAD_MIME_TYPE, PAYLOAD_FILE_NAME)) {
            "Unable to create $PAYLOAD_FILE_NAME through SAF"
        }
        appContext.assets.open(PAYLOAD_ASSET_PATH).use { input ->
            requireNotNull(appContext.contentResolver.openOutputStream(payload.uri, "w")) {
                "Unable to open the staged payload for writing"
            }.use { output -> input.copyTo(output) }
        }
        return JSONObject()
            .put("uri", payload.uri.toString())
            .put("name", payload.name)
            .put("size", payload.length())
    }

    private fun repairJobStatus(jobId: String): JSONObject {
        require(jobId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid repair job id" }
        val root = requireNotNull(termuxHomeTree()) {
            "No persisted writable Termux Home SAF grant"
        }
        val job = requireNotNull(findDocument(root, ".wuxianpi/jobs/$jobId")) {
            "Repair job does not exist: $jobId"
        }
        val status = job.findFile("status.json")?.let { readDocumentText(it, 16 * 1024) }
        val output = job.findFile("output.log")?.let { readDocumentText(it, 64 * 1024) }
        return JSONObject()
            .put("jobId", jobId)
            .put("status", status ?: JSONObject.NULL)
            .put("output", output ?: JSONObject.NULL)
    }

    private fun openTermux(): JSONObject {
        val intent = requireNotNull(appContext.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)) {
            "Termux is not installed"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return JSONObject().put("opened", true).put("package", TERMUX_PACKAGE)
    }

    private fun exportLogs(): JSONObject {
        val report = readDiagnostics(MAX_DIAGNOSTIC_BYTES).toString(2)
        val root = requireNotNull(termuxHomeTree()) {
            "A writable Termux Home SAF grant is required to export diagnostics"
        }
        val exports = requireDirectory(root, ".wuxianpi/exports")
        val fileName = "rescue-report-${System.currentTimeMillis()}.json"
        val document = requireNotNull(exports.createFile("application/json", fileName)) {
            "Unable to create rescue report"
        }
        requireNotNull(appContext.contentResolver.openOutputStream(document.uri, "w")) {
            "Unable to write rescue report"
        }.bufferedWriter().use { it.write(report) }
        return JSONObject()
            .put("exported", true)
            .put("path", ".wuxianpi/exports/$fileName")
            .put("uri", document.uri.toString())
    }

    private fun submitTermuxCommand(command: String, label: String) {
        val intent =
            Intent(TERMUX_RUN_COMMAND_ACTION)
                .setComponent(ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE))
                .putExtra(TERMUX_RUN_COMMAND_PATH, TERMUX_SHELL_PATH)
                .putExtra(TERMUX_RUN_COMMAND_ARGUMENTS, arrayOf("-lc", command))
                .putExtra(TERMUX_RUN_COMMAND_WORKDIR, TERMUX_HOME_PATH)
                .putExtra(TERMUX_RUN_COMMAND_RUNNER, "app-shell")
                .putExtra(TERMUX_RUN_COMMAND_LABEL, label)
        requireNotNull(appContext.startService(intent)) {
            "Termux rejected the RUN_COMMAND service request"
        }
    }

    private fun repairJobCommand(jobId: String, body: String): String =
        """
        job="$jobId"
        job_dir="${'$'}HOME/.wuxianpi/jobs/${'$'}job"
        mkdir -p "${'$'}job_dir"
        (
          printf '{"jobId":"%s","status":"running"}\n' "${'$'}job" >"${'$'}job_dir/status.json"
          if (
        $body
          ); then
            printf '{"jobId":"%s","status":"completed"}\n' "${'$'}job" >"${'$'}job_dir/status.json"
          else
            code=${'$'}?
            printf '{"jobId":"%s","status":"failed","exitCode":%s}\n' "${'$'}job" "${'$'}code" >"${'$'}job_dir/status.json"
          fi
        ) >"${'$'}job_dir/output.log" 2>&1 &
        """.trimIndent()

    private fun termuxHomeTree(): DocumentFile? {
        val permission =
            appContext.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission && it.isWritePermission }
                .sortedByDescending { it.uri.authority?.contains("termux", ignoreCase = true) == true }
                .firstOrNull()
                ?: return null
        return DocumentFile.fromTreeUri(appContext, permission.uri)
    }

    private fun requireDirectory(root: DocumentFile, relativePath: String): DocumentFile {
        var current = root
        relativePath.split('/').filter(String::isNotBlank).forEach { name ->
            val existing = current.findFile(name)
            val next: DocumentFile =
                when {
                    existing == null ->
                        current.createDirectory(name)
                            ?: throw IllegalStateException(
                                "Unable to create SAF directory $relativePath"
                            )
                    existing.isDirectory -> existing
                    else ->
                        throw IllegalStateException(
                            "SAF path component is not a directory: $name"
                        )
                }
            current = next
        }
        return current
    }

    private fun findDocument(root: DocumentFile, relativePath: String): DocumentFile? {
        var current: DocumentFile? = root
        relativePath.split('/').filter(String::isNotBlank).forEach { name ->
            current = current?.findFile(name)
            if (current == null) return null
        }
        return current
    }

    private fun readDocumentText(document: DocumentFile, maxBytes: Int): String {
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
        requireNotNull(appContext.contentResolver.openInputStream(document.uri)) {
            "Unable to read ${document.uri}"
        }.use { input ->
            val buffer = ByteArray(4096)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun success(details: JSONObject): Completion =
        Completion(details.toString(), details, isError = false, error = null)

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
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val TERMUX_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val TERMUX_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val TERMUX_RUN_COMMAND_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val TERMUX_RUN_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val TERMUX_SHELL_PATH = "/data/data/com.termux/files/usr/bin/sh"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
        private const val DEFAULT_RUNTIME_HEALTH_URL = "http://127.0.0.1:8765/health"
        private const val PAYLOAD_ASSET_PATH = "openhouse-runtime/runtime-aarch64.tgz"
        private const val PAYLOAD_FILE_NAME = "runtime-aarch64.tgz"
        private const val PAYLOAD_MIME_TYPE = "application/gzip"
        private const val DEFAULT_DIAGNOSTIC_BYTES = 32 * 1024
        private const val MAX_DIAGNOSTIC_BYTES = 256 * 1024
        private const val MAX_HTTP_BODY_CHARS = 64 * 1024
        private val DIAGNOSTIC_PATHS =
            listOf(
                ".local/share/openhouseai/runtime/node.log",
                ".local/share/openhouseai/runtime/runtime.log",
                ".wuxianpi/bootstrap.log",
            )
    }
}
