package com.openhouse.host.nativeapp

import android.content.Context
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.ai.assistance.operit.host.OperitHostContract
import com.ai.assistance.operit.host.OperitHostPersistentShellExecution
import com.ai.assistance.operit.host.OperitHostServiceManagerRecovery
import com.ai.assistance.operit.host.OperitHostServiceManagerResult
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.wuxianpi.openhouse.core.ControlPlaneResult
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import com.wuxianpi.openhouse.core.service.ServiceManagerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full legacy Operit host contract backed by the native host adapter. */
class NativeOperitHostContract(
    context: Context,
    private val operations: NativeOperitHostOperations,
    private val openHouseHost: NativeOpenHouseHost,
) : OperitHostContract, OperitHostServiceManagerRecovery, OperitHostPersistentShellExecution {
    override val applicationContext: Context = context.applicationContext ?: context

    override suspend fun executeTermuxCommand(
        command: String,
        timeoutMs: Long,
    ): OperitHostCommandResult = operations.executeCommand(command, HostTerminalTarget.HOST, timeoutMs)

    override suspend fun executeUbuntuCommand(
        command: String,
        timeoutMs: Long,
    ): OperitHostCommandResult = operations.executeCommand(command, HostTerminalTarget.UBUNTU, timeoutMs)

    override suspend fun executePersistentShellCommand(
        command: String,
        timeoutMs: Long,
    ): OperitHostCommandResult = operations.executeCommand(command, HostTerminalTarget.HOST, timeoutMs)

    override suspend fun queryServiceManagerHealth(): OperitHostServiceManagerResult = withContext(Dispatchers.IO) {
        val result = ServiceManagerClient(openHouseHost.runtimeConnection()).healthCheck()
        result.toHostResult(serviceIdFallback = "service-manager")
    }

    override suspend fun queryServiceManagerStatus(serviceId: String): OperitHostServiceManagerResult =
        withContext(Dispatchers.IO) {
            ServiceManagerClient(openHouseHost.runtimeConnection())
                .getStatus(serviceId)
                .toHostResult(serviceIdFallback = serviceId)
        }

    override suspend fun recoverServiceManagerControlPlane(reason: String): OperitHostServiceManagerResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val result = openHouseHost.controlPlaneStarter().startControlPlane()
            OperitHostServiceManagerResult(
                success = result.isSuccess,
                code = result.status.toHttpCode(),
                url = openHouseHost.runtimeConnection().serviceManagerBaseUrl,
                body = result.message,
                message = result.message.ifBlank { "Native control-plane recovery: $reason" },
                serviceId = "service-manager",
                state = result.status.name.lowercase(),
                provider = "native-openhouse-control-plane",
                pid = -1,
                serviceUrl = openHouseHost.runtimeConnection().serviceManagerBaseUrl,
                error = if (result.isSuccess) "" else result.message.ifBlank { "control-plane recovery failed" },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

    private fun ServiceManagerResult.toHostResult(serviceIdFallback: String): OperitHostServiceManagerResult {
        val runtimeUrl = openHouseHost.runtimeConnection().serviceManagerBaseUrl
        return OperitHostServiceManagerResult(
            success = success,
            code = code,
            url = runtimeUrl,
            body = body,
            message = message,
            serviceId = serviceId.ifBlank { serviceIdFallback },
            state = state,
            provider = provider.ifBlank { "native-openhouse-service-manager" },
            pid = pid ?: -1,
            serviceUrl = url,
            error = if (success) "" else message,
            durationMs = 0L,
        )
    }

    private fun ControlPlaneResult.Status.toHttpCode(): Int = when (this) {
        ControlPlaneResult.Status.STARTED,
        ControlPlaneResult.Status.ALREADY_RUNNING,
        ControlPlaneResult.Status.STOPPED,
        ControlPlaneResult.Status.ALREADY_STOPPED -> 200
        ControlPlaneResult.Status.USER_ACTION_REQUIRED -> 409
        ControlPlaneResult.Status.UNSUPPORTED -> 501
        ControlPlaneResult.Status.FAILED -> 503
    }
}
