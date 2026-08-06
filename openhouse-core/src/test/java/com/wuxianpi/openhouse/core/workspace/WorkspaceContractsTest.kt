package com.wuxianpi.openhouse.core.workspace

import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContractsTest {
    @Test
    fun httpUrlNormalizerOnlyAcceptsEmbeddableWebUrls() {
        assertEquals("http://127.0.0.1:20765/app", HttpUrlNormalizer.normalize(" http://127.0.0.1:20765/app "))
        assertEquals("https://example.test/a%2Fb", HttpUrlNormalizer.normalize("https://example.test/a%2Fb"))
        assertNull(HttpUrlNormalizer.normalize("javascript:alert(1)"))
        assertNull(HttpUrlNormalizer.normalize("file:///tmp/test"))
        assertNull(HttpUrlNormalizer.normalize("https://user:secret@example.test/"))
    }

    @Test
    fun serviceBackedResolverStartsAndRetriesUsingInjectedOperations() {
        val component = webComponent(
            id = "yuanshengwuxianpi",
            visible = true,
            serviceName = "yuanshengwuxianpi",
        )
        var lookups = 0
        var starts = 0
        val waits = mutableListOf<Long>()
        val resolver = ServiceBackedComponentEndpointResolver(
            lookupEndpoint = {
                lookups++
                if (lookups >= 3) ServiceEndpointStatus(true, "http://127.0.0.1:20765/")
                else ServiceEndpointStatus(false, message = "not ready")
            },
            startService = { starts++; true },
            retryPolicy = ComponentEndpointRetryPolicy(attemptsAfterStart = 4, delayMillis = 25),
            waitBeforeRetry = waits::add,
        )

        val result = resolver.resolve(component) as ComponentWebResolution.Resolved

        assertEquals("http://127.0.0.1:20765/", result.url)
        assertEquals("yuanshengwuxianpi", result.serviceId)
        assertEquals(3, lookups)
        assertEquals(1, starts)
        assertEquals(listOf(25L), waits)
    }

    @Test
    fun defaultRetryPolicyUsesBoundedExponentialBackoff() {
        val policy = ComponentEndpointRetryPolicy()

        assertEquals(
            listOf(0L, 250L, 500L, 1_000L, 2_000L, 4_000L),
            (0 until policy.attemptsAfterStart).map(policy::delayBeforeAttempt),
        )
        assertEquals(7_750L, (0 until policy.attemptsAfterStart).sumOf(policy::delayBeforeAttempt))
    }

    @Test
    fun serviceBackedResolverDoesNotUseManifestFallbackWhenServiceIsUnavailable() {
        val component = webComponent("agent", true, serviceName = "agent")
        val resolver = ServiceBackedComponentEndpointResolver(
            lookupEndpoint = { ServiceEndpointStatus(false, message = "offline") },
            retryPolicy = ComponentEndpointRetryPolicy(attemptsAfterStart = 0),
            waitBeforeRetry = {},
        )

        val result = resolver.resolve(component)

        assertTrue(result is ComponentWebResolution.Unavailable)
        assertFalse(result is ComponentWebResolution.Resolved)
    }

    @Test
    fun workspaceCatalogContainsSupportedDailyModesAndVisibleDynamicApps() {
        val visible = webComponent("manual", true)
        val hidden = webComponent("hidden", false)

        val entries = WorkspaceCatalog.applications(
            listOf(visible, hidden),
            HostCapabilities(true, false, true, true, true, true, true, true),
        )

        assertEquals(
            listOf(ProductRoute.BASIC, ProductRoute.REPAIR),
            entries.mapNotNull { (it.destination as? WorkspaceDestination.Route)?.route },
        )
        assertTrue(entries.any { it.destination == WorkspaceDestination.Component("manual") })
        assertFalse(entries.any { it.destination == WorkspaceDestination.Component("hidden") })
    }

    @Test
    fun workspaceCatalogDoesNotExposeAdvancedModeWhenAllCapabilitiesAreEnabled() {
        val routes = WorkspaceCatalog.applications(
            dynamicComponents = emptyList(),
            capabilities = HostCapabilities.full(),
        ).mapNotNull { (it.destination as? WorkspaceDestination.Route)?.route }

        assertEquals(listOf(ProductRoute.BASIC, ProductRoute.REPAIR), routes)
        assertFalse(ProductRoute.ADVANCED in routes)
    }

    @Test
    fun componentFingerprintChangesWhenRegistryContractChanges() {
        val original = webComponent("manual", true)
        val changed = OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(
                """
                {
                  "id":"manual",
                  "title":"Updated manual",
                  "visible":true,
                  "entry":{"type":"webview","url":"https://example.test/manual"}
                }
                """.trimIndent(),
            ),
            "test",
        )

        assertFalse(
            WorkspaceCatalog.componentFingerprint(original) ==
                WorkspaceCatalog.componentFingerprint(changed),
        )
    }

    private fun webComponent(
        id: String,
        visible: Boolean,
        serviceName: String = "",
    ): OpenHouseComponent {
        val control = if (serviceName.isEmpty()) "" else """
            ,"controlEntry":{"type":"service-control","serviceNames":["$serviceName"]}
        """.trimIndent()
        return OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(
                """
                {
                  "id":"$id",
                  "title":"$id",
                  "visible":$visible,
                  "entry":{"type":"webview","url":"https://example.test/$id"}
                  $control
                }
                """.trimIndent(),
            ),
            "test",
        )
    }
}
