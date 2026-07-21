package com.wuxianpi.openhouse.servicecontrol

import android.content.Context
import com.wuxianpi.openhouse.core.HostActionResult
import com.wuxianpi.openhouse.core.OpenHouseHost
import com.wuxianpi.openhouse.core.service.ServiceManagerClient

/**
 * The application host installs an adapter backed by com.wuxianpi.openhouse.core in every UI
 * process. This feature never owns endpoints, credentials, shell commands, or registry state.
 */
interface OpenHouseFeatureLauncher {
    fun returnToProduct()
    fun openServiceEndpoint(serviceId: String, endpoint: String): HostActionResult
    fun openAdvancedUi(endpoint: String): HostActionResult
}

data class ServiceControlDependencies(
    val httpClient: ServiceManagerClient,
    val openHouseHost: OpenHouseHost,
    val featureLauncher: OpenHouseFeatureLauncher,
)

object ServiceControlFeature {
    @Volatile
    private var factory: ((Context) -> ServiceControlDependencies)? = null

    /** Must be called from each host Application, including the :openhouse process. */
    @JvmStatic
    fun install(factory: (Context) -> ServiceControlDependencies) {
        this.factory = factory
    }

    internal fun dependencies(context: Context): ServiceControlDependencies =
        checkNotNull(factory) {
            "ServiceControlFeature is not installed. Install the openhouse-core adapter in Application.onCreate()."
        }.invoke(context.applicationContext)

    internal fun isInstalled(): Boolean = factory != null

    internal fun resetForTests() {
        factory = null
    }
}
