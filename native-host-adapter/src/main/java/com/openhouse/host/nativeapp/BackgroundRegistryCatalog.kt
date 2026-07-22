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

    @Volatile
    private var snapshot: List<OpenHouseComponent> = OpenHouseBuiltins.components()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        startTask {
            val loaded = runCatching(loadComponents).getOrDefault(emptyList())
            if (loaded.isNotEmpty()) snapshot = loaded.toList()
        }
    }

    fun components(): List<OpenHouseComponent> {
        start()
        return snapshot
    }
}
