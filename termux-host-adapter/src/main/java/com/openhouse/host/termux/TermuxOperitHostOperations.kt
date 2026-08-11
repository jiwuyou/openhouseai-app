package com.openhouse.host.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.terminal.HostTerminalSessionBackend
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.terminal.tmux.TmuxHostTerminalBackend
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Operit repair bridge for the Termux-embedded APK. */
class TermuxOperitHostOperations(context: Context) : OperitHostOperations {
    private companion object {
        const val PREPARE_HOST_ACTION = "com.termux.WUXIANPI_PREPARE_HOST"
        const val SETUP_COMMAND = "/data/data/com.termux/files/usr/bin/wuxianpi-setup"
        const val INSTALL_BUNDLE_ASSET = "wuxianpi-install/openhouse-install-bundle.tar"
        const val INSTALL_BUNDLE_METADATA_ASSET = "wuxianpi-install/openhouse-install-bundle.json"
        const val INSTALL_BUNDLE_NAME = "openhouse-install-bundle.tar"
    }

    private val appContext = context.applicationContext
    private val host = TermuxOpenHouseHost(appContext)
    private val runtimeLayout = TermuxRuntimeLayout.defaults()
    private val androidShellExecutor = TermuxAndroidShellCommandExecutor(
        workingDirectory = appContext.filesDir,
    )
    private val commandExecutor = TermuxHostCommandExecutor(
        runtimeLayout,
        host.runtimeConnection().serviceManagerBaseUrl,
    )
    override val terminalSessionBackend: HostTerminalSessionBackend =
        TmuxHostTerminalBackend(EmbeddedTermuxSessionTransport(runtimeLayout))

    override suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget,
        timeoutMs: Long,
    ): OperitHostCommandResult = withContext(Dispatchers.IO) {
        when (target) {
            HostTerminalTarget.ANDROID -> androidShellExecutor.execute(command, timeoutMs)
            HostTerminalTarget.TERMUX,
            HostTerminalTarget.UBUNTU -> commandExecutor.execute(command, target, timeoutMs)
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

    override fun pairingInstallerScript(baseUrl: String, token: String): String =
        pairingScript(baseUrl, token)

    override suspend fun runtimeStatus(): OperitHostOperationResult =
        request("${TermuxOpenHouseHost.DEFAULT_PI_RUNTIME_URL}/health", "runtime_status")

    override suspend fun readRuntimeDiagnostics(maxBytes: Int): OperitHostOperationResult =
        request("${TermuxOpenHouseHost.DEFAULT_PI_RUNTIME_URL}/health", "read_diagnostics", maxBytes)

    override suspend fun restartRuntime(): OperitHostOperationResult =
        serviceAction(ServiceAction.RESTART, "restart_runtime")

    override suspend fun redeployRuntime(
        payload: ByteArray,
        fileName: String,
        mimeType: String,
    ): OperitHostOperationResult = serviceAction(
        ServiceAction.REPAIR,
        "redeploy_runtime",
        JSONObject().put("payloadBytes", payload.size).put("fileName", fileName).put("mimeType", mimeType),
    )

    override suspend fun stageApkInstallBundle(): OperitHostOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            val staged = stageInstallBundle()
            OperitHostOperationResult(
                success = true,
                details = JSONObject(staged.toString()).put("operation", "stage_apk_install_bundle"),
                message = "APK install bundle staged",
            )
        }.getOrElse {
            OperitHostOperationResult(
                success = false,
                details = JSONObject().put("operation", "stage_apk_install_bundle"),
                message = "Unable to stage APK install bundle",
                error = it.message,
            )
        }
    }

    override suspend fun readRescueMemoryMirror(): OperitHostOperationResult =
        withContext(Dispatchers.IO) {
            val file = File(appContext.filesDir, "home/.local/share/openhouseai/memory/memory-sync.json")
            if (!file.isFile) {
                OperitHostOperationResult(
                    success = false,
                    details = JSONObject().put("operation", "read_rescue_memory_mirror"),
                    message = "Rescue memory mirror does not exist yet",
                )
            } else {
                OperitHostOperationResult(
                    success = true,
                    details =
                        JSONObject()
                            .put("operation", "read_rescue_memory_mirror")
                            .put("payload", file.readText()),
                    message = "Rescue memory mirror loaded",
                )
            }
        }

    override suspend fun writeRescueMemoryMirror(payload: ByteArray): OperitHostOperationResult =
        withContext(Dispatchers.IO) {
            runCatching {
                require(payload.size <= 128 * 1024) { "Rescue memory sync payload is too large" }
                val sync = JSONObject(payload.toString(Charsets.UTF_8))
                require(sync.optInt("schemaVersion") == 1) {
                    "Unsupported Rescue memory sync schema"
                }
                val markdown = sync.getString("markdown")
                val deviceState = sync.optJSONObject("deviceState") ?: JSONObject().put("facts", org.json.JSONArray())
                val directory = File(appContext.filesDir, "home/.local/share/openhouseai/memory")
                check(directory.mkdirs() || directory.isDirectory) { "Unable to create memory directory" }
                writeMemoryMirrorFile(directory, "memory-sync.json", payload)
                writeMemoryMirrorFile(
                    directory,
                    "assistant-memory.md",
                    markdown.toByteArray(Charsets.UTF_8),
                )
                writeMemoryMirrorFile(
                    directory,
                    "device-state.json",
                    deviceState.toString(2).toByteArray(Charsets.UTF_8),
                )
                OperitHostOperationResult(
                    success = true,
                    details = JSONObject().put("operation", "write_rescue_memory_mirror"),
                    message = "Rescue memory mirror saved",
                )
            }.getOrElse {
                OperitHostOperationResult(
                    success = false,
                    details = JSONObject().put("operation", "write_rescue_memory_mirror"),
                    message = "Unable to save Rescue memory mirror",
                    error = it.message,
                )
            }
        }

    private fun writeMemoryMirrorFile(directory: File, name: String, payload: ByteArray) {
        val target = File(directory, name)
        val temporary = File(directory, ".$name-${System.nanoTime()}.tmp")
        try {
            temporary.writeBytes(payload)
            check(temporary.renameTo(target)) { "Unable to activate Rescue memory file: $name" }
        } finally {
            temporary.delete()
        }
    }

    override suspend fun repairJobStatus(jobId: String): OperitHostOperationResult {
        val id = jobId.trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) }
            ?: return failure("repair_job_status", "invalid repair job id")
        return request(
            "${TermuxOpenHouseHost.DEFAULT_PI_RUNTIME_URL}/v1/repair/jobs/$id",
            "repair_job_status",
        )
    }

    override suspend fun exportDiagnostics(report: String): OperitHostOperationResult = runCatching {
        val output = File(appContext.cacheDir, "operit-termux-diagnostics-${System.currentTimeMillis()}.json")
        output.writeText(report.take(256 * 1024))
        success("export_logs", JSONObject().put("path", output.absolutePath))
    }.getOrElse { failure("export_logs", it.message ?: "unable to write diagnostics") }

    override suspend fun inspectWuxianPiSetup(): OperitHostOperationResult =
        setupCommand("inspect_wuxianpi_setup", "inspect", 15_000L)

    override fun prepareRuntimeHost(context: Context): OperitHostOperationResult = runCatching {
        context.startActivity(
            Intent(PREPARE_HOST_ACTION)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        success(
            "prepare_runtime_host",
            JSONObject()
                .put("action", PREPARE_HOST_ACTION)
                .put("host", "embedded-termux")
                .put("launched", true),
        )
    }.getOrElse { failure("prepare_runtime_host", it.message ?: "unable to launch host preparation") }

    override fun requestTermuxHomeAccess(context: Context): OperitHostOperationResult =
        embeddedPermissionNotRequired("request_termux_home_access", "Termux Home is directly accessible")

    override fun requestTermuxRunCommandPermission(context: Context): OperitHostOperationResult =
        embeddedPermissionNotRequired(
            "request_termux_run_command_permission",
            "RUN_COMMAND permission is not required inside the embedded Termux host",
        )

    override suspend fun preparePersistentTermux(): OperitHostOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            val staged = stageInstallBundle()
            val root = File(runtimeLayout.home, ".local/share/wuxianpi/install-resources/current")
            val bootstrapCommand =
                "set -e; rm -rf '${root.absolutePath}'; mkdir -p '${root.absolutePath}'; " +
                    "'${runtimeLayout.prefix}/bin/tar' -xf '${staged.getString("bundlePath")}' -C '${root.absolutePath}'; " +
                    "install -m 700 '${root.absolutePath}/bootstrap/scripts/wuxianpi-setup' '$SETUP_COMMAND'; " +
                    "install -m 700 '${root.absolutePath}/bootstrap/scripts/wuxianpi-pre-tmux.sh' '${runtimeLayout.prefix}/bin/wuxianpi-pre-tmux.sh'"
            val bootstrap = commandExecutor.execute(bootstrapCommand, HostTerminalTarget.TERMUX, 120_000L)
            check(bootstrap.isSuccess) { bootstrap.stderr.ifBlank { bootstrap.stdout.ifBlank { "Unable to install setup bootstrap" } } }
            setupCommand("prepare_persistent_termux", "prepare-tmux", 30 * 60_000L)
        }.getOrElse { failure("prepare_persistent_termux", it.message ?: "Unable to prepare persistent Termux") }
    }

    override suspend fun startWuxianPiSetup(): OperitHostOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            val staged = stageInstallBundle()
            val inbox = staged.getString("inboxDirectory")
            val command =
                "OPENHOUSEAI_APK_VERSION_CODE='${staged.getLong("apkVersionCode")}' " +
                    "'$SETUP_COMMAND' install --resource-inbox '$inbox'"
            success(
                "start_wuxianpi_setup",
                JSONObject(staged.toString())
                    .put("command", command)
                    .put("working_directory", runtimeLayout.home.absolutePath)
                    .put("session_name", "wuxianpi-setup")
                    .put("yield_time_ms", 1000)
                    .put("executorTool", "termux_exec_command")
                    .put("persistent", true)
                    .put("launchRequired", true),
            )
        }.getOrElse { failure("start_wuxianpi_setup", it.message ?: "Unable to stage APK install bundle") }
    }

    override suspend fun wuxianPiSetupStatus(): OperitHostOperationResult =
        setupCommand("wuxianpi_setup_status", "status", 15_000L)

    private suspend fun setupCommand(
        operation: String,
        action: String,
        timeoutMs: Long,
    ): OperitHostOperationResult = withContext(Dispatchers.IO) {
        val result = commandExecutor.execute(
            "$SETUP_COMMAND $action",
            HostTerminalTarget.TERMUX,
            timeoutMs,
        )
        setupOperationResult(operation, action, result)
    }

    private fun stageInstallBundle(): JSONObject {
        val metadata = appContext.assets.open(INSTALL_BUNDLE_METADATA_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }
        require(metadata.optInt("schema") == 1 && metadata.optString("bundleAsset") == INSTALL_BUNDLE_ASSET) {
            "Invalid APK install bundle metadata"
        }
        val expectedSha = metadata.getString("bundleSha256").lowercase()
        val expectedSize = metadata.getLong("bundleSize")
        require(expectedSha.matches(Regex("[a-f0-9]{64}")) && expectedSize > 0L) {
            "Invalid APK install bundle integrity metadata"
        }
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val offerId = sha256("${appContext.packageName}:$versionCode:${packageInfo.lastUpdateTime}".toByteArray()).take(24)
        val inbox = File(runtimeLayout.home, ".local/share/openhouseai/apk-resource-inbox/$offerId")
        check(inbox.mkdirs() || inbox.isDirectory) { "Unable to create APK resource inbox" }
        File(inbox, ".ready").delete()
        val bundle = File(inbox, INSTALL_BUNDLE_NAME)
        var copiedBytes = 0L
        val reusable = bundle.isFile && bundle.length() == expectedSize && sha256(bundle) == expectedSha
        if (!reusable) {
            bundle.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            appContext.assets.open(INSTALL_BUNDLE_ASSET).use { input ->
                bundle.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copiedBytes += read
                        }
                    }
                }
            }
            check(copiedBytes == expectedSize && digest.digest().toHex() == expectedSha) {
                bundle.delete()
                "APK install bundle integrity verification failed"
            }
        }
        check(bundle.length() == expectedSize && sha256(bundle) == expectedSha) {
            bundle.delete()
            "APK install bundle changed after staging"
        }
        val offer = JSONObject()
            .put("schema", 1)
            .put("offerId", offerId)
            .put("apkVersionName", packageInfo.versionName.orEmpty())
            .put("apkVersionCode", versionCode)
            .put("bundleFile", INSTALL_BUNDLE_NAME)
            .put("bundleSha256", expectedSha)
            .put("bundleSize", expectedSize)
            .put("resourceSet", JSONObject(metadata.getJSONObject("resourceSet").toString()))
        File(inbox, "offer.json").writeText(offer.toString(2) + "\n")
        check(File(inbox, ".ready").createNewFile()) { "Unable to publish APK resource ready marker" }
        return JSONObject(offer.toString())
            .put("operation", "stage_apk_install_bundle")
            .put("asset", INSTALL_BUNDLE_ASSET)
            .put("inboxDirectory", inbox.absolutePath)
            .put("bundlePath", bundle.absolutePath)
            .put("stagedBytes", copiedBytes)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun embeddedPermissionNotRequired(operation: String, reason: String) = success(
        operation,
        JSONObject()
            .put("host", "embedded-termux")
            .put("skipped", true)
            .put("required", false)
            .put("reason", reason),
    )

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
          --data '{"port":8765,"clientId":"operit-termux"}' \
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

internal fun setupOperationResult(
    operation: String,
    action: String,
    result: OperitHostCommandResult,
): OperitHostOperationResult {
    val details = JSONObject()
        .put("operation", operation)
        .put("action", action)
        .put("exitCode", result.exitCode)
        .put("timedOut", result.timedOut)
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
        val message = result.error.ifBlank { result.stderr.ifBlank { result.stdout.ifBlank { "$action failed" } } }
        OperitHostOperationResult(false, details, message, message)
    }
}

internal data class TermuxRuntimeLayout(
    val prefix: File,
    val home: File,
) {
    val bash: File get() = File(prefix, "bin/bash")
    val prootDistro: File get() = File(prefix, "bin/proot-distro")
    val currentUbuntuRootfs: File get() = File(prefix, "var/lib/proot-distro/containers/ubuntu/rootfs")
    val legacyUbuntuRootfs: File get() = File(prefix, "var/lib/proot-distro/installed-rootfs/ubuntu")

    companion object {
        fun defaults() = TermuxRuntimeLayout(
            File(TermuxOpenHouseHost.TERMUX_PREFIX),
            File(TermuxOpenHouseHost.TERMUX_HOME),
        )
    }
}

internal class TermuxHostCommandExecutor(
    private val layout: TermuxRuntimeLayout,
    private val serviceManagerUrl: String,
    private val runner: ConcurrentProcessRunner = ConcurrentProcessRunner(),
) {
    fun execute(command: String, target: HostTerminalTarget, timeoutMs: Long): OperitHostCommandResult {
        val startedAt = System.currentTimeMillis()
        val cleanCommand = command.trim()
        if (cleanCommand.isEmpty()) return failure(command, 2, "command is empty", startedAt)
        require(target != HostTerminalTarget.ANDROID) {
            "Android commands must use TermuxAndroidShellCommandExecutor"
        }
        if (!layout.bash.isFile) {
            return failure(command, 127, "Termux bash is not installed: ${layout.bash.absolutePath}", startedAt)
        }
        if (!layout.home.isDirectory) {
            return failure(command, 127, "Termux home is unavailable: ${layout.home.absolutePath}", startedAt)
        }

        val effectiveTimeout = timeoutMs.coerceAtLeast(1L)
        val shellCommand = if (target == HostTerminalTarget.UBUNTU) {
            val plan = ubuntuCommand(command, cleanCommand, effectiveTimeout, startedAt)
            plan.failure?.let { return it }
            checkNotNull(plan.shellCommand)
        } else {
            cleanCommand
        }
        return runner.run(
            originalCommand = command,
            processBuilder = processBuilder(shellCommand, target),
            timeoutMs = effectiveTimeout,
            startedAt = startedAt,
        )
    }

    private fun ubuntuCommand(
        originalCommand: String,
        cleanCommand: String,
        timeoutMs: Long,
        startedAt: Long,
    ): UbuntuCommandPlan {
        if (!layout.prootDistro.isFile) {
            return UbuntuCommandPlan(
                failure = failure(
                    originalCommand,
                    127,
                    "Ubuntu is unavailable because proot-distro is not installed: ${layout.prootDistro.absolutePath}",
                    startedAt,
                ),
            )
        }
        if (!layout.currentUbuntuRootfs.isDirectory && !layout.legacyUbuntuRootfs.isDirectory) {
            return UbuntuCommandPlan(
                failure = failure(
                    originalCommand,
                    127,
                    "Ubuntu rootfs is not ready. Checked ${layout.currentUbuntuRootfs.absolutePath} and ${layout.legacyUbuntuRootfs.absolutePath}",
                    startedAt,
                ),
            )
        }
        val probe = runner.run(
            originalCommand = originalCommand,
            processBuilder = processBuilder(
                "${shellQuote(layout.prootDistro.absolutePath)} login ubuntu -- true",
                HostTerminalTarget.UBUNTU,
            ),
            timeoutMs = minOf(timeoutMs, 10_000L),
            startedAt = startedAt,
        )
        if (!probe.isSuccess) {
            return UbuntuCommandPlan(
                failure = probe.copy(
                    command = originalCommand,
                    error = "Ubuntu login probe failed. ${probe.error.ifBlank { probe.stderr.ifBlank { probe.stdout } }}".trim(),
                ),
            )
        }
        return UbuntuCommandPlan(
            shellCommand = "${shellQuote(layout.prootDistro.absolutePath)} login ubuntu -- bash -lc ${shellQuote(cleanCommand)}",
        )
    }

    private fun processBuilder(shellCommand: String, target: HostTerminalTarget): ProcessBuilder {
        val builder = ProcessBuilder(layout.bash.absolutePath, "-lc", shellCommand)
            .directory(layout.home)
            .redirectErrorStream(false)
        val environment = builder.environment()
        environment["HOME"] = layout.home.absolutePath
        environment["PREFIX"] = layout.prefix.absolutePath
        environment["PATH"] = prependPath(environment["PATH"], File(layout.prefix, "bin").absolutePath, "/system/bin")
        environment["LD_LIBRARY_PATH"] = prependPath(
            environment["LD_LIBRARY_PATH"],
            File(layout.prefix, "lib").absolutePath,
        )
        environment["TMPDIR"] = File(layout.prefix, "tmp").absolutePath
        if (environment["LANG"].isNullOrBlank()) environment["LANG"] = "C.UTF-8"
        val resolvedTarget = if (target == HostTerminalTarget.UBUNTU) "ubuntu" else "termux"
        environment["OPENHOUSEAI_HOST_TERMINAL"] = "1"
        environment["SMALLPHONEAI_HOST_TERMINAL"] = "1"
        environment["OPERIT_HOST_TERMINAL"] = "1"
        environment["OPENHOUSEAI_TERMINAL_PROVIDER"] = "openhouse-termux"
        environment["SMALLPHONEAI_TERMINAL_PROVIDER"] = "openhouse-termux"
        environment["OPERIT_TERMINAL_PROVIDER"] = "openhouse-termux"
        environment["OPENHOUSEAI_HOST_TERMINAL_TARGET"] = resolvedTarget
        environment["SMALLPHONEAI_HOST_TERMINAL_TARGET"] = resolvedTarget
        environment["OPERIT_HOST_TERMINAL_TARGET"] = resolvedTarget
        environment["OPENHOUSEAI_SERVICE_MANAGER_URL"] = serviceManagerUrl
        environment["SMALLPHONEAI_SERVICE_MANAGER_URL"] = serviceManagerUrl
        environment["OPERIT_SERVICE_MANAGER_URL"] = serviceManagerUrl
        environment["OPENHOUSEAI_NO_AUTO_UBUNTU"] = "1"
        environment["SMALLPHONEAI_NO_AUTO_UBUNTU"] = "1"
        environment["TERMUX_NO_AUTO_UBUNTU"] = "1"
        environment["OPERIT_NO_AUTO_UBUNTU"] = "1"
        if (target == HostTerminalTarget.UBUNTU) {
            environment["OPENHOUSEAI_UBUNTU_PROVIDER"] = "termux-proot-distro"
            environment["SMALLPHONEAI_UBUNTU_PROVIDER"] = "termux-proot-distro"
            environment["OPERIT_UBUNTU_PROVIDER"] = "termux-proot-distro"
            environment["OPENHOUSEAI_UBUNTU_CONTAINER"] = "ubuntu"
            environment["SMALLPHONEAI_UBUNTU_CONTAINER"] = "ubuntu"
            environment["OPERIT_UBUNTU_CONTAINER"] = "ubuntu"
        }
        return builder
    }

    private fun prependPath(original: String?, vararg entries: String): String {
        val values = original.orEmpty().split(File.pathSeparator).filter(String::isNotBlank).toMutableList()
        entries.reversed().forEach { entry ->
            values.remove(entry)
            values.add(0, entry)
        }
        return values.joinToString(File.pathSeparator)
    }

    private fun failure(command: String, code: Int, message: String, startedAt: Long) = OperitHostCommandResult(
        command = command,
        exitCode = code,
        stdout = "",
        stderr = "",
        error = message,
        timedOut = false,
        durationMs = System.currentTimeMillis() - startedAt,
    )

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private data class UbuntuCommandPlan(
        val shellCommand: String? = null,
        val failure: OperitHostCommandResult? = null,
    )
}

internal class ConcurrentProcessRunner {
    fun run(
        originalCommand: String,
        processBuilder: ProcessBuilder,
        timeoutMs: Long,
        startedAt: Long = System.currentTimeMillis(),
        stdin: String? = null,
    ): OperitHostCommandResult = runCatching {
        val process = processBuilder.start()
        val stdout = StreamDrainer(process.inputStream, "host-command-stdout")
        val stderr = StreamDrainer(process.errorStream, "host-command-stderr")
        val input = StreamFeeder(process.outputStream, stdin, "host-command-stdin")
        stdout.start()
        stderr.start()
        input.start()
        val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(200L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        val stdoutCapture = stdout.await()
        val stderrCapture = stderr.await()
        val inputError = input.await()
        val exitCode = if (finished) process.exitValue() else 124
        val streamError = listOf(stdoutCapture.error, stderrCapture.error, inputError)
            .filter(String::isNotBlank)
            .joinToString("\n")
        val error = when {
            !finished -> listOf("command timed out after ${timeoutMs.coerceAtLeast(1L)} ms", streamError)
                .filter(String::isNotBlank).joinToString("\n")
            exitCode != 0 -> stderrCapture.text.ifBlank { stdoutCapture.text }.ifBlank { streamError }
            streamError.isNotBlank() -> streamError
            else -> ""
        }
        OperitHostCommandResult(
            command = originalCommand,
            exitCode = exitCode,
            stdout = stdoutCapture.text,
            stderr = stderrCapture.text,
            error = error,
            timedOut = !finished,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }.getOrElse { error ->
        OperitHostCommandResult(
            command = originalCommand,
            exitCode = 1,
            stdout = "",
            stderr = "",
            error = error.message ?: error.javaClass.simpleName,
            timedOut = false,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

private class StreamFeeder(
    private val stream: OutputStream,
    private val input: String?,
    name: String,
) {
    private var failure: Throwable? = null
    private val thread = Thread({
        try {
            stream.use { output ->
                input?.let { output.write(it.toByteArray(Charsets.UTF_8)) }
                output.flush()
            }
        } catch (error: Throwable) {
            failure = error
        }
    }, name).apply { isDaemon = true }

    fun start() = thread.start()

    fun await(): String {
        thread.join(2_000L)
        if (thread.isAlive) {
            runCatching { stream.close() }
            thread.join(250L)
        }
        return failure?.message.orEmpty()
    }
}

private data class StreamCapture(val text: String, val error: String)

private class StreamDrainer(
    private val stream: InputStream,
    name: String,
) {
    private val output = ByteArrayOutputStream()
    private var failure: Throwable? = null
    private val thread = Thread({
        try {
            stream.use { input -> input.copyTo(output) }
        } catch (error: Throwable) {
            failure = error
        }
    }, name).apply { isDaemon = true }

    fun start() = thread.start()

    fun await(): StreamCapture {
        thread.join(2_000L)
        if (thread.isAlive) {
            runCatching { stream.close() }
            thread.join(250L)
        }
        return StreamCapture(
            output.toString(Charsets.UTF_8.name()),
            failure?.message.orEmpty(),
        )
    }
}
