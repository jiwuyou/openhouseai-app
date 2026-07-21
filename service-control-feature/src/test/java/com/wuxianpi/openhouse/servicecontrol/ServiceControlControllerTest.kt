package com.wuxianpi.openhouse.servicecontrol

import com.wuxianpi.openhouse.core.ControlPlaneResult
import com.wuxianpi.openhouse.core.ControlPlaneStarter
import com.wuxianpi.openhouse.core.HostActionResult
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.HostEdition
import com.wuxianpi.openhouse.core.LegacyRegistrySource
import com.wuxianpi.openhouse.core.OpenHouseHost
import com.wuxianpi.openhouse.core.RuntimeConnection
import com.wuxianpi.openhouse.core.SetupResult
import com.wuxianpi.openhouse.core.SetupState
import com.wuxianpi.openhouse.core.registry.LegacyRegistrySnapshot
import com.wuxianpi.openhouse.core.service.HttpRequestSpec
import com.wuxianpi.openhouse.core.service.HttpResponseSpec
import com.wuxianpi.openhouse.core.service.HttpTransport
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceControlControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test
    fun offlinePageKeepsControlPlaneAndMaintenanceRecoveryAvailable() {
        val transport = RecordingTransport().apply { listFailure = IOException("connection refused") }
        val host = FakeHost(onStart = { transport.listFailure = null })
        val controller = controller(transport, host)

        controller.refresh()
        scope.advanceUntilIdle()

        assertEquals(ControlPlaneState.OFFLINE, controller.state.value.controlPlaneState)
        assertTrue(controller.state.value.showRecoveryActions)

        controller.startControlPlane()
        scope.advanceUntilIdle()

        assertEquals(1, host.startCount)
        assertEquals(ControlPlaneState.ONLINE, controller.state.value.controlPlaneState)
        controller.openMaintenance()
        scope.advanceUntilIdle()
        assertEquals(1, host.maintenanceCount)
    }

    @Test
    fun serviceActionsUseCurrentServiceManagerHttpApiOnly() {
        val transport = RecordingTransport()
        val host = FakeHost()
        val controller = controller(
            transport,
            host,
            request = ServiceControlRequest(
                serviceIds = listOf("pi-agent"),
                showAllServices = false,
            ),
        )
        controller.refresh()
        scope.advanceUntilIdle()

        controller.runAction("pi-agent", ServiceAction.RESTART)
        scope.advanceUntilIdle()

        assertTrue(transport.requests.any { it.method == "POST" && it.path == "/api/v1/services/pi-agent/restart" })
        assertEquals(0, host.startCount)
        assertEquals(0, host.maintenanceCount)
        assertEquals("running", controller.state.value.services.single().state)
    }

    @Test
    fun logsAndHttpErrorsAreRenderedWithoutRegistryDependency() {
        val transport = RecordingTransport()
        val controller = controller(transport, FakeHost())

        controller.fetchLogs("pi-agent")
        scope.advanceUntilIdle()
        assertEquals("hello", controller.state.value.logs.getValue("pi-agent").single().message)
        assertTrue(transport.requests.any { it.path == "/api/v1/services/pi-agent/logs?limit=80" })

        transport.logFailure = IOException("log endpoint failed")
        controller.fetchLogs("pi-agent")
        scope.advanceUntilIdle()
        assertTrue(controller.state.value.statusMessage.contains("log endpoint failed"))
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun componentServicesWorkWithNoRegistryObjectOrApi() {
        val transport = RecordingTransport()
        val controller = controller(
            transport = transport,
            host = FakeHost(),
            request = ServiceControlRequest(
                componentId = "desktop-app",
                serviceIds = listOf("pi-agent", "missing-service"),
                showAllServices = false,
            ),
        )
        controller.refresh()
        scope.advanceUntilIdle()

        assertEquals(ControlPlaneState.ONLINE, controller.state.value.controlPlaneState)
        assertEquals(listOf("pi-agent", "missing-service"), controller.state.value.services.map { it.normalizedId })
        assertFalse(controller.state.value.services.last().statusAvailable)
        assertFalse(transport.requests.any { it.path.contains("registry") })
    }

    private fun controller(
        transport: RecordingTransport,
        host: FakeHost,
        request: ServiceControlRequest = ServiceControlRequest(),
    ) = ServiceControlController(
        request = request,
        dependencies = ServiceControlDependencies(
            ServiceManagerClient(TEST_RUNTIME, transport),
            host,
            FakeLauncher(),
        ),
        scope = scope,
        ioDispatcher = dispatcher,
    )
}

private class RecordingTransport : HttpTransport {
    var listFailure: IOException? = null
    var logFailure: IOException? = null
    val requests = mutableListOf<HttpRequestSpec>()

    override fun execute(connection: RuntimeConnection, request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        if (request.path == "/api/v1/services") {
            listFailure?.let { throw it }
            return HttpResponseSpec(
                200,
                """{"services":[{"spec":{"id":"pi-agent","title":"Node Pi Runtime","provider":"process"},"status":{"state":"running","pid":123,"url":"http://127.0.0.1:8765"}}]}""",
            )
        }
        if (request.path.endsWith("/logs?limit=80")) {
            logFailure?.let { throw it }
            return HttpResponseSpec(200, """{"logs":[{"time":"now","stream":"stdout","message":"hello"}]}""")
        }
        if (request.path.endsWith("/status")) {
            return HttpResponseSpec(200, """{"state":"running","provider":"process","pid":123,"url":"http://127.0.0.1:8765"}""")
        }
        if (request.method == "POST") return HttpResponseSpec(200, "{}")
        return HttpResponseSpec(404, "not found")
    }
}

private class FakeHost(
    private val onStart: () -> Unit = {},
) : OpenHouseHost {
    var startCount = 0
    var maintenanceCount = 0

    private val starter = object : ControlPlaneStarter {
        override fun startControlPlane(): ControlPlaneResult {
            startCount++
            onStart()
            return ControlPlaneResult(ControlPlaneResult.Status.STARTED, "")
        }

        override fun stopControlPlane() = ControlPlaneResult(ControlPlaneResult.Status.STOPPED, "")
    }

    override fun edition() = HostEdition.NATIVE_ANDROID
    override fun capabilities() = HostCapabilities.full()
    override fun setupState() = SetupState.ready()
    override fun ensureConfigured() = SetupResult(SetupResult.Status.ALREADY_CONFIGURED, SetupState.ready(), "")
    override fun runtimeConnection() = TEST_RUNTIME
    override fun controlPlaneStarter() = starter
    override fun legacyRegistrySource() = LegacyRegistrySource { LegacyRegistrySnapshot.unavailable() }
    override fun openTerminal() = HostActionResult(HostActionResult.Status.COMPLETED, "")
    override fun openHostMaintenance(): HostActionResult {
        maintenanceCount++
        return HostActionResult(HostActionResult.Status.COMPLETED, "")
    }
}

private class FakeLauncher : OpenHouseFeatureLauncher {
    override fun returnToProduct() = Unit
    override fun openServiceEndpoint(serviceId: String, endpoint: String) =
        HostActionResult(HostActionResult.Status.COMPLETED, "")
    override fun openAdvancedUi(endpoint: String) = HostActionResult(HostActionResult.Status.COMPLETED, "")
}

private val TEST_RUNTIME = RuntimeConnection(
    "http://127.0.0.1:20087",
    "test-token",
    "http://127.0.0.1:8765",
)
