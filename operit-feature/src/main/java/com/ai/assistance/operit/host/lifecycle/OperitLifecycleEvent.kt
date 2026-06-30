package com.ai.assistance.operit.host.lifecycle

import android.content.Context

enum class OperitLifecycleEvent {
    APPLICATION_CREATE,
    APPLICATION_INITIALIZED,
    APPLICATION_FOREGROUND,
    APPLICATION_BACKGROUND,
    APPLICATION_TERMINATE,
    APPLICATION_LOW_MEMORY,
    APPLICATION_TRIM_MEMORY,
    ACTIVITY_CREATE,
    ACTIVITY_START,
    ACTIVITY_RESUME,
    ACTIVITY_PAUSE,
    ACTIVITY_STOP,
    ACTIVITY_DESTROY
}

data class OperitLifecycleEventParams(
    val context: Context,
    val extras: Map<String, Any?> = emptyMap()
)

fun interface OperitLifecycleObserver {
    fun onEvent(event: OperitLifecycleEvent, params: OperitLifecycleEventParams)
}

