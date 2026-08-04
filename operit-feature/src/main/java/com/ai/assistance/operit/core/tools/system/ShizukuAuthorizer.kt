package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.R

/** Compatibility seam for the Shizuku automation backend excluded from lean host builds. */
class ShizukuAuthorizer {
    companion object {
        private val stateChangeListeners = mutableSetOf<() -> Unit>()

        fun addStateChangeListener(listener: () -> Unit) {
            synchronized(stateChangeListeners) { stateChangeListeners.add(listener) }
        }

        fun removeStateChangeListener(listener: () -> Unit) {
            synchronized(stateChangeListeners) { stateChangeListeners.remove(listener) }
        }

        fun isShizukuInstalled(context: Context): Boolean = false

        fun getServiceErrorMessage(): String =
            "Shizuku is unavailable in this host build"

        fun getPermissionErrorMessage(): String =
            "Shizuku permission is unavailable in this host build"

        fun isShizukuServiceRunning(): Boolean = false

        fun hasShizukuPermission(): Boolean = false

        fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
            onResult(false)
        }

        fun initialize() = Unit

        fun getShizukuStartupInstructions(context: Context): String =
            context.getString(R.string.phone_agent_shizuku_unavailable)
    }
}
