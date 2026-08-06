package com.openhouse.host.termux

import com.wuxianpi.openhouse.core.RuntimeConnection
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.service.HttpRequestSpec
import com.wuxianpi.openhouse.core.service.HttpResponseSpec
import com.wuxianpi.openhouse.core.service.HttpTransport
import com.wuxianpi.openhouse.core.workspace.ComponentWebResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxComponentEndpointResolverTest {
    @Test
    fun serviceBackedWebComponentUsesPublishedEndpoint() {
        val transport =
            RecordingTransport(
                HttpResponseSpec(
                    200,
                    """
                    {
                      "status": "healthy",
                      "endpoints": [
                        {"name": "web", "url": "http://127.0.0.1:20765/"}
                      ]
                    }
                    """.trimIndent(),
                )
            )
        val resolver =
            TermuxComponentEndpointResolver.fromRuntimeConnection(
                runtimeConnection = {
                    RuntimeConnection("http://127.0.0.1:20087", "token", "")
                },
                transport = transport,
            )

        val result = resolver.resolve(component(serviceName = "yuanshengwuxianpi"))

        assertTrue(result is ComponentWebResolution.Resolved)
        result as ComponentWebResolution.Resolved
        assertEquals("http://127.0.0.1:20765/", result.url)
        assertEquals("yuanshengwuxianpi", result.serviceId)
        assertEquals(
            "/api/v1/services/yuanshengwuxianpi/endpoints",
            transport.requests.first().path,
        )
    }

    @Test
    fun ordinaryWebComponentDoesNotQueryServiceManager() {
        val transport = RecordingTransport(HttpResponseSpec(500, "unexpected"))
        val resolver =
            TermuxComponentEndpointResolver.fromRuntimeConnection(
                runtimeConnection = {
                    RuntimeConnection("http://127.0.0.1:20087", "token", "")
                },
                transport = transport,
            )

        val result = resolver.resolve(component(url = "https://example.test/app"))

        assertTrue(result is ComponentWebResolution.Resolved)
        assertEquals("https://example.test/app", (result as ComponentWebResolution.Resolved).url)
        assertTrue(transport.requests.isEmpty())
    }

    private fun component(
        url: String = "http://127.0.0.1:23110/",
        serviceName: String? = null,
    ): OpenHouseComponent {
        val control =
            serviceName?.let {
                """,
                "controlEntry": {
                  "type": "service-control",
                  "serviceNames": ["$it"]
                }
                """.trimIndent()
            }.orEmpty()
        val manifest =
            """
            {
              "id": "web-app",
              "shellMenu": {
                "title": "Web App",
                "entry": {"type": "webview", "url": "$url"}
                $control
              }
            }
            """.trimIndent()
        return OpenHouseComponentParser().parse(RegistryManifest.fromManifestJson(manifest), "test")
    }

    private class RecordingTransport(
        private val response: HttpResponseSpec,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequestSpec>()

        override fun execute(
            connection: RuntimeConnection,
            request: HttpRequestSpec,
        ): HttpResponseSpec {
            requests += request
            return response
        }
    }
}
