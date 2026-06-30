package com.ai.assistance.operit.host.lifecycle

import android.content.Context

interface OperitHostLifecycleInitializer {
    val name: String

    suspend fun initialize(context: Context, environment: OperitHostLifecycleEnvironment)
}

fun interface OperitHostLifecycleCleanup {
    suspend fun cleanup(context: Context, environment: OperitHostLifecycleEnvironment)
}

data class OperitInitializerStatus(
    val name: String,
    val success: Boolean,
    val durationMs: Long,
    val error: String = ""
)

