package com.wuxianpi.openhouse.feature

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent

/** Host integration boundary for the shared OpenHouse display layer. */
interface OpenHouseFeatureHost {
    fun capabilities(): HostCapabilities = HostCapabilities.full()

    fun desktopComponents(): List<OpenHouseComponent> = emptyList()

    fun launchHostRoute(activity: Activity, route: ProductRoute) = Unit

    fun launchAiMode(activity: Activity, route: ProductRoute) {
        if (route == ProductRoute.ADVANCED) {
            activity.startActivity(Intent(activity, AdvancedUiActivity::class.java))
        }
    }

    fun launchServiceControl(activity: Activity) = Unit

    /** Opens controls scoped to the Web component currently shown by the shared feature UI. */
    fun launchComponentControl(activity: Activity, component: ComponentWebLaunchArgs) {
        launchServiceControl(activity)
    }

    /** Opens the host's maintenance/setup entry when a component cannot be reached. */
    fun launchMaintenance(activity: Activity) {
        launchHostRoute(activity, ProductRoute.SETUP)
    }

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

    @JvmStatic
    fun createIntent(context: Context, route: ProductRoute? = null): Intent {
        return Intent(context, OpenHouseActivity::class.java).apply {
            route?.let { putExtra(EXTRA_STARTUP_ROUTE, it.name) }
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
