package com.ai.assistance.operit.host.control

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File

enum class OperitProcessState(val wireName: String) {
    NOT_RUNNING("not_running"),
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    STOPPING("stopping");

    fun isRunningLike(): Boolean = this == FOREGROUND || this == BACKGROUND || this == STOPPING

    companion object {
        @JvmStatic
        fun fromWireName(value: String?): OperitProcessState =
            entries.firstOrNull { it.wireName == value } ?: NOT_RUNNING
    }
}

data class OperitControlStateSnapshot(
    val storedState: OperitProcessState,
    val effectiveState: OperitProcessState,
    val rawState: String,
    val processName: String,
    val pid: Int,
    val updatedAtMs: Long,
    val stoppedAtMs: Long
) {
    fun isRunning(): Boolean =
        effectiveState == OperitProcessState.FOREGROUND || effectiveState == OperitProcessState.BACKGROUND

    fun isForeground(): Boolean = effectiveState == OperitProcessState.FOREGROUND

    fun isBackground(): Boolean = effectiveState == OperitProcessState.BACKGROUND

    fun isStopping(): Boolean = effectiveState == OperitProcessState.STOPPING
}

object OperitControlProtocol {
    const val OPERIT_PROCESS_SUFFIX = ":operit"
    const val ACTION_REQUEST_SHUTDOWN = "com.ai.assistance.operit.host.action.REQUEST_SHUTDOWN"
    const val ACTION_STATE_CHANGED = "com.ai.assistance.operit.host.action.STATE_CHANGED"
    const val EXTRA_SHUTDOWN_REASON = "com.ai.assistance.operit.host.extra.SHUTDOWN_REASON"
    const val EXTRA_REQUESTED_BY = "com.ai.assistance.operit.host.extra.REQUESTED_BY"
    const val EXTRA_STATE = "com.ai.assistance.operit.host.extra.STATE"
    const val SHUTDOWN_REASON_USER = "user"
    const val SHUTDOWN_REQUESTED_BY_HOME = "home"
    const val SHUTDOWN_REQUESTED_BY_OPERIT = "operit"
    @JvmStatic
    fun operitProcessName(packageName: String): String = packageName + OPERIT_PROCESS_SUFFIX

    @JvmStatic
    fun isOperitProcessName(packageName: String, processName: String?): Boolean =
        processName == operitProcessName(packageName)

    @JvmStatic
    fun isMainProcessName(packageName: String, processName: String?): Boolean =
        processName == packageName

    @JvmStatic
    fun isCurrentOperitProcess(context: Context): Boolean =
        isOperitProcessName(context.packageName, resolveCurrentProcessName(context))

    @JvmStatic
    fun isCurrentMainProcess(context: Context): Boolean =
        isMainProcessName(context.packageName, resolveCurrentProcessName(context))

    @JvmStatic
    @JvmOverloads
    fun createShutdownIntent(context: Context, reason: String = SHUTDOWN_REASON_USER): Intent =
        Intent(ACTION_REQUEST_SHUTDOWN)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SHUTDOWN_REASON, reason)

    @JvmStatic
    fun createStateChangedIntent(context: Context, state: OperitProcessState): Intent =
        Intent(ACTION_STATE_CHANGED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, state.wireName)

    @JvmStatic
    fun resolveCurrentProcessName(context: Context): String =
        resolveProcessNameFromApplication()
            ?: resolveProcessNameFromProc()
            ?: resolveProcessNameFromActivityManager(context)
            ?: ""

    private fun resolveProcessNameFromApplication(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    private fun resolveProcessNameFromProc(): String? =
        runCatching {
            File("/proc/self/cmdline").readText()
                .trim { it <= ' ' || it == '\u0000' }
                .takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun resolveProcessNameFromActivityManager(context: Context): String? =
        runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = Process.myPid()
            activityManager?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

object OperitControlStateStore {
    private const val PREFS_NAME = "operit_host_control"
    private const val KEY_STATE = "state"
    private const val KEY_PROCESS_NAME = "process_name"
    private const val KEY_PID = "pid"
    private const val KEY_UPDATED_AT_MS = "updated_at_ms"
    private const val KEY_STOPPED_AT_MS = "stopped_at_ms"

    @JvmStatic
    fun read(context: Context): OperitControlStateSnapshot {
        val preferences = preferences(context)
        val rawState = preferences.getString(KEY_STATE, null).orEmpty()
        val storedState = OperitProcessState.fromWireName(rawState)
        val processName = preferences.getString(KEY_PROCESS_NAME, null).orEmpty()
        val pid = preferences.getInt(KEY_PID, -1)
        val processAlive =
            storedState.isRunningLike() && isProcessAlive(context, pid, processName)
        // Keep reads side-effect free so an old host snapshot cannot overwrite a newly started process.
        val effectiveState =
            if (storedState.isRunningLike() && !processAlive) {
                OperitProcessState.NOT_RUNNING
            } else {
                storedState
            }

        return OperitControlStateSnapshot(
            storedState = storedState,
            effectiveState = effectiveState,
            rawState = rawState.ifBlank { storedState.wireName },
            processName = processName,
            pid = pid,
            updatedAtMs = preferences.getLong(KEY_UPDATED_AT_MS, 0L),
            stoppedAtMs = preferences.getLong(KEY_STOPPED_AT_MS, 0L)
        )
    }

    @JvmStatic
    fun markForeground(context: Context): OperitControlStateSnapshot =
        mark(context, OperitProcessState.FOREGROUND)

    @JvmStatic
    fun markBackground(context: Context): OperitControlStateSnapshot =
        mark(context, OperitProcessState.BACKGROUND)

    @JvmStatic
    fun markStopping(context: Context): OperitControlStateSnapshot =
        mark(context, OperitProcessState.STOPPING)

    @JvmStatic
    fun markStopped(context: Context): OperitControlStateSnapshot {
        val appContext = context.applicationContext ?: context
        val now = System.currentTimeMillis()
        preferences(appContext)
            .edit()
            .putString(KEY_STATE, OperitProcessState.NOT_RUNNING.wireName)
            .putString(KEY_PROCESS_NAME, "")
            .putInt(KEY_PID, -1)
            .putLong(KEY_UPDATED_AT_MS, now)
            .putLong(KEY_STOPPED_AT_MS, now)
            .commit()
        sendStateChanged(appContext, OperitProcessState.NOT_RUNNING)
        return read(appContext)
    }

    @JvmStatic
    fun isRunning(context: Context): Boolean = read(context).isRunning()

    @JvmStatic
    fun isBackground(context: Context): Boolean = read(context).isBackground()

    @JvmStatic
    fun isForeground(context: Context): Boolean = read(context).isForeground()

    private fun mark(
        context: Context,
        state: OperitProcessState
    ): OperitControlStateSnapshot {
        val appContext = context.applicationContext ?: context
        val now = System.currentTimeMillis()
        val processName = OperitControlProtocol.resolveCurrentProcessName(appContext)
        val editor =
            preferences(appContext)
                .edit()
                .putString(KEY_STATE, state.wireName)
                .putString(KEY_PROCESS_NAME, processName)
                .putInt(KEY_PID, Process.myPid())
                .putLong(KEY_UPDATED_AT_MS, now)
        editor.commit()
        sendStateChanged(appContext, state)
        return read(appContext)
    }

    private fun isProcessAlive(context: Context, expectedPid: Int, expectedProcessName: String): Boolean {
        if (expectedPid <= 0 || expectedProcessName.isBlank()) return false

        val expectedUid = context.applicationInfo.uid
        val runningProcesses =
            runCatching {
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                activityManager?.runningAppProcesses
            }.getOrNull()
        if (runningProcesses.orEmpty().any { process ->
                process.pid == expectedPid &&
                    process.uid == expectedUid &&
                    process.processName == expectedProcessName
            }
        ) {
            return true
        }

        return runCatching {
            val processName =
                File("/proc/$expectedPid/cmdline").readText()
                    .trim { it <= ' ' || it == '\u0000' }
            val processUid =
                File("/proc/$expectedPid/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.substringAfter("Uid:")
                        ?.trim()
                        ?.takeWhile { !it.isWhitespace() }
                        ?.toIntOrNull()
                }
            processName == expectedProcessName && processUid == expectedUid
        }.getOrDefault(false)
    }

    private fun sendStateChanged(context: Context, state: OperitProcessState) {
        runCatching {
            context.sendBroadcast(OperitControlProtocol.createStateChangedIntent(context, state))
        }
    }

    @Suppress("DEPRECATION")
    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
}
