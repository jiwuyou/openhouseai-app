package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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

/** Shared product routing backed by the native APK pairing/runtime host. */
class NativeProductHost(context: Context) : OpenHouseFeatureHost, OpenHouseFeatureLauncher {
    private val appContext = context.applicationContext
    private val host = NativeOpenHouseHost(appContext)
    private val registryCatalog = BackgroundRegistryCatalog(loadComponents = {
        RegistryRepository(
            ServiceManagerClient(host.runtimeConnection()),
            SharedPreferencesRegistryCache(appContext),
            host.legacyRegistrySource(),
        ).load().components
    })
    private val operations = NativeOperitHostOperations(appContext)

    fun install() {
        registryCatalog.start()
        OperitHostProvider.install(NativeOperitHostContract(appContext, operations, host))
        OperitHostProvider.installOperations(operations)
        ServiceControlFeature.install { serviceControlDependencies() }
    }

    override fun capabilities(): HostCapabilities = host.capabilities()

    override fun desktopComponents(): List<OpenHouseComponent> = registryCatalog.components()

    override fun launchHostRoute(activity: Activity, route: ProductRoute) {
        when (route) {
            ProductRoute.SETUP -> host.ensureConfigured()
            ProductRoute.SETTINGS -> openApplicationSettings(activity)
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
                    "com.wuxianpi.openhouse.feature.OpenHouseActivity",
                )
            }
            else -> return
        }
        activity.startActivity(intent)
    }

    override fun launchServiceControl(activity: Activity) {
        launchServiceControl(activity, ServiceControlRequest())
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
            OpenHouseComponent.EntryType.TERMINAL -> host.openTerminal()
            OpenHouseComponent.EntryType.SERVICE_CONTROL -> Unit
            OpenHouseComponent.EntryType.ANDROID_ACTIVITY -> launchClass(activity, component.activityClassName)
            OpenHouseComponent.EntryType.WEBVIEW -> openUrl(activity, component.url)
            OpenHouseComponent.EntryType.NATIVE_PAGE -> {
                val route = ProductRoute.fromPersistenceKey(component.nativePage, ProductRoute.DESKTOP)
                when (route) {
                    ProductRoute.BASIC, ProductRoute.ADVANCED, ProductRoute.REPAIR -> launchAiMode(activity, route)
                    ProductRoute.SERVICE_CONTROL -> Unit
                    ProductRoute.SETUP, ProductRoute.SETTINGS -> launchHostRoute(activity, route)
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

    private fun openApplicationSettings(context: Context): HostActionResult = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
        completed("Settings opened")
    }.getOrElse { failed(it) }

    private fun launchClass(context: Context, className: String): HostActionResult = runCatching {
        require(className.isNotBlank())
        context.startActivity(Intent().setClassName(context.packageName, className))
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
