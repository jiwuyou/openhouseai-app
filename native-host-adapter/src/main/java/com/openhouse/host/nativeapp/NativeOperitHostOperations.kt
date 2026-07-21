package com.openhouse.host.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Operit repair bridge for the native APK and its bundled runtime pairing flow. */
class NativeOperitHostOperations(context: Context) : OperitHostOperations {
    private val appContext = context.applicationContext
    private val host = NativeOpenHouseHost(appContext)

    override suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget,
        timeoutMs: Long,
    ): OperitHostCommandResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val cleanCommand = command.trim()
        if (cleanCommand.isEmpty()) {
            return@withContext commandResult(command, 2, "command is empty", startedAt)
        }
        if (target == HostTerminalTarget.UBUNTU) {
            return@withContext commandResult(command, 127, "Ubuntu target is unavailable in native APK", startedAt)
        }
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", cleanCommand)
                .directory(appContext.filesDir)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@withContext commandResult(command, 124, "command timed out", startedAt, timedOut = true)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            commandResult(command, process.exitValue(), output, startedAt)
        }.getOrElse { commandResult(command, 1, it.message ?: "command failed", startedAt) }
    }

    override fun openPermissions(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    override fun openHostApp(context: Context): OperitHostOperationResult =
        host.openTerminal().toOperationResult("open_terminal")

    override fun pairingInstallerScript(baseUrl: String, token: String): String =
        pairingScript(baseUrl, token)

    override suspend fun runtimeStatus(): OperitHostOperationResult =
        request("${NativeOpenHouseHost.DEFAULT_PI_RUNTIME_URL}/health", "runtime_status")

    override suspend fun readRuntimeDiagnostics(maxBytes: Int): OperitHostOperationResult =
        request("${NativeOpenHouseHost.DEFAULT_PI_RUNTIME_URL}/health", "read_diagnostics", maxBytes)

    override suspend fun restartRuntime(): OperitHostOperationResult =
        serviceAction(ServiceAction.RESTART, "restart_runtime")

    override suspend fun redeployRuntime(
        payload: ByteArray,
        fileName: String,
        mimeType: String,
    ): OperitHostOperationResult = serviceAction(
        ServiceAction.REPAIR,
        "redeploy_runtime",
        JSONObject().put("payloadBytes", payload.size).put("fileName", fileName).put("mimeType", mimeType)
            .put("flow", "native-pairing-runtime"),
    )

    override suspend fun repairJobStatus(jobId: String): OperitHostOperationResult {
        val id = jobId.trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) }
            ?: return failure("repair_job_status", "invalid repair job id")
        return request(
            "${NativeOpenHouseHost.DEFAULT_PI_RUNTIME_URL}/v1/repair/jobs/$id",
            "repair_job_status",
        )
    }

    override suspend fun exportDiagnostics(report: String): OperitHostOperationResult = runCatching {
        val output = File(appContext.cacheDir, "operit-native-diagnostics-${System.currentTimeMillis()}.json")
        output.writeText(report.take(256 * 1024))
        success("export_logs", JSONObject().put("path", output.absolutePath))
    }.getOrElse { failure("export_logs", it.message ?: "unable to write diagnostics") }

    private fun serviceAction(
        action: ServiceAction,
        operation: String,
        details: JSONObject = JSONObject(),
    ): OperitHostOperationResult {
        val result = ServiceManagerClient(host.runtimeConnection()).runAction("pi-agent", action)
        details.put("serviceId", "pi-agent").put("action", action.apiName()).put("code", result.code)
        details.put("body", result.body)
        return if (result.success) success(operation, details)
        else failure(operation, result.message.ifBlank { "service-manager action failed" }, details)
    }

    private fun request(url: String, operation: String, maxBytes: Int = 64 * 1024): OperitHostOperationResult {
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2500
            connection.readTimeout = 7000
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(maxBytes.coerceIn(1024, 256 * 1024)) }.orEmpty()
            connection.disconnect()
            val details = JSONObject().put("url", url).put("code", code).put("body", body)
            if (code in 200..299) success(operation, details)
            else failure(operation, "runtime request failed: HTTP $code", details)
        }.getOrElse { failure(operation, it.message ?: "runtime request failed") }
    }

    private fun pairingScript(baseUrl: String, token: String): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -euo pipefail
        BASE='${baseUrl.trimEnd('/')}'
        PAIR='$token'
        TMP="${'$'}(mktemp -d)"
        trap 'rm -rf "${'$'}TMP"' EXIT
        curl -fsSL "${'$'}BASE/payload/${'$'}PAIR/runtime-aarch64.tgz" -o "${'$'}TMP/runtime-aarch64.tgz"
        tar -xzf "${'$'}TMP/runtime-aarch64.tgz" -C "${'$'}TMP"
        [ ! -x "${'$'}TMP/install.sh" ] || "${'$'}TMP/install.sh"
        curl -fsSL -X POST -H 'Content-Type: application/json' \
          --data '{"port":8765,"clientId":"operit-native"}' \
          "${'$'}BASE/paired/${'$'}PAIR"
    """.trimIndent()

    private fun commandResult(
        command: String,
        exitCode: Int,
        output: String,
        startedAt: Long,
        timedOut: Boolean = false,
    ) = OperitHostCommandResult(
        command = command,
        exitCode = exitCode,
        stdout = output,
        stderr = "",
        error = if (exitCode == 0 && !timedOut) "" else output,
        timedOut = timedOut,
        durationMs = System.currentTimeMillis() - startedAt,
    )

    private fun success(operation: String, details: JSONObject) =
        OperitHostOperationResult(true, details.put("operation", operation), operation, null)

    private fun failure(operation: String, message: String, details: JSONObject = JSONObject()) =
        OperitHostOperationResult(false, details.put("operation", operation), message, message)

    private fun com.wuxianpi.openhouse.core.HostActionResult.toOperationResult(operation: String) =
        if (isSuccess()) success(operation, JSONObject().put("message", message))
        else failure(operation, message)
}
