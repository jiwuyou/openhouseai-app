package com.openhouse.host.nativeapp

import com.wuxianpi.openhouse.core.RuntimeConnection
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.service.HttpRequestSpec
import com.wuxianpi.openhouse.core.service.HttpResponseSpec
import com.wuxianpi.openhouse.core.service.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeComponentEndpointResolverTest {
    @Test
    fun runtimeResolverQueriesEndpointApiAndPrefersApiEndpoint() {
        val transport = RecordingTransport(
            HttpResponseSpec(
                200,
                """
                {
                  "serviceId": "yuanshengwuxianpi",
                  "status": "healthy",
                  "endpoints": [
                    {"name": "runtime", "url": "http://127.0.0.1:21910/"},
                    {"name": "api", "url": "http://127.0.0.1:21444/"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val resolver = NativeComponentEndpointResolver.fromRuntimeConnection(
            runtimeConnection = {
                RuntimeConnection("http://127.0.0.1:20087", "token", "")
            },
            transport = transport,
        )

        val result = resolver.resolve(
            component(serviceNames = listOf("yuanshengwuxianpi")),
        ) as NativeComponentEndpointResult.Resolved

        assertEquals(
            "/api/v1/services/yuanshengwuxianpi/endpoints",
            transport.request?.path,
        )
        assertTrue(transport.request?.authenticated == true)
        assertEquals("http://127.0.0.1:21444/", result.url)
    }

    @Test
    fun endpointApiCanBuildUrlFromHostAndDynamicPort() {
        val transport = RecordingTransport(
            HttpResponseSpec(
                200,
                """
                {
                  "status": "healthy",
                  "endpoints": [
                    {"name": "api", "host": "0.0.0.0", "port": 21910, "protocol": "tcp"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val resolver = NativeComponentEndpointResolver.fromRuntimeConnection(
            runtimeConnection = {
                RuntimeConnection("http://127.0.0.1:20087", "token", "")
            },
            transport = transport,
        )

        val result = resolver.resolve(
            component(serviceRefs = listOf("service-manager://services/yuanshengwuxianpi")),
        ) as NativeComponentEndpointResult.Resolved

        assertEquals("http://127.0.0.1:21910", result.url)
    }

    @Test
    fun serviceBackedComponentUsesPublishedDynamicUrl() {
        val requestedIds = mutableListOf<String>()
        val resolver = NativeComponentEndpointResolver { serviceId ->
            requestedIds += serviceId
            NativeServiceEndpointStatus(
                success = true,
                url = "http://127.0.0.1:21444/",
                message = "ready",
            )
        }

        val result = resolver.resolve(
            component(serviceNames = listOf("yuanshengwuxianpi")),
        )

        assertEquals(listOf("yuanshengwuxianpi"), requestedIds)
        assertTrue(result is NativeComponentEndpointResult.Resolved)
        result as NativeComponentEndpointResult.Resolved
        assertEquals("http://127.0.0.1:21444/", result.url)
        assertEquals("yuanshengwuxianpi", result.serviceId)
    }

    @Test
    fun serviceReferenceIsResolvedAsServiceId() {
        val requestedIds = mutableListOf<String>()
        val resolver = NativeComponentEndpointResolver { serviceId ->
            requestedIds += serviceId
            NativeServiceEndpointStatus(true, "http://127.0.0.1:21910/", "ready")
        }

        resolver.resolve(
            component(serviceRefs = listOf("service-manager://services/yuanshengwuxianpi")),
        )

        assertEquals(listOf("yuanshengwuxianpi"), requestedIds)
    }

    @Test
    fun unavailableServiceDoesNotFallBackToManifestUrl() {
        val resolver = NativeComponentEndpointResolver {
            NativeServiceEndpointStatus(false, "", "service is stopped")
        }

        val result = resolver.resolve(
            component(
                manifestUrl = "http://127.0.0.1:23110/",
                serviceNames = listOf("yuanshengwuxianpi"),
            ),
        )

        assertTrue(result is NativeComponentEndpointResult.Unavailable)
        result as NativeComponentEndpointResult.Unavailable
        assertTrue(result.message.contains("yuanshengwuxianpi"))
        assertFalse(result.message.contains("23110"))
    }

    @Test
    fun stoppedServiceIsStartedAndResolvedAgain() {
        var lookups = 0
        var starts = 0
        val resolver = NativeComponentEndpointResolver(
            lookupEndpoint = {
                lookups += 1
                if (lookups == 1) {
                    NativeServiceEndpointStatus(false, "", "service is stopped")
                } else {
                    NativeServiceEndpointStatus(true, "http://127.0.0.1:20765/", "healthy")
                }
            },
            startService = {
                starts += 1
                true
            },
        )

        val result = resolver.resolve(component(serviceNames = listOf("yuanshengwuxianpi")))

        assertEquals(1, starts)
        assertEquals(2, lookups)
        assertEquals(
            NativeComponentEndpointResult.Resolved(
                "http://127.0.0.1:20765/",
                "yuanshengwuxianpi",
            ),
            result,
        )
    }

    @Test
    fun ordinaryWebComponentUsesManifestUrlWithoutServiceLookup() {
        var queried = false
        val resolver = NativeComponentEndpointResolver {
            queried = true
            NativeServiceEndpointStatus(false, "", "unexpected")
        }

        val result = resolver.resolve(component(manifestUrl = "https://example.test/app"))

        assertFalse(queried)
        assertEquals(
            NativeComponentEndpointResult.Resolved("https://example.test/app", null),
            result,
        )
    }

    private fun component(
        manifestUrl: String = "http://127.0.0.1:23110/",
        serviceNames: List<String> = emptyList(),
        serviceRefs: List<String> = emptyList(),
    ): OpenHouseComponent {
        val control = if (serviceNames.isEmpty() && serviceRefs.isEmpty()) {
            ""
        } else {
            """,
                "controlEntry": {
                  "type": "service-control",
                  "serviceNames": ${jsonArray(serviceNames)},
                  "serviceRefs": ${jsonArray(serviceRefs)}
                }
            """.trimIndent()
        }
        val manifest = """
            {
              "id": "wuxianpi-web",
              "shellMenu": {
                "title": "WuxianPi",
                "entry": {"type": "webview", "url": "$manifestUrl"}
                $control
              }
            }
        """.trimIndent()
        return OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(manifest),
            "test",
        )
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { value -> "\"$value\"" }

    private class RecordingTransport(
        private val response: HttpResponseSpec,
    ) : HttpTransport {
        var request: HttpRequestSpec? = null

        override fun execute(
            connection: RuntimeConnection,
            request: HttpRequestSpec,
        ): HttpResponseSpec {
            this.request = request
            return response
        }
    }
}
