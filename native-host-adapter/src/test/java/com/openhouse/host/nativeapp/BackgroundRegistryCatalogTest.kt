package com.openhouse.host.nativeapp

import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundRegistryCatalogTest {
    @Test
    fun refreshReplacesSnapshotAndInvokesCallback() {
        var source = OpenHouseBuiltins.components().take(1)
        val catalog = BackgroundRegistryCatalog(
            loadComponents = { source },
            startTask = { task -> task() },
        )

        catalog.start()
        source = OpenHouseBuiltins.components().take(2)
        var callbacks = 0
        catalog.refresh { callbacks++ }

        assertEquals(2, catalog.components().size)
        assertEquals(1, callbacks)
    }

    @Test
    fun failedRefreshKeepsPreviousSnapshot() {
        var fail = false
        val expected = OpenHouseBuiltins.components().take(1)
        val catalog = BackgroundRegistryCatalog(
            loadComponents = {
                if (fail) error("registry unavailable")
                expected
            },
            startTask = { task -> task() },
        )

        catalog.start()
        fail = true
        catalog.refresh()

        assertEquals(expected, catalog.components())
    }

    @Test
    fun refreshRequestedDuringLoadRunsAgainBeforeCallbacks() {
        val tasks = mutableListOf<() -> Unit>()
        var loads = 0
        val latest = OpenHouseBuiltins.components().take(2)
        val catalog = BackgroundRegistryCatalog(
            loadComponents = {
                loads++
                if (loads == 1) OpenHouseBuiltins.components().take(1) else latest
            },
            startTask = { task -> tasks += task },
        )

        catalog.start()
        var callbacks = 0
        catalog.refresh { callbacks++ }

        assertEquals(1, tasks.size)
        tasks.removeAt(0).invoke()
        assertEquals(1, tasks.size)
        assertEquals(0, callbacks)

        tasks.removeAt(0).invoke()
        assertEquals(2, loads)
        assertEquals(1, callbacks)
        assertEquals(latest, catalog.components())
    }
}
