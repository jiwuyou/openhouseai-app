package com.ai.assistance.operit.core.tools.system

import android.app.Activity
import android.content.Context
import com.ai.assistance.operit.host.OperitHostProvider

/** Routes hosted Operit Shizuku setup through the injected host bridge. */
object OpenHouseShizukuHost {
    fun openPermissions(context: Context): Boolean {
        return OperitHostProvider.operationsOrUnsupported().openPermissions(context)
    }
}
