package com.wuxianpi.openhouse.feature.workspace

import android.content.ComponentCallbacks2
import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.feature.ComponentWebLaunchArgs
import com.wuxianpi.openhouse.feature.ComponentWebTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedWebPagePoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun retainsTwoRecentPagesAndDropsInactivePagesUnderMemoryPressure() {
        val pool = EmbeddedWebPagePool(context, object : EmbeddedWebPagePool.Callbacks {})
        val host = FrameLayout(context)

        pool.show(args("one"), host)
        pool.show(args("two"), host)
        pool.show(args("three"), host)

        assertEquals(2, pool.retainedPageCount)
        pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(1, pool.retainedPageCount)

        pool.destroy()
        assertEquals(0, pool.retainedPageCount)
    }

    @Test
    fun changedResolvedUrlReplacesOlderPageForSameComponent() {
        val pool = EmbeddedWebPagePool(context, object : EmbeddedWebPagePool.Callbacks {})
        val host = FrameLayout(context)

        pool.show(args("agent", "one"), host)
        pool.show(args("agent", "two"), host)

        assertEquals(1, pool.retainedPageCount)
        assertEquals("https://example.test/two", pool.activeAddress)
        pool.destroy()
    }

    @Test
    fun changedCatalogFingerprintReplacesSameComponentAndUrl() {
        val pool = EmbeddedWebPagePool(context, object : EmbeddedWebPagePool.Callbacks {})
        val host = FrameLayout(context)

        pool.show(args("agent", fingerprint = "revision-one"), host)
        val firstGeneration = pool.activeLoadGeneration
        pool.show(args("agent", fingerprint = "revision-two"), host)

        assertEquals(1, pool.retainedPageCount)
        assertEquals(1L, firstGeneration)
        assertEquals(1L, pool.activeLoadGeneration)
        pool.reloadActive()
        assertEquals(2L, pool.activeLoadGeneration)
        assertEquals(false, pool.acceptsActiveLoadGeneration(firstGeneration))
        assertEquals(true, pool.acceptsActiveLoadGeneration(pool.activeLoadGeneration))
        pool.destroy()
    }

    @Test
    fun tabsReuseOneWebViewPageRecord() {
        val pool = EmbeddedWebPagePool(context, object : EmbeddedWebPagePool.Callbacks {})
        val host = FrameLayout(context)
        val args = args("deepseek").copy(
            tabs = listOf(
                ComponentWebTab("API 密钥", "https://example.test/keys"),
                ComponentWebTab("用量", "https://example.test/usage"),
            ),
        )

        pool.show(args, host)
        val generation = pool.activeLoadGeneration
        assertEquals(2, pool.activeTabCount())
        assertTrue(pool.selectActiveTab(1))
        assertEquals(1, pool.retainedPageCount)
        assertEquals(generation, pool.activeLoadGeneration)
        assertEquals("https://example.test/usage", pool.activeAddress)

        pool.destroy()
    }

    @Test
    fun remembersPreviousAddressWhenLoadingASecondUrl() {
        val pool = EmbeddedWebPagePool(context, object : EmbeddedWebPagePool.Callbacks {})
        val host = FrameLayout(context)

        pool.show(args("agent", "conversation"), host)
        pool.loadActiveUrl("https://example.test/source")

        assertEquals("https://example.test/conversation", pool.previousAddress)
        assertEquals("https://example.test/source", pool.activeAddress)
        pool.destroy()
    }

    private fun args(
        id: String,
        path: String = id,
        fingerprint: String = "revision-$id",
    ) = ComponentWebLaunchArgs(
        componentId = id,
        title = id,
        fallbackUrl = "",
        resolvedUrl = "https://example.test/$path",
        controlTitle = "",
        serviceNames = emptyList(),
        serviceRefs = emptyList(),
        catalogFingerprint = fingerprint,
    )
}
