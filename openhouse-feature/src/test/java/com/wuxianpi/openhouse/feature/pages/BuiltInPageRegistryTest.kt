package com.wuxianpi.openhouse.feature.pages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuiltInPageRegistryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledDeepSeekPageIsHiddenButLaunchableWithFourTabs() {
        val registry = BuiltInPageRegistry(context)
        val page = registry.components().first { it.id == "openhouse.api-keys" }

        assertTrue(page.visible)
        assertFalse(page.desktopVisible)
        assertEquals(4, registry.tabsFor(page.id).size)
        assertEquals("API 密钥", registry.tabsFor(page.id).first().title)
        assertTrue(registry.tabsFor(page.id).first().url.contains("platform.deepseek.com"))
        registry.close()
    }

    @Test
    fun ordinaryBundledPagesHaveNoTabBar() {
        val registry = BuiltInPageRegistry(context)
        val page = registry.components().first { it.id == "openhouse.platform" }

        assertTrue(registry.tabsFor(page.id).isEmpty())
        registry.close()
    }
}
