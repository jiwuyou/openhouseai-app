package com.ai.assistance.operit.core.application

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.system.Os
import android.util.Log
import androidx.core.os.LocaleListCompat
import androidx.work.Configuration as WorkConfiguration
import androidx.work.WorkManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycle
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycleConfig
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycleEnvironment
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycleSnapshot
import com.ai.assistance.operit.host.lifecycle.OperitInitializerStatus
import com.ai.assistance.operit.host.lifecycle.OperitLifecycleEvent
import com.ai.assistance.operit.host.lifecycle.OperitLifecycleEventParams
import com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitRuntimeContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

object OperitApplication {
    private const val TAG = "OperitApplication"

    @Volatile
    var appStartupTimeMs: Long = 0L
        private set

    val instance: Context
        get() = applicationContextOrThrow()

    lateinit var json: Json
        private set

    lateinit var globalImageLoader: ImageLoader
        private set

    val workManagerConfiguration: WorkConfiguration
        get() =
            WorkConfiguration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainInitializationLock = Any()

    @Volatile
    private var mainApplicationInitialized = false

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var config: OperitHostLifecycleConfig = OperitHostLifecycleConfig()

    @Volatile
    private var initializerStatuses: List<OperitInitializerStatus> = emptyList()

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    fun initializeMainApplication(
        context: Context,
        lifecycleConfig: OperitHostLifecycleConfig = OperitHostLifecycleConfig()
    ): OperitHostLifecycleSnapshot {
        synchronized(mainInitializationLock) {
            if (mainApplicationInitialized) {
                return snapshot()
            }
            val appContext = context.applicationContext
            applicationContext = appContext
            config = lifecycleConfig
            appStartupTimeMs = System.currentTimeMillis()
            OperitRuntimeContext.bind(appContext)
            AppLogger.bindContext(appContext)
            AIMessageManager.initialize(appContext)

            configureOpenMpEnvironment()
            initializeJson()
            if (lifecycleConfig.installUncaughtExceptionHandler) {
                installUncaughtExceptionHandler()
            }
            if (lifecycleConfig.initializeWorkManager) {
                ensureWorkManagerInitialized(appContext)
            }
            if (lifecycleConfig.initializeImageLoader) {
                globalImageLoader = buildImageLoader(appContext, lifecycleConfig)
            } else {
                globalImageLoader = ImageLoader.Builder(appContext).build()
            }

            initAndroidPermissionPreferences(appContext)

            val environment =
                OperitHostLifecycleEnvironment(
                    applicationContext = appContext,
                    host = OperitHostProvider.currentOrNull(),
                    applicationScope = applicationScope,
                    json = json,
                    imageLoader = globalImageLoader,
                    startupTimeMs = appStartupTimeMs,
                    config = lifecycleConfig
                )
            OperitHostLifecycle.installEnvironment(environment)

            if (lifecycleConfig.cleanOnExitOnStartup) {
                launchCleanOnExitCleanup(appContext, lifecycleConfig)
            }

            initializerStatuses =
                OperitHostLifecycle.initializeRegisteredComponents(
                    context = appContext,
                    environment = environment,
                    registerActivityLifecycleCallbacks = lifecycleConfig.registerActivityLifecycleCallbacks
                )

            mainApplicationInitialized = true
            OperitHostLifecycle.dispatchAsync(
                event = OperitLifecycleEvent.APPLICATION_CREATE,
                params = OperitLifecycleEventParams(appContext, mapOf("startupTimeMs" to appStartupTimeMs))
            )
            OperitHostLifecycle.dispatchAsync(
                event = OperitLifecycleEvent.APPLICATION_INITIALIZED,
                params = OperitLifecycleEventParams(appContext, mapOf("startupTimeMs" to appStartupTimeMs))
            )
            return snapshot()
        }
    }

    fun initializeMainApplication(): OperitHostLifecycleSnapshot =
        initializeMainApplication(applicationContextOrThrow(), config)

    fun attachBaseContext(base: Context, lifecycleConfig: OperitHostLifecycleConfig = config): Context {
        configureOpenMpEnvironment()
        val locale = lifecycleConfig.locale ?: return base
        return try {
            Locale.setDefault(locale)
            val configuration = Configuration(base.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val localeList = LocaleList(locale)
                LocaleList.setDefault(localeList)
                configuration.setLocales(localeList)
            } else {
                @Suppress("DEPRECATION")
                configuration.locale = locale
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
            }
            base.createConfigurationContext(configuration)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply Operit base context locale.", e)
            base
        }
    }

    fun onTerminate() {
        if (!mainApplicationInitialized) return
        OperitHostLifecycle.terminate()
    }

    fun onLowMemory() {
        if (!mainApplicationInitialized) return
        val appContext = applicationContext ?: return
        OperitHostLifecycle.dispatchAsync(
            event = OperitLifecycleEvent.APPLICATION_LOW_MEMORY,
            params = OperitLifecycleEventParams(appContext)
        )
    }

    fun onTrimMemory(level: Int) {
        if (!mainApplicationInitialized) return
        val appContext = applicationContext ?: return
        OperitHostLifecycle.dispatchAsync(
            event = OperitLifecycleEvent.APPLICATION_TRIM_MEMORY,
            params = OperitLifecycleEventParams(appContext, mapOf("level" to level))
        )
    }

    fun newImageLoader(): ImageLoader =
        globalImageLoader

    fun currentConfig(): OperitHostLifecycleConfig =
        config

    fun snapshot(): OperitHostLifecycleSnapshot =
        OperitHostLifecycleSnapshot(
            initialized = applicationContext != null,
            startupTimeMs = appStartupTimeMs,
            mainInitialized = mainApplicationInitialized,
            hostInstalled = OperitHostProvider.currentOrNull() != null,
            registeredInitializers = OperitHostLifecycle.registeredInitializerNames(),
            initializerStatuses = initializerStatuses,
            observers = OperitHostLifecycle.observerCount(),
            cleanups = OperitHostLifecycle.cleanupCount()
        )

    private fun applicationContextOrThrow(): Context =
        applicationContext
            ?: OperitHostProvider.currentOrNull()?.applicationContext
            ?: error("OperitApplication is not initialized. Call OperitHostLifecycle.initialize(...) from SmallPhoneAI.")

    private fun configureOpenMpEnvironment() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Os.setenv("KMP_AFFINITY", "disabled", true)
                Os.setenv("OMP_PROC_BIND", "false", true)
            }
        } catch (_: Throwable) {
        }
    }

    private fun initializeJson() {
        json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
                encodeDefaults = true
            }
    }

    private fun installUncaughtExceptionHandler() {
        if (previousUncaughtExceptionHandler != null) return
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in ${thread.name}.", throwable)
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun ensureWorkManagerInitialized(context: Context) {
        try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            try {
                WorkManager.initialize(context, workManagerConfiguration)
            } catch (_: IllegalStateException) {
            }
        }
    }

    private fun buildImageLoader(context: Context, lifecycleConfig: OperitHostLifecycleConfig): ImageLoader {
        val imageOkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        return ImageLoader.Builder(context)
            .okHttpClient(imageOkHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .respectCacheHeaders(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "operit-image-cache"))
                    .maxSizeBytes(lifecycleConfig.imageCacheMaxBytes)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(lifecycleConfig.imageMemoryCachePercent)
                    .build()
            }
            .build()
    }

    private fun launchCleanOnExitCleanup(context: Context, lifecycleConfig: OperitHostLifecycleConfig) {
        applicationScope.launch {
            runCatching {
                val externalRoot = context.getExternalFilesDir(null)
                val deletedFiles =
                    listOfNotNull(
                        externalRoot?.let { File(it, lifecycleConfig.cleanOnExitExternalDirName) },
                        File(context.cacheDir, lifecycleConfig.cleanOnExitCacheDirName)
                    ).sumOf { cleanDirectory(it, preserveRootNoMedia = it.parentFile == externalRoot) }
                Log.d(TAG, "cleanOnExit cleanup deleted $deletedFiles files.")
            }.onFailure {
                Log.e(TAG, "Failed to clean Operit temporary files.", it)
            }
        }
    }

    private fun cleanDirectory(tempDir: File, preserveRootNoMedia: Boolean): Int {
        if (!tempDir.exists() || !tempDir.isDirectory) {
            return 0
        }
        if (preserveRootNoMedia) {
            val noMediaFile = File(tempDir, ".nomedia")
            if (!noMediaFile.exists()) {
                noMediaFile.createNewFile()
            }
        }
        return deleteRecursively(rootDir = tempDir, file = tempDir, preserveRootNoMedia = preserveRootNoMedia, isRoot = true)
    }

    private fun deleteRecursively(
        rootDir: File,
        file: File,
        preserveRootNoMedia: Boolean,
        isRoot: Boolean = false
    ): Int {
        var deletedCount = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deletedCount += deleteRecursively(rootDir, child, preserveRootNoMedia, false)
            }
            if (!isRoot && file.exists()) {
                file.delete()
            }
        } else if (file.isFile) {
            val isRootNoMedia =
                preserveRootNoMedia &&
                    file.parentFile?.absolutePath == rootDir.absolutePath &&
                    file.name == ".nomedia"
            if (!isRootNoMedia && file.delete()) {
                deletedCount += 1
            }
        }
        return deletedCount
    }
}
