package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.launcher.OperitAiLauncher
import com.ai.assistance.operit.rescue.ui.RescueActivity
import com.ai.assistance.operit.rescue.resources.ApkResourceOfferStore
import com.ai.assistance.operit.ui.main.OperitHostMode
import com.ai.assistance.operit.workspace.OperitWorkspaceContent
import com.ai.assistance.operit.workspace.OperitWorkspaceContentFactory
import com.ai.assistance.operit.workspace.OperitWorkspaceSpec
import com.wuxianpi.openhouse.core.HostActionResult
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import com.wuxianpi.openhouse.core.registry.RegistryRepository
import com.wuxianpi.openhouse.core.registry.SharedPreferencesRegistryCache
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import com.wuxianpi.openhouse.core.workspace.ComponentWebResolution
import com.wuxianpi.openhouse.core.workspace.ComponentServiceActionResult
import com.wuxianpi.openhouse.core.workspace.ComponentServiceController
import com.wuxianpi.openhouse.core.workspace.ComponentServiceSummary
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.AdvancedUiEndpoints
import com.wuxianpi.openhouse.feature.ControlPlaneForegroundSupervisor
import com.wuxianpi.openhouse.feature.ComponentWebLaunchArgs
import com.wuxianpi.openhouse.feature.OpenHouseFeature
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost
import com.wuxianpi.openhouse.feature.workspace.WorkspaceContent
import com.wuxianpi.openhouse.servicecontrol.OpenHouseFeatureLauncher
import com.wuxianpi.openhouse.servicecontrol.OpenHouseServiceControlActivity
import com.wuxianpi.openhouse.servicecontrol.ServiceControlDependencies
import com.wuxianpi.openhouse.servicecontrol.ServiceControlFeature
import com.wuxianpi.openhouse.servicecontrol.ServiceControlRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared product routing backed by the native APK pairing/runtime host. */
class NativeProductHost(context: Context) : OpenHouseFeatureHost, OpenHouseFeatureLauncher {
    private val appContext = context.applicationContext
    private val host = NativeOpenHouseHost(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registryCatalog = BackgroundRegistryCatalog(loadComponents = {
        RegistryRepository(
            ServiceManagerClient(host.runtimeConnection()),
            SharedPreferencesRegistryCache(appContext),
            host.legacyRegistrySource(),
        ).load().components
    })
    private val operations = NativeOperitHostOperations(appContext)
    private val componentEndpointResolver =
        NativeComponentEndpointResolver.fromRuntimeConnection(host::runtimeConnection)
    private val componentServiceController =
        ComponentServiceController { ServiceManagerClient(host.runtimeConnection()) }
    private val componentLaunchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun install() {
        registryCatalog.start()
        ControlPlaneForegroundSupervisor.register(appContext, host.controlPlaneBridge())
        OperitHostProvider.install(NativeOperitHostContract(appContext, operations, host))
        OperitHostProvider.installOperations(operations)
        ServiceControlFeature.install { serviceControlDependencies() }
    }

    override fun capabilities(): HostCapabilities = host.capabilities()

    override fun desktopComponents(): List<OpenHouseComponent> =
        registryCatalog.components().filterNot { it.id == OpenHouseBuiltins.SHARED_BROWSER_ID } +
            OpenHouseBuiltins.sharedBrowser(
                "com.openhouse.host.nativeapp.browser.NativeSharedBrowserActivity",
            )

    override fun refreshDesktopComponents(onComplete: () -> Unit) {
        registryCatalog.refresh {
            mainHandler.post { onComplete() }
        }
    }

    override fun loadComponentServiceStates(
        components: List<OpenHouseComponent>,
        onComplete: (Map<String, ComponentServiceSummary>) -> Unit,
    ) {
        componentLaunchScope.launch {
            onComplete(withContext(Dispatchers.IO) { componentServiceController.load(components) })
        }
    }

    override fun setComponentServicesRunning(
        component: OpenHouseComponent,
        running: Boolean,
        onComplete: (ComponentServiceActionResult) -> Unit,
    ) {
        componentLaunchScope.launch {
            onComplete(
                withContext(Dispatchers.IO) {
                    componentServiceController.setRunning(component, running)
                },
            )
        }
    }

    override fun launchHostRoute(activity: Activity, route: ProductRoute) {
        when (route) {
            ProductRoute.SETUP -> host.ensureConfigured()
            ProductRoute.PERMISSIONS -> openApplicationSettings(activity)
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
                putExtra(
                    RescueActivity.EXTRA_HOST_RETURN_INTENT,
                    OpenHouseFeature.createDestinationIntent(
                        activity,
                        WorkspaceDestination.Desktop,
                    ),
                )
            }
            else -> return
        }
        activity.startActivity(intent)
    }

    override fun hasPendingApkResourceOffer(): Boolean =
        ApkResourceOfferStore.get(appContext).current()?.requiresReminder == true

    override fun dismissCurrentApkResourceOffer() {
        ApkResourceOfferStore.get(appContext).dismissCurrent()
    }

    override fun launchApkResourceUpdate(activity: Activity) {
        val intent = OperitAiLauncher.repairIntent(activity).apply {
            putExtra(
                RescueActivity.EXTRA_HOST_RETURN_ACTIVITY,
                "com.wuxianpi.openhouse.feature.OpenHouseActivity",
            )
            putExtra(RescueActivity.EXTRA_PENDING_ACTION_ID, "resource-update")
            putExtra(
                RescueActivity.EXTRA_PENDING_ACTION_PROMPT,
                RescueActivity.RESOURCE_UPDATE_PROMPT,
            )
        }
        activity.startActivity(intent)
    }

    override fun createEmbeddedContent(
        activity: Activity,
        destination: WorkspaceDestination,
    ): WorkspaceContent? {
        val componentActivity = activity as? ComponentActivity ?: return null
        val route = (destination as? WorkspaceDestination.Route)?.route ?: return null
        val hostMode = if (route == ProductRoute.BASIC) OperitHostMode.BASIC else return null
        val returnToDesktop = { componentActivity.onBackPressedDispatcher.onBackPressed() }
        val content =
            OperitWorkspaceContentFactory.create(
                componentActivity,
                OperitWorkspaceSpec(
                    hostMode = hostMode,
                    onReturnToHostMainMenu = returnToDesktop,
                    onCloseHostedOperit = returnToDesktop,
                    hostedCloseLabel = com.ai.assistance.operit.ui.main.DEFAULT_HOSTED_CLOSE_LABEL,
                ),
            )
        return content.asOpenHouseWorkspaceContent()
    }

    override fun resolveComponentWeb(
        component: OpenHouseComponent,
        onResolved: (ComponentWebResolution) -> Unit,
    ) {
        if (component.entryType != OpenHouseComponent.EntryType.WEBVIEW) {
            onResolved(ComponentWebResolution.DelegateToHost)
            return
        }
        componentLaunchScope.launch {
            val resolution =
                withContext(Dispatchers.IO) {
                    when (val endpoint = componentEndpointResolver.resolve(component)) {
                        is NativeComponentEndpointResult.Resolved ->
                            ComponentWebResolution.resolved(endpoint.url, endpoint.serviceId)
                        is NativeComponentEndpointResult.Unavailable ->
                            ComponentWebResolution.Unavailable(endpoint.message)
                    }
                }
            onResolved(resolution)
        }
    }

    override fun launchServiceControl(activity: Activity) {
        launchServiceControl(activity, ServiceControlRequest())
    }

    override fun launchTerminal(activity: Activity) {
        val result = host.openTerminal()
        if (!result.isSuccess) {
            val message = if (result.status == HostActionResult.Status.USER_ACTION_REQUIRED) {
                "请先安装并打开 WuxianPi All-in-One（Termux）。"
            } else {
                result.message.ifBlank { "无法打开 Termux 终端。" }
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun launchFiles(activity: Activity) {
        activity.startActivity(Intent(activity, NativeTermuxFilesActivity::class.java))
    }

    override fun launchComponentControl(activity: Activity, component: ComponentWebLaunchArgs) {
        launchServiceControl(activity, serviceControlRequestFor(component))
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
            OpenHouseComponent.EntryType.FILES -> launchFiles(activity)
            OpenHouseComponent.EntryType.TERMINAL -> launchTerminal(activity)
            OpenHouseComponent.EntryType.SERVICE_CONTROL -> Unit
            OpenHouseComponent.EntryType.ANDROID_ACTIVITY -> launchClass(activity, component.activityClassName)
            OpenHouseComponent.EntryType.WEBVIEW -> launchComponentWebView(activity, component)
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

    private fun launchComponentWebView(activity: Activity, component: OpenHouseComponent) {
        componentLaunchScope.launch {
            when (val endpoint = withContext(Dispatchers.IO) {
                componentEndpointResolver.resolve(component)
            }) {
                is NativeComponentEndpointResult.Resolved -> activity.startActivity(
                    createNativeComponentWebIntent(activity, component, endpoint.url),
                )
                is NativeComponentEndpointResult.Unavailable -> activity.startActivity(
                    createNativeComponentWebIntent(activity, component, ""),
                )
            }
        }
    }

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

private fun OperitWorkspaceContent.asOpenHouseWorkspaceContent(): WorkspaceContent =
    object : WorkspaceContent {
        override val view = this@asOpenHouseWorkspaceContent.view

        override fun onResume() = this@asOpenHouseWorkspaceContent.onResume()

        override fun onPause() = this@asOpenHouseWorkspaceContent.onPause()

        override fun onBackPressed(): Boolean =
            this@asOpenHouseWorkspaceContent.onBackPressed()

        override fun destroy() = this@asOpenHouseWorkspaceContent.destroy()
    }

internal fun createNativeComponentWebIntent(
    context: Context,
    component: OpenHouseComponent,
    resolvedUrl: String,
): Intent = OpenHouseFeature.createComponentWebIntent(context, component, resolvedUrl)

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
    val serviceIds = serviceIdsFor(component)
    return ServiceControlRequest(
        title = component.controlTitle.ifBlank { component.title },
        componentId = component.id,
        componentEndpoint = component.url,
        serviceIds = serviceIds,
        showAllServices = serviceIds.isEmpty(),
    )
}

fun serviceControlRequestFor(component: ComponentWebLaunchArgs): ServiceControlRequest {
    val serviceIds = serviceIdsFor(component.serviceNames, component.serviceRefs)
    return ServiceControlRequest(
        title = component.controlTitle.ifBlank { component.title },
        componentId = component.componentId,
        componentEndpoint = component.loadUrl,
        serviceIds = serviceIds,
        showAllServices = false,
    )
}
