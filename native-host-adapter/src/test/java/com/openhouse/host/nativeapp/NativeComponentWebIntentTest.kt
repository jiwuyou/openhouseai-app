package com.openhouse.host.nativeapp

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.feature.ComponentWebLaunchArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeComponentWebIntentTest {
    @Test
    fun webComponentUsesInternalActivityInsteadOfActionView() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(
                """
                {
                  "id": "yuanshengwuxianpi",
                  "entry": {"type": "webview", "url": "http://127.0.0.1:23110/"},
                  "controlEntry": {
                    "type": "service-control",
                    "serviceNames": ["yuanshengwuxianpi"]
                  }
                }
                """.trimIndent(),
            ),
            "test",
        )

        val intent = createNativeComponentWebIntent(
            context,
            component,
            "http://127.0.0.1:21444/",
        )

        assertNotEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "com.wuxianpi.openhouse.feature.OpenHouseComponentWebActivity",
            intent.component?.className,
        )
    }

    @Test
    fun componentControlRequestIsScopedToCurrentWebComponentServices() {
        val request = serviceControlRequestFor(
            ComponentWebLaunchArgs(
                componentId = "wuxianpi-web",
                title = "WuxianPi",
                fallbackUrl = "http://127.0.0.1:23110/",
                resolvedUrl = "http://127.0.0.1:21444/",
                controlTitle = "WuxianPi Control",
                serviceNames = listOf("yuanshengwuxianpi"),
                serviceRefs = listOf("service-manager://services/wuxianpi-worker"),
            ),
        )

        assertEquals("WuxianPi Control", request.title)
        assertEquals("wuxianpi-web", request.componentId)
        assertEquals("http://127.0.0.1:21444/", request.componentEndpoint)
        assertEquals(listOf("yuanshengwuxianpi", "wuxianpi-worker"), request.serviceIds)
        assertFalse(request.showAllServices)
    }
}
