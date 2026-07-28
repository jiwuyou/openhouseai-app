package com.ai.assistance.operit.host.setup

import com.ai.assistance.operit.host.OperitHostOperationResult
import org.json.JSONObject

/** Shared names and result fields for deterministic WuxianPi first-use setup. */
object WuxianPiSetupContract {
    const val SMALLPHONE_HOST_ACTION = "com.termux.SMALLPHONE_HOST"
    const val PREPARE_HOST_ACTION = "com.termux.WUXIANPI_PREPARE_HOST"

    const val TOOL_INSPECT = "inspect_wuxianpi_setup"
    const val TOOL_PREPARE_RUNTIME_HOST = "prepare_runtime_host"
    const val TOOL_REQUEST_TERMUX_HOME_ACCESS = "request_termux_home_access"
    const val TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION =
        "request_termux_run_command_permission"
    const val TOOL_PREPARE_PERSISTENT_TERMUX = "prepare_persistent_termux"
    const val TOOL_START_SETUP = "start_wuxianpi_setup"
    const val TOOL_SETUP_STATUS = "get_wuxianpi_setup_status"

    const val OPERATION_INSPECT = "inspect_wuxianpi_setup"
    const val OPERATION_PREPARE_RUNTIME_HOST = "prepare_runtime_host"
    const val OPERATION_REQUEST_TERMUX_HOME_ACCESS = "request_termux_home_access"
    const val OPERATION_REQUEST_TERMUX_RUN_COMMAND_PERMISSION =
        "request_termux_run_command_permission"
    const val OPERATION_PREPARE_PERSISTENT_TERMUX = "prepare_persistent_termux"
    const val OPERATION_START_SETUP = "start_wuxianpi_setup"
    const val OPERATION_SETUP_STATUS = "wuxianpi_setup_status"

    const val DETAIL_OPERATION = "operation"
    const val DETAIL_SUPPORTED = "supported"
    const val DETAIL_SUCCESS = "success"
    const val DETAIL_USER_ACTION_REQUIRED = "userActionRequired"
    const val DETAIL_DEFERRED_USER_ACTION = "deferredUserAction"

    val toolNames: Set<String> =
        linkedSetOf(
            TOOL_INSPECT,
            TOOL_PREPARE_RUNTIME_HOST,
            TOOL_REQUEST_TERMUX_HOME_ACCESS,
            TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION,
            TOOL_PREPARE_PERSISTENT_TERMUX,
            TOOL_START_SETUP,
            TOOL_SETUP_STATUS,
        )

    fun unsupported(operation: String): OperitHostOperationResult =
        OperitHostOperationResult(
            success = false,
            details =
                JSONObject()
                    .put(DETAIL_OPERATION, operation)
                    .put(DETAIL_SUPPORTED, false)
                    .put(DETAIL_USER_ACTION_REQUIRED, false),
            message = "$operation is not supported by the active host",
            error = "No host setup bridge is installed",
        )

    fun requiresUserAction(result: OperitHostOperationResult): Boolean =
        result.details.optBoolean(DETAIL_USER_ACTION_REQUIRED, false)

    fun normalizedDetails(result: OperitHostOperationResult): JSONObject =
        JSONObject(result.details.toString())
            .put(DETAIL_SUCCESS, result.success)
            .put(DETAIL_USER_ACTION_REQUIRED, requiresUserAction(result))
            .also { details ->
                result.message.takeIf(String::isNotBlank)?.let { details.put("message", it) }
                result.error?.takeIf(String::isNotBlank)?.let { details.put("error", it) }
            }
}
