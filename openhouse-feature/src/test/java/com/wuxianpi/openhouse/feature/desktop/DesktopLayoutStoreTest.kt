package com.wuxianpi.openhouse.feature.desktop

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.feature.StartupRouteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DesktopLayoutStoreTest {
    private lateinit var context: Context
    private lateinit var components: List<DesktopComponent>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StartupRouteStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val manifest = RegistryManifest.fromManifestJson(
            """{"id":"dynamic-app","title":"Dynamic","entry":{"type":"webview","url":"http://127.0.0.1:9000/"},"desktop":{"visible":true,"order":100}}""",
        )
        components = DesktopCatalog.merge(listOf(requireNotNull(OpenHouseComponentParser().parse(manifest, "test"))))
    }

    @Test
    fun persistsNewLayoutSchemaWithRenameHideAndCurrentPage() {
        val store = DesktopLayoutStore(context)
        store.updateOverride(components, "dynamic-app", "Renamed", DesktopIconOverride(label = "R"))
        store.hide(components, "dynamic-app", true)
        store.moveToSlot(components, "dynamic-app", 24)
        store.saveCurrentPage(components, 2)

        val state = DesktopLayoutStore(context).load(components)
        assertEquals(2, state.currentPage)
        assertEquals("Renamed", state.find("dynamic-app")?.title)
        assertTrue(state.find("dynamic-app")?.hidden == true)
    }

    @Test
    fun supportsSparseMoveAndProtectsFixedEntriesFromHide() {
        val store = DesktopLayoutStore(context)
        var state = store.moveToSlot(components, "dynamic-app", 24)
        assertEquals(24, state.find("dynamic-app")?.slotIndex)

        state = store.hide(components, "dynamic-app", true)
        assertTrue(state.find("dynamic-app")?.hidden == true)

        state = store.hide(components, DesktopCatalog.ID_BASIC, true)
        assertFalse(state.find(DesktopCatalog.ID_BASIC)?.hidden == true)
    }
}
