package com.wuxianpi.openhouse.core.rescue

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject

data class RescueControlStateSnapshot(
    val storedState: RescueProcessState,
    val effectiveState: RescueProcessState,
    val processName: String,
    val pid: Int,
    val updatedAtMs: Long,
) {
    fun isRunning(): Boolean = effectiveState.isRunningLike()
    fun isForeground(): Boolean = effectiveState == RescueProcessState.FOREGROUND
    fun isBackground(): Boolean = effectiveState == RescueProcessState.BACKGROUND
    fun isStopping(): Boolean = effectiveState == RescueProcessState.STOPPING
}

/** Cross-process state for the Android-local Rescue UI. Broadcasts are only cache invalidation. */
object RescueControlStateStore {
    private const val STATE_FILE_NAME = "openhouse_rescue_control.json"
    private const val KEY_STATE = "state"
    private const val KEY_PROCESS_NAME = "processName"
    private const val KEY_PID = "pid"
    private const val KEY_UPDATED_AT_MS = "updatedAtMs"

    @JvmStatic
    fun read(context: Context): RescueControlStateSnapshot {
        val persisted = readPersisted(context.applicationContext ?: context)
        val storedState = persisted.state
        val processName = persisted.processName
        val pid = persisted.pid
        val effectiveState =
            if (storedState.isRunningLike() && !isProcessAlive(context, pid, processName)) {
                RescueProcessState.NOT_RUNNING
            } else {
                storedState
            }
        return RescueControlStateSnapshot(
            storedState = storedState,
            effectiveState = effectiveState,
            processName = processName,
            pid = pid,
            updatedAtMs = persisted.updatedAtMs,
        )
    }

    @JvmStatic
    fun markForeground(context: Context) = mark(context, RescueProcessState.FOREGROUND)

    @JvmStatic
    fun markBackground(context: Context) = mark(context, RescueProcessState.BACKGROUND)

    @JvmStatic
    fun markStopping(context: Context) = mark(context, RescueProcessState.STOPPING)

    @JvmStatic
    fun markStopped(context: Context): RescueControlStateSnapshot {
        val appContext = context.applicationContext ?: context
        if (RescueControlProtocol.currentProcessName(appContext) !=
            RescueControlProtocol.processName(appContext.packageName)
        ) {
            return read(appContext)
        }
        val storedPid = readPersisted(appContext).pid
        if (storedPid > 0 && storedPid != Process.myPid()) return read(appContext)
        writePersisted(
            appContext,
            PersistedState(
                state = RescueProcessState.NOT_RUNNING,
                processName = "",
                pid = -1,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        sendStateChanged(appContext, RescueProcessState.NOT_RUNNING)
        return read(appContext)
    }

    private fun mark(context: Context, state: RescueProcessState): RescueControlStateSnapshot {
        val appContext = context.applicationContext ?: context
        if (RescueControlProtocol.currentProcessName(appContext) !=
            RescueControlProtocol.processName(appContext.packageName)
        ) {
            return read(appContext)
        }
        writePersisted(
            appContext,
            PersistedState(
                state = state,
                processName = RescueControlProtocol.currentProcessName(appContext),
                pid = Process.myPid(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        sendStateChanged(appContext, state)
        return read(appContext)
    }

    private fun isProcessAlive(context: Context, expectedPid: Int, expectedProcessName: String): Boolean {
        if (expectedPid <= 0 || expectedProcessName.isBlank()) return false
        if (expectedProcessName != RescueControlProtocol.processName(context.packageName)) return false
        val expectedUid = context.applicationInfo.uid
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.runningAppProcesses.orEmpty().any { process ->
            process.pid == expectedPid &&
                process.uid == expectedUid &&
                process.processName == expectedProcessName
        }
    }

    private fun sendStateChanged(context: Context, state: RescueProcessState) {
        runCatching {
            context.sendBroadcast(RescueControlProtocol.createStateChangedIntent(context, state))
        }
    }

    private fun readPersisted(context: Context): PersistedState = runCatching {
        val payload = String(stateFile(context).readFully(), Charsets.UTF_8)
        val json = JSONObject(payload)
        PersistedState(
            state = RescueProcessState.fromWireName(json.optString(KEY_STATE)),
            processName = json.optString(KEY_PROCESS_NAME),
            pid = json.optInt(KEY_PID, -1),
            updatedAtMs = json.optLong(KEY_UPDATED_AT_MS, 0L),
        )
    }.getOrElse { PersistedState() }

    private fun writePersisted(context: Context, state: PersistedState) {
        val file = stateFile(context)
        var output = file.startWrite()
        try {
            val payload = JSONObject()
                .put(KEY_STATE, state.state.wireName)
                .put(KEY_PROCESS_NAME, state.processName)
                .put(KEY_PID, state.pid)
                .put(KEY_UPDATED_AT_MS, state.updatedAtMs)
                .toString()
            output.write(payload.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun stateFile(context: Context): AtomicFile =
        AtomicFile(context.noBackupFilesDir.resolve(STATE_FILE_NAME))

    private data class PersistedState(
        val state: RescueProcessState = RescueProcessState.NOT_RUNNING,
        val processName: String = "",
        val pid: Int = -1,
        val updatedAtMs: Long = 0L,
    )
}
