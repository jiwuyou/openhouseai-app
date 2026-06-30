package com.ai.assistance.operit.host.lifecycle

import java.util.Locale

data class OperitHostLifecycleConfig(
    val locale: Locale? = null,
    val installUncaughtExceptionHandler: Boolean = true,
    val initializeWorkManager: Boolean = true,
    val initializeImageLoader: Boolean = true,
    val cleanOnExitOnStartup: Boolean = true,
    val registerActivityLifecycleCallbacks: Boolean = true,
    val keepScreenOnEnabledByDefault: Boolean = true,
    val imageCacheMaxBytes: Long = 50L * 1024L * 1024L,
    val imageMemoryCachePercent: Double = 0.15,
    val cleanOnExitExternalDirName: String = "Operit/cleanOnExit",
    val cleanOnExitCacheDirName: String = "Operit/cleanOnExit"
)

