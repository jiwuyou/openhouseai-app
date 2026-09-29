package com.ai.assistance.operit.host.setup

import android.content.Context
import android.os.Bundle
import android.net.Uri
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.wuxianpi.openhouse.core.workspace.OpenHouseHomeSettingsProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Client for the home-settings provider owned by the OpenHouse process. */
object OpenHouseHomeSettingsClient {
    suspend fun getHome(context: Context): OperitHostOperationResult =
        call(context, OpenHouseHomeSettingsProtocol.METHOD_GET_HOME)

    suspend fun listCandidates(context: Context): OperitHostOperationResult =
        call(context, OpenHouseHomeSettingsProtocol.METHOD_LIST_CANDIDATES)

    suspend fun setHome(context: Context, componentId: String): OperitHostOperationResult =
        call(
            context,
            OpenHouseHomeSettingsProtocol.METHOD_SET_HOME,
            Bundle().apply {
                putString(OpenHouseHomeSettingsProtocol.ARG_COMPONENT_ID, componentId)
            },
        )

    private suspend fun call(
        context: Context,
        method: String,
        extras: Bundle? = null,
    ): OperitHostOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            val response = context.applicationContext.contentResolver.call(
                Uri.parse("content://${OpenHouseHomeSettingsProtocol.authority(context)}"),
                method,
                null,
                extras,
            ) ?: return@runCatching failure(method, "OpenHouse home provider returned no response")
            val success = response.getBoolean(OpenHouseHomeSettingsProtocol.EXTRA_SUCCESS, false)
            val details = JSONObject()
                .put("operation", method)
                .put("success", success)
            response.getString(OpenHouseHomeSettingsProtocol.EXTRA_ERROR_CODE)
                ?.let { details.put("errorCode", it) }
            response.getString(OpenHouseHomeSettingsProtocol.EXTRA_MESSAGE)
                ?.let { details.put("message", it) }
            response.getString(OpenHouseHomeSettingsProtocol.EXTRA_HOME_JSON)
                ?.let { details.put("home", JSONObject(it)) }
            response.getString(OpenHouseHomeSettingsProtocol.EXTRA_CANDIDATES_JSON)
                ?.let { details.put("candidates", JSONArray(it)) }
            OperitHostOperationResult(
                success = success,
                details = details,
                message = response.getString(OpenHouseHomeSettingsProtocol.EXTRA_MESSAGE).orEmpty(),
                error = response.getString(OpenHouseHomeSettingsProtocol.EXTRA_ERROR_CODE)
                    ?.takeIf { !success },
            )
        }.getOrElse { error ->
            failure(method, error.message ?: "OpenHouse home provider call failed", error)
        }
    }

    private fun failure(
        operation: String,
        message: String,
        error: Throwable? = null,
    ): OperitHostOperationResult = OperitHostOperationResult(
        success = false,
        details = JSONObject()
            .put("operation", operation)
            .put("success", false),
        message = message,
        error = error?.message ?: message,
    )
}
