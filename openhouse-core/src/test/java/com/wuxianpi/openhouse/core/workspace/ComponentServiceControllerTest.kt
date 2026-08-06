package com.wuxianpi.openhouse.core.workspace

import com.wuxianpi.openhouse.core.RuntimeConnection
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.service.HttpRequestSpec
import com.wuxianpi.openhouse.core.service.HttpResponseSpec
import com.wuxianpi.openhouse.core.service.HttpTransport
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ComponentServiceControllerTest {
    @Test
    fun aggregatesBulkStatusAndStopsServicesInReverseOrder() {
        val transport = FakeTransport().apply {
            responses += HttpResponseSpec(
                200,
                """[
                    {"service":{"id":"api","name":"api"},"status":{"state":"running"}},
                    {"service":{"id":"web","name":"web"},"status":{"state":"stopped"}}
                ]""".trimIndent(),
            )
            responses += HttpResponseSpec(204, "")
            responses += HttpResponseSpec(204, "")
        }
        val client = ServiceManagerClient(runtime(), transport)
        val controller = ComponentServiceController { client }
        val component = component("demo", listOf("api", "web"))

        val summary = controller.load(listOf(component)).getValue("demo")
        val action = controller.setRunning(component, false)

        assertEquals(ComponentServiceState.MIXED, summary.state)
        assertTrue(action.success)
        assertEquals(
            listOf(
                "/api/v1/services/statuses",
                "/api/v1/services/web/stop",
                "/api/v1/services/api/stop",
            ),
            transport.requests.map { it.path },
        )
    }

    @Test
    fun fallsBackToIndividualStatusWhenBulkApiIsUnavailable() {
        val transport = FakeTransport().apply {
            responses += HttpResponseSpec(404, "missing")
            responses += HttpResponseSpec(200, "{\"state\":\"running\"}")
        }
        val controller = ComponentServiceController {
            ServiceManagerClient(runtime(), transport)
        }

        val summary = controller.load(listOf(component("demo", listOf("api")))).getValue("demo")

        assertEquals(ComponentServiceState.RUNNING, summary.state)
        assertEquals("/api/v1/services/api/status", transport.requests.last().path)
    }

    private fun component(id: String, services: List<String>): OpenHouseComponent {
        val names = services.joinToString(",") { "\"$it\"" }
        return OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(
                """{
                    "id":"$id",
                    "title":"Demo",
                    "entry":{"type":"webview","url":"http://127.0.0.1:9000/"},
                    "controlEntry":{"type":"service-control","serviceNames":[$names]}
                }""".trimIndent(),
            ),
            "test",
        )
    }

    private fun runtime() = RuntimeConnection("http://127.0.0.1:20087", "token", "")

    private class FakeTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponseSpec>()
        val requests = mutableListOf<HttpRequestSpec>()

        override fun execute(connection: RuntimeConnection, request: HttpRequestSpec): HttpResponseSpec {
            requests += request
            return responses.removeFirst()
        }
    }
}
