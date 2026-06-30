package com.ai.assistance.operit.host.lifecycle

import android.content.Context
import coil.ImageLoader
import com.ai.assistance.operit.host.OperitHostContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

class OperitHostLifecycleEnvironment internal constructor(
    val applicationContext: Context,
    val host: OperitHostContract?,
    val applicationScope: CoroutineScope,
    val json: Json,
    val imageLoader: ImageLoader,
    val startupTimeMs: Long,
    val config: OperitHostLifecycleConfig
)

data class OperitHostLifecycleSnapshot(
    val initialized: Boolean,
    val startupTimeMs: Long,
    val mainInitialized: Boolean,
    val hostInstalled: Boolean,
    val registeredInitializers: List<String>,
    val initializerStatuses: List<OperitInitializerStatus>,
    val observers: Int,
    val cleanups: Int
)

