package com.ai.assistance.operit.host.setup

import android.content.Context
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.OperitHostOperations

/** Routes high-level setup tools to the active host without owning installation state. */
class WuxianPiSetupToolExecutor(
    private val operationsProvider: () -> OperitHostOperations,
) {
    fun handles(toolName: String): Boolean = toolName in WuxianPiSetupContract.toolNames

    suspend fun execute(
        toolName: String,
        context: Context,
    ): OperitHostOperationResult {
        val operations = operationsProvider()
        return when (toolName) {
            WuxianPiSetupContract.TOOL_INSPECT -> operations.inspectWuxianPiSetup()
            WuxianPiSetupContract.TOOL_PREPARE_RUNTIME_HOST ->
                operations.prepareRuntimeHost(context)
            WuxianPiSetupContract.TOOL_REQUEST_TERMUX_HOME_ACCESS ->
                operations.requestTermuxHomeAccess(context)
            WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION ->
                operations.requestTermuxRunCommandPermission(context)
            WuxianPiSetupContract.TOOL_CONFIGURE_TERMUX_EXTERNAL_APPS ->
                operations.configureTermuxExternalApps()
            WuxianPiSetupContract.TOOL_VERIFY_TERMUX_RUN_COMMAND ->
                operations.verifyTermuxRunCommand()
            WuxianPiSetupContract.TOOL_PREPARE_PERSISTENT_TERMUX ->
                operations.preparePersistentTermux()
            WuxianPiSetupContract.TOOL_START_SETUP -> operations.startWuxianPiSetup()
            WuxianPiSetupContract.TOOL_SETUP_STATUS -> operations.wuxianPiSetupStatus()
            WuxianPiSetupContract.TOOL_STORE_SERVICE_MANAGER_CONNECTION ->
                operations.storeServiceManagerConnection()
            else -> error("Unknown WuxianPi setup tool: $toolName")
        }
    }
}
