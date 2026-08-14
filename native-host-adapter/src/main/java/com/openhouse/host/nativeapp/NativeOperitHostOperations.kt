package com.openhouse.host.nativeapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.setup.LoopbackInstallBundleServer
import com.ai.assistance.operit.host.setup.OpenHouseConnectionBridge
import com.ai.assistance.operit.host.setup.WuxianPiSetupContract
import com.ai.assistance.operit.host.setup.WuxianPiConnectionStore
import com.ai.assistance.operit.host.terminal.HostTerminalSessionBackend
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.tmux.TmuxHostTerminalBackend
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val NATIVE_WUXIANPI_SERVICE_ID = "yuanshengwuxianpi"

/** Operit repair bridge for the native APK and its bundled runtime pairing flow. */
class NativeOperitHostOperations(context: Context) : OperitHostOperations {
    private companion object {
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val SETUP_COMMAND = "$TERMUX_PREFIX/bin/wuxianpi-setup"
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
    @Volatile private var activeInstallBundleServer: LoopbackInstallBundleServer? = null
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
        details.put("termuxHomeAccess", false).put("termuxHomeRequired", false)
        return success(
            "inspect_wuxianpi_setup",
            details
                .put("wuxianPiSetupPath", SETUP_COMMAND)
                .put("runCommandVerificationRequired", true),
        )
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
            val readiness = runCatching {
                val readiness = runBlocking(Dispatchers.IO) {
                    termuxHomeRepository.registerAndProbe(uri)
                }
                mergeDetails(details.put("alreadyGranted", true), readiness)
            }.getOrElse { error ->
                details
                    .put("alreadyGranted", true)
                    .put("termuxHomeAccess", false)
                    .put("authorizationProbeError", error.message.orEmpty())
            }
            if (isTermuxHomeWorkspaceReady(readiness)) {
                return success("request_termux_home_access", readiness)
            }
            return termuxHomeAccessAction(
                readiness.put("existingAuthorizationInvalid", true),
                "Termux Home 授权已经失效或无法读写，请重新选择 Termux Home。",
            )
        }
        return termuxHomeAccessAction(details)
    }

    override fun launchTermuxHomeAccess(context: Context): OperitHostOperationResult =
        launchCoordinator(
            context,
            NativeTermuxHomeAccessActivity::class.java,
            "request_termux_home_access",
            JSONObject(),
        )

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
        if (context.checkSelfPermission(NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return success(
                "request_termux_run_command_permission",
                details.put("alreadyGranted", true).put("permissionGranted", true),
            )
        }
        return deferredAction(
            "request_termux_run_command_permission",
            details,
            stage = "termux_run_command",
            title = "允许调用 Termux 命令",
            description = "需要允许 OpenHouse 调用 Termux 执行安装和诊断命令。",
            button = "打开权限请求",
        )
    }

    override fun launchTermuxRunCommandPermission(context: Context): OperitHostOperationResult =
        launchCoordinator(
            context,
            NativeTermuxRunCommandPermissionActivity::class.java,
            "request_termux_run_command_permission",
            JSONObject(),
        )

    override suspend fun configureTermuxExternalApps(): OperitHostOperationResult {
        val details = JSONObject()
            .put("propertiesPath", "\$HOME/.termux/termux.properties")
            .put("commands", org.json.JSONArray().put("mkdir -p ~/.termux")
                .put("echo 'allow-external-apps = true' >> ~/.termux/termux.properties")
                .put("termux-reload-settings"))
        return deferredAction(
            operation = "configure_termux_external_apps",
            action = "reload_termux_settings",
            details = details,
            stage = "termux_reload",
            title = "在 Termux 启用外部命令",
            description = "请打开 Termux，依次执行卡片中的三行命令；完成后返回维修助手进行实际命令探针。",
            button = "打开 Termux",
        )
    }

    override suspend fun verifyTermuxRunCommand(): OperitHostOperationResult {
        val details = JSONObject().put("probe", "printf").put("expectedStdout", TERMUX_READY_MARKER)
        if (appContext.checkSelfPermission(NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return failure(
                "verify_termux_run_command",
                "Missing ${NativeTermuxRunCommandPermissionActivity.RUN_COMMAND_PERMISSION}",
                details.put("permissionGranted", false),
            )
        }
        return runCatching {
            val startedAt = System.currentTimeMillis()
            val response = runCommandTransport.execute(buildTermuxRunCommandProbe(), 15_000L)
            details.put("permissionGranted", true)
                .put("exitCode", response.exitCode)
                .put("stdout", response.stdout)
                .put("stderr", response.stderr)
                .put("timedOut", response.timedOut)
                .put("durationMs", System.currentTimeMillis() - startedAt)
            if (!response.timedOut && response.errorCode == TERMUX_SUCCESS_ERROR_CODE &&
                response.exitCode == 0 && response.stdout == TERMUX_READY_MARKER
            ) {
                success("verify_termux_run_command", details.put("verified", true))
            } else {
                failure(
                    "verify_termux_run_command",
                    response.errorMessage.ifBlank { response.stderr }.ifBlank {
                        "Termux RUN_COMMAND probe did not return the exact readiness marker"
                    },
                    details.put("verified", false),
                )
            }
        }.getOrElse {
            failure(
                "verify_termux_run_command",
                it.message ?: "Termux RUN_COMMAND verification failed",
                details.put("verified", false),
            )
        }
    }

    override suspend fun preparePersistentTermux(): OperitHostOperationResult = runCatching {
        commandResult(
            operation = "prepare_persistent_termux",
            command = "set -eu; export PREFIX='$TERMUX_PREFIX'; export PATH='\$PREFIX/bin:/system/bin'; " +
                "pkg update -y; pkg install -y tmux libncursesw; " +
                "tmux new-session -d -s wuxianpi-setup 'exec \$PREFIX/bin/bash -l' 2>/dev/null || true; " +
                "tmux has-session -t wuxianpi-setup",
            timeoutMs = 30 * 60_000L,
            details = JSONObject().put("phase", "pre-tmux").put("termuxHomeRequired", false),
        )
    }.getOrElse { failure("prepare_persistent_termux", it.message ?: "Unable to prepare tmux") }

    override suspend fun startWuxianPiSetup(): OperitHostOperationResult = runCatching {
        val bridge = OpenHouseConnectionBridge.ensureStarted(appContext, "setup")
        activeInstallBundleServer?.close()
        val server = LoopbackInstallBundleServer.start(appContext)
        activeInstallBundleServer = server
        val bundle = server.offer
        val command = buildWuxianPiSetupDownloadCommand(
            TERMUX_PREFIX,
            TERMUX_HOME,
            bundle,
            bridge.identity.bridgeId,
        )
        setupLaunchDetails(command)
                .put("asset", INSTALL_BUNDLE_ASSET)
                .put("offerId", bundle.offerId)
                .put("resourceSetVersion", bundle.resourceSetVersion)
                .put("resourceSetSequence", bundle.resourceSetSequence)
                .put("bundleSize", bundle.bundleSize)
                .put("bundleUrl", bundle.url)
                .put("termuxHomeRequired", false)
                .let { details ->
                    success("start_wuxianpi_setup", details)
                }
    }.getOrElse { failure("start_wuxianpi_setup", it.message ?: "Unable to open WuxianPi setup bundle") }

    override suspend fun wuxianPiSetupStatus(): OperitHostOperationResult =
        setupCommand("status", "wuxianpi_setup_status", 20_000L)

    override suspend fun storeServiceManagerConnection(): OperitHostOperationResult = withContext(Dispatchers.IO) {
        val saved = WuxianPiConnectionStore.get(appContext).load()
        if (!saved.isReady) return@withContext failure(
            "store_service_manager_connection",
            "No service-manager connection is saved in Android-private storage",
        )
        success(
            "store_service_manager_connection",
            JSONObject()
                .put("serviceManagerBaseUrl", saved.serviceManagerBaseUrl)
                .put("hasToken", true)
                .put("updatedAt", saved.updatedAt),
        )
    }

    override suspend fun ensureOpenHouseConnectionBridge(): OperitHostOperationResult =
        runCatching {
            val bridge = OpenHouseConnectionBridge.ensureStarted(appContext, "rescue-tool")
            success(
                "ensure_openhouse_connection_bridge",
                JSONObject()
                    .put("processName", bridge.identity.processName)
                    .put("bridgeId", bridge.identity.bridgeId)
                    .put("port", bridge.port)
                    .put("listening", true),
            )
        }.getOrElse { failure("ensure_openhouse_connection_bridge", it.message ?: "Unable to start Bridge") }

    override suspend fun writeServiceManagerConnection(
        serviceManagerBaseUrl: String,
        token: String,
    ): OperitHostOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            val store = WuxianPiConnectionStore.get(appContext)
            store.save(serviceManagerBaseUrl, token)
            val saved = store.load()
            success(
                "write_service_manager_connection",
                JSONObject()
                    .put("serviceManagerBaseUrl", saved.serviceManagerBaseUrl)
                    .put("port", java.net.URI(saved.serviceManagerBaseUrl).port)
                    .put("updatedAt", saved.updatedAt)
                    .put("hasToken", saved.isReady),
            )
        }.getOrElse { failure("write_service_manager_connection", it.message ?: "Unable to save service-manager connection") }
    }

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

    override suspend fun stageApkInstallBundle(): OperitHostOperationResult = runCatching {
        activeInstallBundleServer?.close()
        val staged = LoopbackInstallBundleServer.start(appContext).also { activeInstallBundleServer = it }.offer
        success(
            "stage_apk_install_bundle",
            JSONObject()
                .put("offerId", staged.offerId)
                .put("bundleSize", staged.bundleSize)
                .put("bundleUrl", staged.url),
        )
    }.getOrElse {
        failure(
            "stage_apk_install_bundle",
            it.message ?: "Unable to open APK install bundle",
        )
    }

    override suspend fun readRescueMemoryMirror(): OperitHostOperationResult = runCatching {
        val payload = termuxHomeRepository.readBytes(
            ".local/share/openhouseai/memory/memory-sync.json",
            128 * 1024,
        )
        success(
            "read_rescue_memory_mirror",
            JSONObject().put("payload", payload.toString(Charsets.UTF_8)),
        )
    }.getOrElse {
        failure("read_rescue_memory_mirror", it.message ?: "Unable to read Rescue memory mirror")
    }

    override suspend fun writeRescueMemoryMirror(payload: ByteArray): OperitHostOperationResult =
        runCatching {
            require(payload.size <= 128 * 1024) { "Rescue memory sync payload is too large" }
            val sync = JSONObject(payload.toString(Charsets.UTF_8))
            require(sync.optInt("schemaVersion") == 1) {
                "Unsupported Rescue memory sync schema"
            }
            val markdown = sync.getString("markdown").toByteArray(Charsets.UTF_8)
            val deviceState =
                (sync.optJSONObject("deviceState") ?: JSONObject().put("facts", org.json.JSONArray()))
                    .toString(2)
                    .toByteArray(Charsets.UTF_8)
            termuxHomeRepository.stageBytes(
                ".local/share/openhouseai/memory/memory-sync.json",
                payload,
                sha256(payload),
            )
            termuxHomeRepository.stageBytes(
                ".local/share/openhouseai/memory/assistant-memory.md",
                markdown,
                sha256(markdown),
            )
            termuxHomeRepository.stageBytes(
                ".local/share/openhouseai/memory/device-state.json",
                deviceState,
                sha256(deviceState),
            )
            success("write_rescue_memory_mirror", JSONObject())
        }.getOrElse {
            failure("write_rescue_memory_mirror", it.message ?: "Unable to save Rescue memory mirror")
        }

    private fun sha256(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

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
        val result = ServiceManagerClient(host.runtimeConnection()).runAction(NATIVE_WUXIANPI_SERVICE_ID, action)
        details.put("serviceId", NATIVE_WUXIANPI_SERVICE_ID).put("action", action.apiName()).put("code", result.code)
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
        return nativeSetupOperationResult(operation, subcommand, result, baseDetails)
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

    private fun termuxHomeAccessAction(
        details: JSONObject,
        description: String = "需要访问 Termux Home，用于修改 Termux 配置、投递安装包和读取安装结果。",
    ): OperitHostOperationResult =
        deferredAction(
            operation = "request_termux_home_access",
            details = details,
            stage = "termux_home",
            title = "授权 Termux Home",
            description = description,
            button = "打开文件授权",
        )

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
          --data '{"port":20765,"clientId":"operit-native"}' \
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

internal fun buildWuxianPiSetupLaunchCommand(
    prefix: String,
    home: String,
    resourceInbox: String,
    bundlePath: String,
    apkVersionCode: Long,
): String =
    "set -e; root='$home/.local/share/wuxianpi/install-resources/current'; " +
        "inbox='$resourceInbox'; bundle='$bundlePath'; " +
        "[ -f \"\$inbox/.ready\" ] || { printf '%s\\n' 'APK resource offer is not ready' >&2; exit 1; }; " +
        "[ -s \"\$bundle\" ] || { printf '%s\\n' 'APK install bundle is missing' >&2; exit 1; }; " +
        "rm -rf \"\$root\"; mkdir -p \"\$root\"; " +
        "'$prefix/bin/tar' -xf \"\$bundle\" -C \"\$root\"; " +
        "install -m 700 \"\$root/bootstrap/scripts/openhouse-resource-import\" '$prefix/bin/openhouse-resource-import'; " +
        "install -m 700 \"\$root/bootstrap/scripts/openhouse-resource-manager\" '$prefix/bin/openhouse-resource-manager'; " +
        "OPENHOUSEAI_APK_VERSION_CODE='$apkVersionCode' '$prefix/bin/bash' \"\$root/bootstrap/scripts/wuxianpi-setup\" install " +
        "--request '$home/$SETUP_REQUEST_HOME_PATH' " +
        "--resource-inbox \"\$inbox\""

/** Downloads the canonical TAR through localhost; Termux owns Inbox publication and import. */
internal fun buildWuxianPiSetupDownloadCommand(
    prefix: String,
    home: String,
    offer: LoopbackInstallBundleServer.Offer,
    connectionBridgeId: String,
): String {
    val inbox = "$home/.local/share/openhouseai/apk-resource-inbox/${offer.offerId}"
    return "set -eu; inbox='$inbox'; bundle=\"\$inbox/openhouse-install-bundle.tar\"; " +
        "temporary=\"\$inbox/openhouse-install-bundle.tar.incoming\"; root='$home/.local/share/wuxianpi/install-resources/current'; " +
        "mkdir -p \"\$inbox\"; rm -f \"\$temporary\"; " +
        "'$prefix/bin/curl' -fL --retry 2 --connect-timeout 10 --max-time 900 '${offer.url}' -o \"\$temporary\"; " +
        "[ -s \"\$temporary\" ]; mv -f \"\$temporary\" \"\$bundle\"; : > \"\$inbox/.ready\"; " +
        "rm -rf \"\$root\"; mkdir -p \"\$root\"; '$prefix/bin/tar' -xf \"\$bundle\" -C \"\$root\"; " +
        "install -m 700 \"\$root/bootstrap/scripts/openhouse-resource-import\" '$prefix/bin/openhouse-resource-import'; " +
        "install -m 700 \"\$root/bootstrap/scripts/openhouse-resource-manager\" '$prefix/bin/openhouse-resource-manager'; " +
        "OPENHOUSEAI_APK_VERSION_CODE='${offer.apkVersionCode}' '$prefix/bin/bash' \"\$root/bootstrap/scripts/wuxianpi-setup\" install " +
        "--resource-inbox \"\$inbox\" --offer-id '${offer.offerId}' " +
        "--connection-bridge-id '$connectionBridgeId'"
}

internal fun setupLaunchDetails(command: String): JSONObject =
    JSONObject()
        .put("command", command)
        .put("working_directory", "/data/data/com.termux/files/home")
        .put("session_name", "wuxianpi-setup")
        .put("yield_time_ms", 1000)
        .put("executorTool", "termux_exec_command")
        .put("persistent", true)
        .put("launchRequired", true)

/** Keeps external-Termux setup status structurally identical to the embedded host status. */
internal fun nativeSetupOperationResult(
    operation: String,
    action: String,
    result: OperitHostCommandResult,
    baseDetails: JSONObject = JSONObject(),
): OperitHostOperationResult {
    val details = baseDetails
        .put("operation", operation)
        .put("action", action)
        .put("exitCode", result.exitCode)
        .put("timedOut", result.timedOut)
        .put("durationMs", result.durationMs)
        .put("stdout", result.stdout.take(256 * 1024))
        .put("stderr", result.stderr.take(64 * 1024))
    result.stdout.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("{") && it.endsWith("}") }
        .lastOrNull()
        ?.let { json -> runCatching { details.put("status", JSONObject(json)) } }
    return if (result.isSuccess) {
        OperitHostOperationResult(true, details, operation, null)
    } else {
        val message = result.error.ifBlank {
            result.stderr.ifBlank { result.stdout.ifBlank { "$action failed" } }
        }
        OperitHostOperationResult(false, details, message, message)
    }
}

private fun mergeDetails(target: JSONObject, source: JSONObject): JSONObject {
    source.keys().forEach { key -> target.put(key, source.get(key)) }
    return target
}

internal fun launchedUserActionDetails(details: JSONObject): JSONObject =
    details
        .put("launched", true)
        .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)

private fun deferredAction(
    operation: String,
    details: JSONObject,
    stage: String,
    title: String,
    description: String,
    button: String,
    action: String = operation,
): OperitHostOperationResult =
    OperitHostOperationResult(
        success = false,
        details = details
            .put("operation", operation)
            .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
            .put(WuxianPiSetupContract.DETAIL_DEFERRED_USER_ACTION, action)
            .put(WuxianPiSetupContract.DETAIL_ACTION_STAGE, stage)
            .put(WuxianPiSetupContract.DETAIL_ACTION_TITLE, title)
            .put(WuxianPiSetupContract.DETAIL_ACTION_DESCRIPTION, description)
            .put(WuxianPiSetupContract.DETAIL_ACTION_BUTTON, button),
        message = description,
    )
