package com.ai.assistance.operit.host.setup

import android.content.Context
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostOperations
import com.ai.assistance.operit.host.terminal.HostTerminalTarget

open class TestOperitHostOperations : OperitHostOperations {
    override suspend fun executeCommand(
        command: String,
        target: HostTerminalTarget,
        timeoutMs: Long,
    ): OperitHostCommandResult =
        OperitHostCommandResult(command, 127, "", "", "unsupported", false, 0L)

    override fun openPermissions(context: Context): Boolean = false

    override fun openHostApp(context: Context): OperitHostOperationResult = result("open_host")

    override fun pairingInstallerScript(baseUrl: String, token: String): String? = null

    override suspend fun runtimeStatus(): OperitHostOperationResult = result("runtime_status")

    override suspend fun readRuntimeDiagnostics(maxBytes: Int): OperitHostOperationResult =
        result("read_diagnostics")

    override suspend fun restartRuntime(): OperitHostOperationResult = result("restart_runtime")

    override suspend fun redeployRuntime(
        payload: ByteArray,
        fileName: String,
        mimeType: String,
    ): OperitHostOperationResult = result("redeploy_runtime")

    override suspend fun repairJobStatus(jobId: String): OperitHostOperationResult =
        result("repair_job_status")

    override suspend fun exportDiagnostics(report: String): OperitHostOperationResult =
        result("export_logs")

    protected fun result(operation: String): OperitHostOperationResult =
        OperitHostOperationResult(false, message = operation, error = operation)
}
