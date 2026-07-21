package com.wuxianpi.openhouse.servicecontrol

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHouseServiceControlActivityTest {
    @After
    fun tearDown() {
        ServiceControlFeature.resetForTests()
    }

    @Test
    fun activityStartsWithoutAnyRegistryInstallation() {
        ServiceControlFeature.install {
            ServiceControlDependencies(
                httpClient = ServiceManagerClient(ACTIVITY_RUNTIME, ActivityTransport()),
                openHouseHost = FakeActivityHost(),
                featureLauncher = FakeActivityLauncher(),
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = OpenHouseServiceControlActivity.createIntent(
            context,
            ServiceControlRequest(serviceIds = listOf("pi-agent"), showAllServices = false),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<OpenHouseServiceControlActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
}

private class ActivityTransport : HttpTransport {
    override fun execute(connection: RuntimeConnection, request: HttpRequestSpec): HttpResponseSpec = when {
        request.path == "/api/v1/services" -> HttpResponseSpec(200, """[{"id":"pi-agent","state":"running"}]""")
        request.path.endsWith("/status") -> HttpResponseSpec(200, """{"state":"running"}""")
        else -> HttpResponseSpec(200, "{}")
    }
}

private class FakeActivityHost : OpenHouseHost {
    private val starter = object : ControlPlaneStarter {
        override fun startControlPlane() = ControlPlaneResult(ControlPlaneResult.Status.STARTED, "")
        override fun stopControlPlane() = ControlPlaneResult(ControlPlaneResult.Status.STOPPED, "")
    }

    override fun edition() = HostEdition.NATIVE_ANDROID
    override fun capabilities() = HostCapabilities.full()
    override fun setupState() = SetupState.ready()
    override fun ensureConfigured() = SetupResult(SetupResult.Status.ALREADY_CONFIGURED, SetupState.ready(), "")
    override fun runtimeConnection() = ACTIVITY_RUNTIME
    override fun controlPlaneStarter() = starter
    override fun legacyRegistrySource() = LegacyRegistrySource { LegacyRegistrySnapshot.unavailable() }
    override fun openTerminal() = HostActionResult(HostActionResult.Status.COMPLETED, "")
    override fun openHostMaintenance() = HostActionResult(HostActionResult.Status.COMPLETED, "")
}

private class FakeActivityLauncher : OpenHouseFeatureLauncher {
    override fun returnToProduct() = Unit
    override fun openServiceEndpoint(serviceId: String, endpoint: String) =
        HostActionResult(HostActionResult.Status.COMPLETED, "")
    override fun openAdvancedUi(endpoint: String) = HostActionResult(HostActionResult.Status.COMPLETED, "")
}

private val ACTIVITY_RUNTIME = RuntimeConnection(
    "http://127.0.0.1:20087",
    "test-token",
    "http://127.0.0.1:8765",
)
