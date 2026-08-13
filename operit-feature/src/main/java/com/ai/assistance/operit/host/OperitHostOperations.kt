package com.ai.assistance.operit.host

import android.content.Context
import com.ai.assistance.operit.host.setup.WuxianPiSetupContract
import com.ai.assistance.operit.host.terminal.HostTerminalSessionBackend
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import org.json.JSONObject

/** Host capabilities used by shared Operit and Rescue UI without naming a host implementation. */
interface OperitHostOperations {
    /** Optional real terminal/session backend supplied by the active host adapter. */
    val terminalSessionBackend: HostTerminalSessionBackend?
        get() = null

    /** Execute on exactly the requested ANDROID, TERMUX, or UBUNTU backend without fallback. */
    suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget = HostTerminalTarget.DEFAULT,
        timeoutMs: Long = 15_000L,
    ): OperitHostCommandResult

    fun openPermissions(context: Context): Boolean

    fun openHostApp(context: Context): OperitHostOperationResult

    fun pairingInstallerScript(baseUrl: String, token: String): String?

    suspend fun runtimeStatus(): OperitHostOperationResult

    suspend fun readRuntimeDiagnostics(maxBytes: Int): OperitHostOperationResult

    suspend fun restartRuntime(): OperitHostOperationResult

    suspend fun redeployRuntime(
        payload: ByteArray,
        fileName: String,
        mimeType: String,
    ): OperitHostOperationResult

    /** Streams the canonical APK install bundle into the Termux resource inbox. */
    suspend fun stageApkInstallBundle(): OperitHostOperationResult =
        OperitHostOperationResult(
            success = false,
            details = JSONObject().put("operation", "stage_apk_install_bundle"),
            message = "APK install bundle staging is unavailable",
            error = "The active host does not support APK install bundle staging",
        )

    suspend fun readRescueMemoryMirror(): OperitHostOperationResult =
        OperitHostOperationResult(
            success = false,
            details = JSONObject().put("operation", "read_rescue_memory_mirror"),
            message = "Rescue memory mirror is unavailable",
            error = "The active host does not support Rescue memory synchronization",
        )

    suspend fun writeRescueMemoryMirror(payload: ByteArray): OperitHostOperationResult =
        OperitHostOperationResult(
            success = false,
            details = JSONObject().put("operation", "write_rescue_memory_mirror"),
            message = "Rescue memory mirror is unavailable",
            error = "The active host does not support Rescue memory synchronization",
        )

    suspend fun repairJobStatus(jobId: String): OperitHostOperationResult

    suspend fun exportDiagnostics(report: String): OperitHostOperationResult

    suspend fun inspectWuxianPiSetup(): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(WuxianPiSetupContract.OPERATION_INSPECT)

    fun prepareRuntimeHost(context: Context): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(WuxianPiSetupContract.OPERATION_PREPARE_RUNTIME_HOST)

    fun requestTermuxHomeAccess(context: Context): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(WuxianPiSetupContract.OPERATION_REQUEST_TERMUX_HOME_ACCESS)

    /** Starts the SAF picker after the conversation action card was explicitly clicked. */
    fun launchTermuxHomeAccess(context: Context): OperitHostOperationResult =
        requestTermuxHomeAccess(context)

    fun requestTermuxRunCommandPermission(context: Context): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(
            WuxianPiSetupContract.OPERATION_REQUEST_TERMUX_RUN_COMMAND_PERMISSION
        )

    /** Starts the Android permission flow after the conversation action card was explicitly clicked. */
    fun launchTermuxRunCommandPermission(context: Context): OperitHostOperationResult =
        requestTermuxRunCommandPermission(context)

    /** Normalizes external Termux command opt-in using the already-authorized Home repository. */
    suspend fun configureTermuxExternalApps(): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(
            WuxianPiSetupContract.OPERATION_CONFIGURE_TERMUX_EXTERNAL_APPS
        )

    /** Proves RUN_COMMAND only after the user has reloaded Termux settings. */
    suspend fun verifyTermuxRunCommand(): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(
            WuxianPiSetupContract.OPERATION_VERIFY_TERMUX_RUN_COMMAND
        )

    suspend fun preparePersistentTermux(): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(WuxianPiSetupContract.OPERATION_PREPARE_PERSISTENT_TERMUX)

    suspend fun startWuxianPiSetup(): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(WuxianPiSetupContract.OPERATION_START_SETUP)

    suspend fun wuxianPiSetupStatus(): OperitHostOperationResult =
        WuxianPiSetupContract.unsupported(WuxianPiSetupContract.OPERATION_SETUP_STATUS)
}

data class OperitHostOperationResult(
    val success: Boolean,
    val details: JSONObject = JSONObject(),
    val message: String = "",
    val error: String? = null,
)

object UnsupportedOperitHostOperations : OperitHostOperations {
    override suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget,
        timeoutMs: Long,
    ): OperitHostCommandResult =
        OperitHostCommandResult(
            command = command,
            exitCode = 127,
            stdout = "",
            stderr = "",
            error = "Host command operations are not supported by the active host",
            timedOut = false,
            durationMs = 0L,
        )

    override fun openPermissions(context: Context): Boolean = false

    override fun openHostApp(context: Context): OperitHostOperationResult =
        unsupported("open_host")

    override fun pairingInstallerScript(baseUrl: String, token: String): String? = null

    override suspend fun runtimeStatus(): OperitHostOperationResult =
        unsupported("runtime_status")

    override suspend fun readRuntimeDiagnostics(maxBytes: Int): OperitHostOperationResult =
        unsupported("read_diagnostics")

    override suspend fun restartRuntime(): OperitHostOperationResult =
        unsupported("restart_runtime")

    override suspend fun redeployRuntime(
        payload: ByteArray,
        fileName: String,
        mimeType: String,
    ): OperitHostOperationResult = unsupported("redeploy_runtime")

    override suspend fun repairJobStatus(jobId: String): OperitHostOperationResult =
        unsupported("repair_job_status")

    override suspend fun stageApkInstallBundle(): OperitHostOperationResult =
        unsupported("stage_apk_install_bundle")

    override suspend fun readRescueMemoryMirror(): OperitHostOperationResult =
        unsupported("read_rescue_memory_mirror")

    override suspend fun writeRescueMemoryMirror(payload: ByteArray): OperitHostOperationResult =
        unsupported("write_rescue_memory_mirror")

    override suspend fun exportDiagnostics(report: String): OperitHostOperationResult =
        unsupported("export_logs")

    private fun unsupported(operation: String): OperitHostOperationResult =
        OperitHostOperationResult(
            success = false,
            details = JSONObject().put("operation", operation).put("supported", false),
            message = "$operation is not supported by the active host",
            error = "No host operation bridge is installed",
        )
}
