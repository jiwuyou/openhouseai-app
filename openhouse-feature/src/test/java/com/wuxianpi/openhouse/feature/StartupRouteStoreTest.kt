package com.wuxianpi.openhouse.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.StartupTarget
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartupRouteStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StartupRouteStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun resolvesConfiguredRoutesAndLastRoute() {
        val store = StartupRouteStore(context)
        store.setTarget(StartupTarget.BASIC)
        assertEquals(ProductRoute.BASIC, store.resolve())

        store.recordLast(ProductRoute.ADVANCED)
        store.setTarget(StartupTarget.LAST_PAGE)
        assertEquals(ProductRoute.ADVANCED, store.resolve())
    }

    @Test
    fun persistsOnlyCurrentStartupTargetSchema() {
        val store = StartupRouteStore(context)
        store.setTarget(StartupTarget.ADVANCED)
        assertEquals(StartupTarget.ADVANCED, StartupRouteStore(context).target())
    }
}
