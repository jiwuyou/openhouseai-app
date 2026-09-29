package com.wuxianpi.openhouse.core.workspace

import android.content.Context

/**
 * In-process-app, cross-process contract for the OpenHouse startup destination.
 *
 * Rescue runs in a different Android process, so it must not read or write
 * StartupRouteStore directly. The provider in the OpenHouse process owns the
 * actual validation and persistence; this object only contains the stable wire
 * names shared by both sides.
 */
object OpenHouseHomeSettingsProtocol {
    const val AUTHORITY_SUFFIX = ".openhouse.home"

    const val METHOD_GET_HOME = "get_home"
    const val METHOD_LIST_CANDIDATES = "list_candidates"
    const val METHOD_SET_HOME = "set_home"

    const val ARG_COMPONENT_ID = "componentId"

    const val EXTRA_SUCCESS = "success"
    const val EXTRA_ERROR_CODE = "errorCode"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_HOME_JSON = "home"
    const val EXTRA_CANDIDATES_JSON = "candidates"

    @JvmStatic
    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX
}
