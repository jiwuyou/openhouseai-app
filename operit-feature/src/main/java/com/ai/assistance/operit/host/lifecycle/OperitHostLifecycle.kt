package com.ai.assistance.operit.host.lifecycle

import android.app.Application
import android.content.Context
import android.util.Log
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.host.OperitHostContract
import com.ai.assistance.operit.host.OperitHostProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object OperitHostLifecycle {
    private const val TAG = "OperitHostLifecycle"

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initializers = linkedMapOf<String, OperitHostLifecycleInitializer>()
    private val cleanupTasks = mutableListOf<OperitHostLifecycleCleanup>()
    private val observers = mutableListOf<OperitLifecycleObserver>()
    private val lock = Any()

    @Volatile
    private var environment: OperitHostLifecycleEnvironment? = null

    fun registerInitializer(initializer: OperitHostLifecycleInitializer) {
        synchronized(lock) {
            initializers[initializer.name] = initializer
        }
    }

    fun registerCleanup(cleanup: OperitHostLifecycleCleanup) {
        synchronized(lock) {
            cleanupTasks += cleanup
        }
    }

    fun addObserver(observer: OperitLifecycleObserver) {
        synchronized(lock) {
            observers += observer
        }
    }

    fun removeObserver(observer: OperitLifecycleObserver) {
        synchronized(lock) {
            observers -= observer
        }
    }

    fun initialize(
        context: Context,
        host: OperitHostContract? = null,
        config: OperitHostLifecycleConfig = OperitHostLifecycleConfig()
    ): OperitHostLifecycleSnapshot {
        host?.let(OperitHostProvider::install)
        return OperitApplication.initializeMainApplication(context, config)
    }

    internal fun initializeRegisteredComponents(
        context: Context,
        environment: OperitHostLifecycleEnvironment,
        registerActivityLifecycleCallbacks: Boolean
    ): List<OperitInitializerStatus> {
        if (registerActivityLifecycleCallbacks) {
            resolveApplication(context)?.let(ActivityLifecycleManager::initialize)
                ?: Log.w(TAG, "Cannot register ActivityLifecycleCallbacks: application context is not an Application.")
        }

        val initializerSnapshot = synchronized(lock) {
            initializers.values.toList()
        }

        return initializerSnapshot.map { initializer ->
            val startedAt = System.currentTimeMillis()
            runCatching {
                runBlocking {
                    initializer.initialize(context, environment)
                }
            }.fold(
                onSuccess = {
                    OperitInitializerStatus(
                        name = initializer.name,
                        success = true,
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Initializer ${initializer.name} failed.", error)
                    OperitInitializerStatus(
                        name = initializer.name,
                        success = false,
                        durationMs = System.currentTimeMillis() - startedAt,
                        error = error.message ?: error.javaClass.name
                    )
                }
            )
        }
    }

    internal fun installEnvironment(newEnvironment: OperitHostLifecycleEnvironment) {
        environment = newEnvironment
    }

    internal fun dispatch(event: OperitLifecycleEvent, params: OperitLifecycleEventParams) {
        val observerSnapshot = synchronized(lock) {
            observers.toList()
        }
        observerSnapshot.forEach { observer ->
            runCatching { observer.onEvent(event, params) }
                .onFailure { Log.e(TAG, "Lifecycle observer failed for $event.", it) }
        }
    }

    fun dispatchAsync(event: OperitLifecycleEvent, params: OperitLifecycleEventParams) {
        lifecycleScope.launch {
            dispatch(event, params)
        }
    }

    fun terminate() {
        val activeEnvironment = environment ?: return
        dispatchAsync(
            event = OperitLifecycleEvent.APPLICATION_TERMINATE,
            params = OperitLifecycleEventParams(activeEnvironment.applicationContext)
        )

        val cleanupSnapshot = synchronized(lock) {
            cleanupTasks.toList()
        }
        cleanupSnapshot.forEach { cleanup ->
            lifecycleScope.launch {
                runCatching {
                    cleanup.cleanup(activeEnvironment.applicationContext, activeEnvironment)
                }.onFailure {
                    Log.e(TAG, "Lifecycle cleanup failed.", it)
                }
            }
        }
    }

    fun snapshot(): OperitHostLifecycleSnapshot =
        OperitApplication.snapshot()

    internal fun registeredInitializerNames(): List<String> =
        synchronized(lock) {
            initializers.keys.toList()
        }

    internal fun observerCount(): Int =
        synchronized(lock) {
            observers.size
        }

    internal fun cleanupCount(): Int =
        synchronized(lock) {
            cleanupTasks.size
        }

    private fun resolveApplication(context: Context): Application? =
        when (val appContext = context.applicationContext) {
            is Application -> appContext
            else -> context as? Application
        }
}

