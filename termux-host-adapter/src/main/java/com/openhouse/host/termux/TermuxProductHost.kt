package com.openhouse.host.termux

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.launcher.OperitAiLauncher
import com.ai.assistance.operit.rescue.ui.RescueActivity
import com.wuxianpi.openhouse.core.HostActionResult
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.RegistryRepository
import com.wuxianpi.openhouse.core.registry.SharedPreferencesRegistryCache
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import com.wuxianpi.openhouse.feature.AdvancedUiEndpoints
import com.wuxianpi.openhouse.feature.OpenHouseFeature
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost
import com.wuxianpi.openhouse.servicecontrol.OpenHouseFeatureLauncher
import com.wuxianpi.openhouse.servicecontrol.OpenHouseServiceControlActivity
import com.wuxianpi.openhouse.servicecontrol.ServiceControlDependencies
import com.wuxianpi.openhouse.servicecontrol.ServiceControlFeature
import com.wuxianpi.openhouse.servicecontrol.ServiceControlRequest

/** Shared product routing backed by the Termux-embedded host. */
class TermuxProductHost(context: Context) : OpenHouseFeatureHost, OpenHouseFeatureLauncher {
    private val appContext = context.applicationContext
    private val host = TermuxOpenHouseHost(appContext)
    private val registryCatalog = BackgroundRegistryCatalog(loadComponents = {
        RegistryRepository(
            ServiceManagerClient(host.runtimeConnection()),
            SharedPreferencesRegistryCache(appContext),
            host.legacyRegistrySource(),
        ).load().components
    })

    fun install() {
        registryCatalog.start()
        OperitHostProvider.installOperations(TermuxOperitHostOperations(appContext))
        ServiceControlFeature.install { serviceControlDependencies() }
    }

    override fun capabilities(): HostCapabilities = host.capabilities()

    override fun desktopComponents(): List<OpenHouseComponent> = registryCatalog.components()

    override fun launchHostRoute(activity: Activity, route: ProductRoute) {
        when (route) {
            ProductRoute.SETUP -> host.ensureConfigured()
            ProductRoute.PERMISSIONS -> launchClass(activity, PERMISSIONS_ACTIVITY_CLASS)
            ProductRoute.SETTINGS -> launchClass(activity, "com.termux.app.activities.SettingsActivity")
            else -> Unit
        }
    }

    override fun launchAiMode(activity: Activity, route: ProductRoute) {
        val intent = when (route) {
            ProductRoute.BASIC -> OperitAiLauncher.basicIntent(
                activity,
                "com.wuxianpi.openhouse.feature.OpenHouseActivity",
            )
            ProductRoute.ADVANCED -> OpenHouseFeature.createAdvancedUiIntent(activity)
            ProductRoute.REPAIR -> OperitAiLauncher.repairIntent(activity).apply {
                putExtra(
                    RescueActivity.EXTRA_HOST_RETURN_ACTIVITY,
                    "com.termux.app.activities.OpenHouseHomeActivity",
                )
            }
            else -> return
        }
        activity.startActivity(intent)
    }

    override fun launchServiceControl(activity: Activity) {
        launchServiceControl(activity, ServiceControlRequest())
    }

    override fun launchTerminal(activity: Activity) {
        val result = host.openTerminal()
        if (!result.isSuccess) {
            Toast.makeText(
                activity,
                result.message.ifBlank { "无法打开 Termux 终端。" },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun launchFiles(activity: Activity) {
        val result = launchClass(activity, FILES_ACTIVITY_CLASS)
        if (!result.isSuccess) {
            Toast.makeText(
                activity,
                result.message.ifBlank { "无法打开文件管理器。" },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun launchServiceControl(activity: Activity, request: ServiceControlRequest) {
        activity.startActivity(
            OpenHouseServiceControlActivity.createIntent(activity, request),
        )
    }

    override fun launchDynamicComponent(activity: Activity, component: OpenHouseComponent) {
        serviceControlRequestForDynamicComponent(component)?.let { request ->
            launchServiceControl(activity, request)
            return
        }
        when (component.entryType) {
            OpenHouseComponent.EntryType.TERMINAL -> launchTerminal(activity)
            OpenHouseComponent.EntryType.FILES -> launchFiles(activity)
            OpenHouseComponent.EntryType.SERVICE_CONTROL -> Unit
            OpenHouseComponent.EntryType.ANDROID_ACTIVITY -> launchClass(activity, component.activityClassName)
            OpenHouseComponent.EntryType.WEBVIEW -> openUrl(activity, component.url)
            OpenHouseComponent.EntryType.NATIVE_PAGE -> {
                val route = ProductRoute.fromPersistenceKey(component.nativePage, ProductRoute.DESKTOP)
                when (route) {
                    ProductRoute.BASIC, ProductRoute.ADVANCED, ProductRoute.REPAIR -> launchAiMode(activity, route)
                    ProductRoute.SERVICE_CONTROL -> Unit
                    ProductRoute.SETUP, ProductRoute.PERMISSIONS, ProductRoute.SETTINGS ->
                        launchHostRoute(activity, route)
                    else -> Unit
                }
            }
            null -> Unit
        }
    }

    override fun advancedUiEndpoints(): AdvancedUiEndpoints = AdvancedUiEndpoints.defaults()

    override fun returnToProduct() {
        appContext.startActivity(
            OpenHouseFeature.createIntent(appContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openServiceEndpoint(serviceId: String, endpoint: String): HostActionResult =
        openUrl(appContext, endpoint)

    override fun openAdvancedUi(endpoint: String): HostActionResult = runCatching {
        appContext.startActivity(
            OpenHouseFeature.createAdvancedUiIntent(appContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        completed("Advanced UI opened")
    }.getOrElse { failed(it) }

    private fun serviceControlDependencies() = ServiceControlDependencies(
        httpClient = ServiceManagerClient(host.runtimeConnection()),
        openHouseHost = host,
        featureLauncher = this,
    )

    private fun launchClass(context: Context, className: String): HostActionResult = runCatching {
        require(className.isNotBlank())
        context.startActivity(
            Intent().setClassName(context.packageName, className).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        completed("Opened $className")
    }.getOrElse { failed(it) }

    private fun openUrl(context: Context, url: String): HostActionResult = runCatching {
        require(url.startsWith("http://") || url.startsWith("https://"))
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        completed("Opened endpoint")
    }.getOrElse { failed(it) }

    private fun completed(message: String) = HostActionResult(HostActionResult.Status.COMPLETED, message)
    private fun failed(error: Throwable) = HostActionResult(
        HostActionResult.Status.FAILED,
        error.message ?: error.javaClass.simpleName,
    )

    companion object {
        internal const val FILES_ACTIVITY_CLASS =
            "com.termux.app.openhouse.files.ui.OpenHouseFilesActivity"
        internal const val PERMISSIONS_ACTIVITY_CLASS =
            "com.termux.app.activities.MaintenanceCenterActivity"
    }
}

fun serviceControlRequestForDynamicComponent(
    component: OpenHouseComponent,
): ServiceControlRequest? {
    val routesToServiceControl = component.entryType == OpenHouseComponent.EntryType.SERVICE_CONTROL ||
        component.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE &&
        ProductRoute.fromPersistenceKey(component.nativePage, ProductRoute.DESKTOP) == ProductRoute.SERVICE_CONTROL ||
        component.entryType == null && component.hasControlEntry()
    return if (routesToServiceControl) serviceControlRequestFor(component) else null
}

fun serviceControlRequestFor(component: OpenHouseComponent): ServiceControlRequest {
    val referencedIds = component.serviceRefs.mapNotNull { reference ->
        ServiceManagerClient.parseServiceManagerRef(reference).takeIf { it.valid }?.serviceId
    }
    val serviceIds = (component.serviceNames + referencedIds)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    return ServiceControlRequest(
        title = component.controlTitle.ifBlank { component.title },
        componentId = component.id,
        componentEndpoint = component.url,
        serviceIds = serviceIds,
        showAllServices = serviceIds.isEmpty(),
    )
}
