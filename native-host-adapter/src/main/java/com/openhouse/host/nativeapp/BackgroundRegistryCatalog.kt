package com.openhouse.host.nativeapp

import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps registry network and disk access off the OpenHouse UI thread. */
internal class BackgroundRegistryCatalog(
    private val loadComponents: () -> List<OpenHouseComponent>,
    private val startTask: ((() -> Unit) -> Unit) = { task ->
        Thread(task, "native-openhouse-registry").apply {
            isDaemon = true
            start()
        }
    },
) {
    private val started = AtomicBoolean(false)
    private val callbackLock = Any()
    private val pendingCallbacks = mutableListOf<() -> Unit>()
    private var refreshing = false
    private var reloadRequested = false

    @Volatile
    private var snapshot: List<OpenHouseComponent> = OpenHouseBuiltins.components()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        refresh()
    }

    /** Reloads the registry without blocking callers or replacing a good snapshot on failure. */
    fun refresh(onComplete: () -> Unit = {}) {
        val shouldStart = synchronized(callbackLock) {
            started.set(true)
            pendingCallbacks += onComplete
            if (refreshing) {
                reloadRequested = true
                false
            } else {
                refreshing = true
                true
            }
        }
        if (!shouldStart) return
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        startTask {
            runCatching(loadComponents).getOrNull()?.let { loaded ->
                snapshot = loaded.toList()
            }
            val (callbacks, repeat) = synchronized(callbackLock) {
                if (reloadRequested) {
                    reloadRequested = false
                    emptyList<() -> Unit>() to true
                } else {
                    refreshing = false
                    pendingCallbacks.toList().also { pendingCallbacks.clear() } to false
                }
            }
            if (repeat) scheduleRefresh()
            callbacks.forEach { callback -> runCatching(callback) }
        }
    }

    fun components(): List<OpenHouseComponent> {
        start()
        return snapshot
    }
}
