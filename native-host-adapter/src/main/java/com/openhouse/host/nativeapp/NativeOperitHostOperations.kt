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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Operit repair bridge for the native APK and its bundled runtime pairing flow. */
class NativeOperitHostOperations(context: Context) : OperitHostOperations {
    private companion object {
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val SETUP_COMMAND = "$TERMUX_PREFIX/bin/wuxianpi-setup"
        const val SERVICE_MANAGER_COMMAND = "$TERMUX_PREFIX/bin/service-manager"
    }

    private val appContext = context.applicationContext
    private val host = NativeOpenHouseHost(appContext)
    private val runCommandTransport = NativeTermuxRunCommandTransport(appContext)
    private val androidShellExecutor = AndroidShellCommandExecutor(
        workingDirectory = appContext.filesDir,
    )
    private val externalTermuxExecutor = ExternalTermuxCommandExecutor(
        runCommandTransport,
    )
    private val termuxHomeRepository = NativeTermuxHomeRepository(appContext)
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
            .put(
                "runCommandPermission",
                appContext.checkSelfPermission(NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        termuxHomeRepository.persistedTreeUri()?.let {
            runCatching { termuxHomeRepository.registerAndProbe(it) }
                .getOrNull()?.let { readiness -> mergeDetails(details, readiness) }
        } ?: details.put("termuxHomeAccess", false)
        if (!canUseTermuxRunCommand(probe) ||
            appContext.checkSelfPermission(NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return success("inspect_wuxianpi_setup", details)
        }
        val binaryProbe = externalTermuxExecutor.execute(
            "test -x '$SETUP_COMMAND' && echo setup=1 || echo setup=0; " +
                "test -x '$SERVICE_MANAGER_COMMAND' && echo service_manager=1 || echo service_manager=0; " +
                "test -x '$TERMUX_PREFIX/bin/tmux' && echo tmux=1 || echo tmux=0",
            ExternalTermuxCommandTarget.TERMUX,
            10_000L,
        )
        details.put("binaryProbe", binaryProbe.stdout)
            .put("wuxianPiSetupPath", SETUP_COMMAND)
            .put("serviceManagerPath", SERVICE_MANAGER_COMMAND)
        return if (binaryProbe.stdout.contains("setup=1")) {
            setupCommand("inspect", "inspect_wuxianpi_setup", 20_000L, details)
        } else {
            success("inspect_wuxianpi_setup", details.put("setupInstallerReady", false))
        }
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
        termuxHomeRepository.persistedTreeUri()?.let { uri ->
            return runCatching {
                val readiness = runBlocking(Dispatchers.IO) {
                    termuxHomeRepository.registerAndProbe(uri)
                }
                val completedDetails = mergeDetails(details.put("alreadyGranted", true), readiness)
                if (isTermuxHomeWorkspaceReady(readiness)) {
                    success("request_termux_home_access", completedDetails)
                } else {
                    failure(
                        "request_termux_home_access",
                        "Termux Home bookmark or read/write probe is not ready",
                        completedDetails,
                    )
                }
            }.getOrElse { failure("request_termux_home_access", it.message.orEmpty(), details) }
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

    override suspend fun preparePersistentTermux(): OperitHostOperationResult = runCatching {
        val bytes = termuxHomeRepository.stageAsset(PRE_TMUX_ASSET, PRE_TMUX_HOME_PATH)
        commandResult(
            operation = "prepare_persistent_termux",
            command = "$TERMUX_PREFIX/bin/bash '$TERMUX_HOME/$PRE_TMUX_HOME_PATH' --region auto",
            timeoutMs = 10 * 60_000L,
            details = JSONObject().put("asset", PRE_TMUX_ASSET).put("stagedBytes", bytes),
        )
    }.getOrElse { failure("prepare_persistent_termux", it.message ?: "Unable to stage pre-tmux setup") }

    override suspend fun startWuxianPiSetup(): OperitHostOperationResult = runCatching {
        val bytes = termuxHomeRepository.stageAsset(SETUP_RESOURCES_ASSET, SETUP_RESOURCES_HOME_PATH)
        val request = JSONObject()
            .put("version", 1)
            .put("region", "auto")
            .put("resourcesArchive", "$TERMUX_HOME/$SETUP_RESOURCES_HOME_PATH")
        termuxHomeRepository.writeText(SETUP_REQUEST_HOME_PATH, request.toString(2))
        val command = buildWuxianPiSetupLaunchCommand(TERMUX_PREFIX, TERMUX_HOME)
        success(
            "start_wuxianpi_setup",
            setupLaunchDetails(command)
                .put("asset", SETUP_RESOURCES_ASSET)
                .put("stagedBytes", bytes)
                .put("request", "$TERMUX_HOME/$SETUP_REQUEST_HOME_PATH"),
        )
    }.getOrElse { failure("start_wuxianpi_setup", it.message ?: "Unable to stage WuxianPi setup resources") }

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
        baseDetails.putHostProbe(probe).put("setupCommand", "$SETUP_COMMAND $subcommand")
        if (!canUseTermuxRunCommand(probe)) {
            val message = if (probe.state == NativeExternalHostState.ABSENT) {
                "Termux package com.termux is not installed"
            } else {
                "Installed Termux does not expose $TERMUX_RUN_COMMAND_ACTION"
            }
            return failure(operation, message, baseDetails)
        }
        val result = externalTermuxExecutor.execute(
            "$SETUP_COMMAND $subcommand",
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
            "$SETUP_COMMAND $subcommand failed with exit code ${result.exitCode}"
        }, baseDetails)
    }

    private suspend fun commandResult(
        operation: String,
        command: String,
        timeoutMs: Long,
        details: JSONObject,
    ): OperitHostOperationResult {
        val result = externalTermuxExecutor.execute(command, ExternalTermuxCommandTarget.TERMUX, timeoutMs)
        details.put("command", command).put("exitCode", result.exitCode)
            .put("stdout", result.stdout).put("stderr", result.stderr).put("timedOut", result.timedOut)
        return if (result.isSuccess) success(operation, details)
        else failure(operation, result.error.ifBlank { result.stderr }, details)
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

internal fun buildWuxianPiSetupLaunchCommand(prefix: String, home: String): String =
    "set -e; root='$home/.local/share/wuxianpi/install-resources/current'; " +
        "rm -rf \"\$root\"; mkdir -p \"\$root\"; " +
        "'$prefix/bin/tar' -xf '$home/$SETUP_RESOURCES_HOME_PATH' -C \"\$root\"; " +
        "'$prefix/bin/bash' \"\$root/bootstrap/wuxianpi-setup\" install " +
        "--request '$home/$SETUP_REQUEST_HOME_PATH'"

internal fun setupLaunchDetails(command: String): JSONObject =
    JSONObject()
        .put("command", command)
        .put("working_directory", "/data/data/com.termux/files/home")
        .put("session_name", "wuxianpi-setup")
        .put("yield_time_ms", 1000)
        .put("executorTool", "termux_exec_command")
        .put("persistent", true)
        .put("launchRequired", true)

private fun mergeDetails(target: JSONObject, source: JSONObject): JSONObject {
    source.keys().forEach { key -> target.put(key, source.get(key)) }
    return target
}

internal fun launchedUserActionDetails(details: JSONObject): JSONObject =
    details
        .put("launched", true)
        .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
