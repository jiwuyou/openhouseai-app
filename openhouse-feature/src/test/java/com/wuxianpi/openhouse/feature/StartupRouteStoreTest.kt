package com.wuxianpi.openhouse.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.StartupTarget
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
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

    @Test
    fun persistsDynamicComponentAsHomeAndFallsBackWhenMissing() {
        val store = StartupRouteStore(context)
        val component = component("notes", home = false)
        store.setHomeDestination(WorkspaceDestination.Component("notes"))

        assertEquals(
            WorkspaceDestination.Component("notes"),
            StartupRouteStore(context).resolveDestination(listOf(component)),
        )
        assertEquals(
            WorkspaceDestination.Desktop,
            StartupRouteStore(context).resolveDestination(emptyList()),
        )
    }

    @Test
    fun automaticSelectionUsesManifestHomeAndLastPageSupportsComponents() {
        val store = StartupRouteStore(context)
        val component = component("notes", home = true)

        assertEquals(WorkspaceDestination.Component("notes"), store.resolveDestination(listOf(component)))

        store.setTarget(StartupTarget.LAST_PAGE)
        store.recordLast(WorkspaceDestination.Component("notes"))
        assertEquals(WorkspaceDestination.Component("notes"), store.resolveDestination(listOf(component)))
    }

    private fun component(id: String, home: Boolean) = OpenHouseComponentParser().parse(
        RegistryManifest.fromManifestJson(
            """{
                "id":"$id",
                "title":"Notes",
                "home":$home,
                "entry":{"type":"webview","url":"http://127.0.0.1:9000/"}
            }""".trimIndent(),
        ),
        "test",
    )
}
