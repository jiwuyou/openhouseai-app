package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.feature.StartupRouteStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspacePreferenceStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StartupRouteStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun userOverrideCanPinAndUnpinManifestDefaults() {
        val favorite = component("favorite", true)
        val regular = component("regular", false)
        val store = WorkspacePreferenceStore(context)

        assertTrue(store.isPinned(favorite))
        assertFalse(store.isPinned(regular))

        store.setPinned(favorite, false)
        store.setPinned(regular, true)

        assertFalse(WorkspacePreferenceStore(context).isPinned(favorite))
        assertTrue(WorkspacePreferenceStore(context).isPinned(regular))
    }

    @Test
    fun homeDoesNotImplicitlyPinSidebarEntry() {
        val store = WorkspacePreferenceStore(context)

        assertFalse(store.isPinned(component("home-only", favorite = false, home = true)))
    }

    private fun component(id: String, favorite: Boolean, home: Boolean = false) = OpenHouseComponentParser().parse(
        RegistryManifest.fromManifestJson(
            """{
                "id":"$id",
                "title":"$id",
                "favorite":$favorite,
                "home":$home,
                "entry":{"type":"webview","url":"http://127.0.0.1:9000/"}
            }""".trimIndent(),
        ),
        "test",
    )
}
