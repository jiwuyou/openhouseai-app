package com.wuxianpi.openhouse.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentWebLaunchArgsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun createIntentCarriesResolvedUrlAndComponentControlMetadata() {
        val component = componentWithControl()
        val intent = OpenHouseFeature.createComponentWebIntent(
            context,
            component,
            "http://127.0.0.1:20765/",
        )

        val args = requireNotNull(ComponentWebLaunchArgs.fromIntent(intent))
        assertEquals("yuanshengwuxianpi", args.componentId)
        assertEquals("WuxianPi", args.title)
        assertEquals("http://127.0.0.1:8765/", args.fallbackUrl)
        assertEquals("http://127.0.0.1:20765/", args.loadUrl)
        assertEquals("运行控制", args.controlTitle)
        assertEquals(listOf("yuanshengwuxianpi"), args.serviceNames)
        assertEquals(
            listOf("service-manager://services/yuanshengwuxianpi"),
            args.serviceRefs,
        )
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    }

    @Test
    fun serviceBackedComponentDoesNotUseManifestUrlWhenResolutionIsMissing() {
        val args = ComponentWebLaunchArgs.from(componentWithControl(), "")
        assertEquals("", args.loadUrl)
    }

    @Test
    fun ordinaryWebComponentCanUseItsManifestUrl() {
        val args = ComponentWebLaunchArgs(
            componentId = "manual",
            title = "手册",
            fallbackUrl = "https://example.com/manual",
            resolvedUrl = "",
            controlTitle = "",
            serviceNames = emptyList(),
            serviceRefs = emptyList(),
        )
        assertEquals("https://example.com/manual", args.loadUrl)
    }

    @Test
    fun pageStateTracksLoadingConnectedAndFailure() {
        val initial = ComponentWebPageState("http://127.0.0.1:20765/")
        assertEquals(ComponentWebLoadPhase.IDLE, initial.phase)
        assertEquals(ComponentWebLoadPhase.LOADING, initial.loading().phase)
        assertEquals(ComponentWebLoadPhase.CONNECTED, initial.connected().phase)
        assertEquals(ComponentWebLoadPhase.FAILED, initial.failed().phase)
    }

    private fun componentWithControl() = OpenHouseComponentParser().parse(
        RegistryManifest.fromManifestJson(
            """
            {
              "id": "yuanshengwuxianpi",
              "title": "WuxianPi",
              "entry": {
                "type": "webview",
                "url": "http://127.0.0.1:8765/"
              },
              "controlEntry": {
                "type": "service-control",
                "title": "运行控制",
                "serviceNames": ["yuanshengwuxianpi"],
                "serviceRefs": ["service-manager://services/yuanshengwuxianpi"]
              }
            }
            """.trimIndent(),
        ),
        "test",
    )
}
