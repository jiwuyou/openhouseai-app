package com.ai.assistance.operit.core.tools.system

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.util.AppLogger

/** Routes hosted Operit Shizuku setup to the OpenHouse host process. */
object OpenHouseShizukuHost {
    private const val TAG = "OpenHouseShizukuHost"
    private const val HOST_PACKAGE = "com.termux"
    private const val HOST_ACTIVITY = "com.termux.app.activities.OpenHouseHomeActivity"
    private const val EXTRA_OPENHOUSE_PAGE = "openhouse_page"
    private const val PAGE_PERMISSIONS = "permissions"

    fun openPermissions(context: Context): Boolean {
        val intent =
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(HOST_PACKAGE, HOST_ACTIVITY)
                    putExtra(EXTRA_OPENHOUSE_PAGE, PAGE_PERMISSIONS)
                    addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }

        return try {
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            AppLogger.e(TAG, "Unable to open the OpenHouse permissions page", error)
            false
        }
    }
}
