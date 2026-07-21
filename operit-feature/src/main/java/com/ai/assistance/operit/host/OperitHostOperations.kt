package com.ai.assistance.operit.host

import android.content.Context
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import org.json.JSONObject

/** Host capabilities used by shared Operit and Rescue UI without naming a host implementation. */
interface OperitHostOperations {
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

    suspend fun repairJobStatus(jobId: String): OperitHostOperationResult

    suspend fun exportDiagnostics(report: String): OperitHostOperationResult
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
