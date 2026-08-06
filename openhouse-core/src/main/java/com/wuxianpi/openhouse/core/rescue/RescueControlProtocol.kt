package com.wuxianpi.openhouse.core.rescue

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

/** Package-private control contract shared by OpenHouse and the Android-local Rescue process. */
object RescueControlProtocol {
    const val PROCESS_SUFFIX = ":rescue_ui"
    const val ACTION_REQUEST_SHUTDOWN =
        "com.wuxianpi.openhouse.rescue.action.REQUEST_SHUTDOWN"
    const val ACTION_STATE_CHANGED =
        "com.wuxianpi.openhouse.rescue.action.STATE_CHANGED"
    const val EXTRA_STATE = "com.wuxianpi.openhouse.rescue.extra.STATE"
    const val EXTRA_SHUTDOWN_REASON = "com.wuxianpi.openhouse.rescue.extra.SHUTDOWN_REASON"
    const val SHUTDOWN_REASON_USER = "user"

    @JvmStatic
    fun processName(packageName: String): String = packageName + PROCESS_SUFFIX

    @JvmStatic
    fun createShutdownIntent(context: Context, reason: String = SHUTDOWN_REASON_USER): Intent =
        Intent(ACTION_REQUEST_SHUTDOWN)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SHUTDOWN_REASON, reason)

    @JvmStatic
    fun createStateChangedIntent(context: Context, state: RescueProcessState): Intent =
        Intent(ACTION_STATE_CHANGED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, state.wireName)

    @JvmStatic
    fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
            .orEmpty()
    }
}

enum class RescueProcessState(val wireName: String) {
    NOT_RUNNING("not_running"),
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    STOPPING("stopping");

    fun isRunningLike(): Boolean = this != NOT_RUNNING

    companion object {
        @JvmStatic
        fun fromWireName(value: String?): RescueProcessState =
            entries.firstOrNull { it.wireName == value } ?: NOT_RUNNING
    }
}
