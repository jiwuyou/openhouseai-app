package com.openhouse.host.nativeapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.setup.WuxianPiSetupContract
import com.ai.assistance.operit.host.terminal.HostTerminalSessionBackend
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.tmux.TmuxHostTerminalBackend
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Operit repair bridge for the native APK and its bundled runtime pairing flow. */
class NativeOperitHostOperations(context: Context) : OperitHostOperations {
    private val appContext = context.applicationContext
    private val host = NativeOpenHouseHost(appContext)
    private val runCommandTransport = NativeTermuxRunCommandTransport(appContext)
    private val androidShellExecutor = AndroidShellCommandExecutor(
        workingDirectory = appContext.filesDir,
    )
    private val externalTermuxExecutor = ExternalTermuxCommandExecutor(
        runCommandTransport,
    )
    override val terminalSessionBackend: HostTerminalSessionBackend =
        TmuxHostTerminalBackend(NativeTermuxSessionTransport(runCommandTransport))

    override suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget,
        timeoutMs: Long,
    ): OperitHostCommandResult = withContext(Dispatchers.IO) {
        when (target) {
            HostTerminalTarget.ANDROID -> androidShellExecutor.execute(command, timeoutMs)
            HostTerminalTarget.TERMUX -> externalTermuxExecutor.execute(
                command,
                ExternalTermuxCommandTarget.TERMUX,
                timeoutMs,
            )
            HostTerminalTarget.UBUNTU -> externalTermuxExecutor.execute(
                command,
                ExternalTermuxCommandTarget.UBUNTU,
                timeoutMs,
            )
        }
    }

    override fun openPermissions(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    override fun openHostApp(context: Context): OperitHostOperationResult =
        host.openTerminal().toOperationResult("open_terminal")

    override suspend fun inspectWuxianPiSetup(): OperitHostOperationResult {
        val probe = NativeExternalHostInspector.inspect(appContext)
        val details = JSONObject().putHostProbe(probe)
            .put("termuxHomeAccess", hasTermuxHomeAccess())
            .put(
                "runCommandPermission",
                appContext.checkSelfPermission(NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        if (!canUseTermuxRunCommand(probe) ||
            appContext.checkSelfPermission(NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return success("inspect_wuxianpi_setup", details)
        }
        return setupCommand("inspect", "inspect_wuxianpi_setup", 20_000L, details)
    }

    override fun prepareRuntimeHost(context: Context): OperitHostOperationResult {
        val probe = NativeExternalHostInspector.inspect(context)
        val details = JSONObject().putHostProbe(probe)
        return when (probe.state) {
            NativeExternalHostState.ALL_IN_ONE -> runCatching {
                check(NativeExternalHostInspector.launchPreparation(context, probe)) {
                    "WuxianPi All-in-One does not expose $WUXIANPI_PREPARE_HOST_ACTION"
                }
                success(
                    "prepare_runtime_host",
                    launchedUserActionDetails(details),
                )
            }.getOrElse { failure("prepare_runtime_host", it.message ?: "Unable to prepare host", details) }
            NativeExternalHostState.ABSENT -> launchCoordinator(
                context,
                NativeRuntimeHostPreparationActivity::class.java,
                "prepare_runtime_host",
                details.put("downloadRequired", true),
            )
            NativeExternalHostState.EXTERNAL_TERMUX ->
                success("prepare_runtime_host", details.put("externalTermux", true))
        }
    }

    override fun requestTermuxHomeAccess(context: Context): OperitHostOperationResult {
        val probe = NativeExternalHostInspector.inspect(context)
        val details = JSONObject().putHostProbe(probe)
        if (!canRequestTermuxHomeAccess(probe)) {
            val message = if (probe.state == NativeExternalHostState.ABSENT) {
                "Termux package com.termux is not installed"
            } else {
                "Installed Termux does not expose $TERMUX_DOCUMENTS_AUTHORITY"
            }
            return failure("request_termux_home_access", message, details)
        }
        if (hasTermuxHomeAccess()) {
            return success("request_termux_home_access", details.put("alreadyGranted", true))
        }
        return launchCoordinator(
            context,
            NativeTermuxHomeAccessActivity::class.java,
            "request_termux_home_access",
            details,
        )
    }

    override fun requestTermuxRunCommandPermission(context: Context): OperitHostOperationResult {
        val probe = NativeExternalHostInspector.inspect(context)
        val details = JSONObject().putHostProbe(probe)
        if (!canUseTermuxRunCommand(probe)) {
            val message = if (probe.state == NativeExternalHostState.ABSENT) {
                "Termux package com.termux is not installed"
            } else {
                "Installed Termux does not expose $TERMUX_RUN_COMMAND_ACTION"
            }
            return failure("request_termux_run_command_permission", message, details)
        }
        return launchCoordinator(
            context,
            NativeTermuxRunCommandPermissionActivity::class.java,
            "request_termux_run_command_permission",
            details,
        )
    }

    override suspend fun preparePersistentTermux(): OperitHostOperationResult =
        setupCommand("prepare-tmux", "prepare_persistent_termux", 10 * 60_000L)

    override suspend fun startWuxianPiSetup(): OperitHostOperationResult =
        setupCommand("install", "start_wuxianpi_setup", 60_000L)

    override suspend fun wuxianPiSetupStatus(): OperitHostOperationResult =
        setupCommand("status", "wuxianpi_setup_status", 20_000L)

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

    private suspend fun setupCommand(
        subcommand: String,
        operation: String,
        timeoutMs: Long,
        baseDetails: JSONObject = JSONObject(),
    ): OperitHostOperationResult {
        val probe = NativeExternalHostInspector.inspect(appContext)
        baseDetails.putHostProbe(probe).put("setupCommand", "wuxianpi-setup $subcommand")
        if (!canUseTermuxRunCommand(probe)) {
            val message = if (probe.state == NativeExternalHostState.ABSENT) {
                "Termux package com.termux is not installed"
            } else {
                "Installed Termux does not expose $TERMUX_RUN_COMMAND_ACTION"
            }
            return failure(operation, message, baseDetails)
        }
        val result = externalTermuxExecutor.execute(
            "wuxianpi-setup $subcommand",
            ExternalTermuxCommandTarget.TERMUX,
            timeoutMs,
        )
        baseDetails.put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("timedOut", result.timedOut)
            .put("durationMs", result.durationMs)
        return if (result.isSuccess) success(operation, baseDetails)
        else failure(operation, result.error.ifBlank { result.stderr }.ifBlank {
            "wuxianpi-setup $subcommand failed with exit code ${result.exitCode}"
        }, baseDetails)
    }

    private fun hasTermuxHomeAccess(): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && isValidatedTermuxHomeTree(permission.uri)
        }

    private fun launchCoordinator(
        context: Context,
        activityClass: Class<*>,
        operation: String,
        details: JSONObject,
    ): OperitHostOperationResult = runCatching {
        context.startActivity(Intent(context, activityClass).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        success(
            operation,
            launchedUserActionDetails(details),
        )
    }.getOrElse { failure(operation, it.message ?: "Unable to launch $operation", details) }

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

    private fun success(operation: String, details: JSONObject) =
        OperitHostOperationResult(true, details.put("operation", operation), operation, null)

    private fun failure(operation: String, message: String, details: JSONObject = JSONObject()) =
        OperitHostOperationResult(false, details.put("operation", operation), message, message)

    private fun com.wuxianpi.openhouse.core.HostActionResult.toOperationResult(operation: String) =
        if (isSuccess()) success(operation, JSONObject().put("message", message))
        else failure(operation, message)
}

internal fun launchedUserActionDetails(details: JSONObject): JSONObject =
    details
        .put("launched", true)
        .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
