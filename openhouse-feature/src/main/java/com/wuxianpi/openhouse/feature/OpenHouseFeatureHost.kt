package com.wuxianpi.openhouse.feature

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.HostEdition
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.workspace.ComponentWebResolution
import com.wuxianpi.openhouse.core.workspace.ComponentServiceActionResult
import com.wuxianpi.openhouse.core.workspace.ComponentServiceSummary
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.workspace.WorkspaceContent

enum class OpenHouseSetupAttention {
    FIRST_INSTALL,
    RESOURCE_UPDATE,
    GENERIC;

    companion object {
        fun fromResourceOffer(reason: String?, requiresReminder: Boolean): OpenHouseSetupAttention? {
            if (!requiresReminder) return null
            return when (reason) {
                "first-install" -> FIRST_INSTALL
                "apk-update" -> RESOURCE_UPDATE
                else -> GENERIC
            }
        }
    }
}

/** Host integration boundary for the shared OpenHouse display layer. */
interface OpenHouseFeatureHost {
    fun edition(): HostEdition = HostEdition.NATIVE_ANDROID

    fun capabilities(): HostCapabilities = HostCapabilities.full()

    fun desktopComponents(): List<OpenHouseComponent> = emptyList()

    /** Reloads host-provided desktop components without blocking the caller. */
    fun refreshDesktopComponents(onComplete: () -> Unit = {}) {
        onComplete()
    }

    /** Loads service-manager state for the supplied registry components off the caller thread. */
    fun loadComponentServiceStates(
        components: List<OpenHouseComponent>,
        onComplete: (Map<String, ComponentServiceSummary>) -> Unit,
    ) {
        onComplete(emptyMap())
    }

    /** Starts or stops every service associated with one component. */
    fun setComponentServicesRunning(
        component: OpenHouseComponent,
        running: Boolean,
        onComplete: (ComponentServiceActionResult) -> Unit,
    ) {
        onComplete(
            ComponentServiceActionResult(
                success = false,
                componentId = component.id,
                running = running,
                message = "service control is unavailable",
            ),
        )
    }

    fun launchHostRoute(activity: Activity, route: ProductRoute) = Unit

    fun launchAiMode(activity: Activity, route: ProductRoute) {
        if (route == ProductRoute.ADVANCED) {
            activity.startActivity(Intent(activity, AdvancedUiActivity::class.java))
        }
    }

    fun launchServiceControl(activity: Activity) = Unit

    fun launchTerminal(activity: Activity) = Unit

    fun launchFiles(activity: Activity) = Unit

    /** Opens controls scoped to the Web component currently shown by the shared feature UI. */
    fun launchComponentControl(activity: Activity, component: ComponentWebLaunchArgs) {
        launchServiceControl(activity)
    }

    /** Opens the host's maintenance/setup entry when a component cannot be reached. */
    fun launchMaintenance(activity: Activity) {
        launchHostRoute(activity, ProductRoute.SETUP)
    }

    /** True only while the current APK resource offer still needs user attention. */
    fun hasPendingApkResourceOffer(): Boolean = false

    /** Maps a pending APK resource offer to the compact action shown in the top bar. */
    fun setupAttention(): OpenHouseSetupAttention? = null

    /** Suppresses the current offer reminder without changing installed Termux resource state. */
    fun dismissCurrentApkResourceOffer() = Unit

    /** Opens Rescue AI and submits the resource-check intent as a normal conversation. */
    fun launchApkResourceUpdate(activity: Activity) {
        launchAiMode(activity, ProductRoute.REPAIR)
    }

    /** Opens Rescue AI at the action represented by the current top-bar reminder. */
    fun launchSetupAttention(activity: Activity, attention: OpenHouseSetupAttention) {
        if (attention == OpenHouseSetupAttention.RESOURCE_UPDATE) {
            launchApkResourceUpdate(activity)
        } else {
            launchAiMode(activity, ProductRoute.REPAIR)
        }
    }

    /** Resolves a Web component without blocking the main thread. */
    fun resolveComponentWeb(
        component: OpenHouseComponent,
        onResolved: (ComponentWebResolution) -> Unit,
    ) {
        if (component.entryType == OpenHouseComponent.EntryType.WEBVIEW && !component.isServiceBacked) {
            onResolved(ComponentWebResolution.resolved(component.url))
        } else {
            onResolved(ComponentWebResolution.DelegateToHost)
        }
    }

    /** Returns host content that can be mounted in OpenHouseActivity, or null for legacy launch. */
    fun createEmbeddedContent(
        activity: Activity,
        destination: WorkspaceDestination,
    ): WorkspaceContent? = null

    /** Compatibility path for old adapters and non-embeddable component types. */
    fun launchDynamicComponent(activity: Activity, component: OpenHouseComponent) = Unit

    fun advancedUiEndpoints(): AdvancedUiEndpoints = AdvancedUiEndpoints.defaults()

    fun onDesktopReleased() = Unit
}

/** Implement this on the host Application so every UI process can resolve its callbacks. */
interface OpenHouseFeatureHostProvider {
    fun openHouseFeatureHost(): OpenHouseFeatureHost
}

internal object OpenHouseFeatureHosts {
    private val fallback = object : OpenHouseFeatureHost {}

    fun from(context: Context): OpenHouseFeatureHost {
        val application = context.applicationContext
        return (application as? OpenHouseFeatureHostProvider)?.openHouseFeatureHost() ?: fallback
    }
}

object OpenHouseFeature {
    const val EXTRA_STARTUP_ROUTE = "com.wuxianpi.openhouse.feature.STARTUP_ROUTE"
    const val EXTRA_STARTUP_COMPONENT_ID = "com.wuxianpi.openhouse.feature.STARTUP_COMPONENT_ID"

    @JvmStatic
    fun createIntent(context: Context, route: ProductRoute? = null): Intent {
        return Intent(context, OpenHouseActivity::class.java).apply {
            route?.let { putExtra(EXTRA_STARTUP_ROUTE, it.name) }
        }
    }

    @JvmStatic
    fun createDestinationIntent(context: Context, destination: WorkspaceDestination): Intent {
        return createIntent(context).apply {
            when (destination) {
                WorkspaceDestination.Desktop ->
                    putExtra(EXTRA_STARTUP_ROUTE, ProductRoute.DESKTOP.name)
                is WorkspaceDestination.Route ->
                    putExtra(EXTRA_STARTUP_ROUTE, destination.route.name)
                is WorkspaceDestination.Component ->
                    putExtra(EXTRA_STARTUP_COMPONENT_ID, destination.normalizedComponentId)
            }
        }
    }

    @JvmStatic
    fun createAdvancedUiIntent(context: Context): Intent =
        Intent(context, AdvancedUiActivity::class.java)

    @JvmStatic
    fun createComponentWebIntent(
        context: Context,
        component: OpenHouseComponent,
        resolvedUrl: String,
    ): Intent = ComponentWebLaunchArgs.from(component, resolvedUrl).createIntent(context)
}
