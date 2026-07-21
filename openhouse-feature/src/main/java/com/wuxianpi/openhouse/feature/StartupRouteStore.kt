package com.wuxianpi.openhouse.feature

import android.content.Context
import android.content.SharedPreferences
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.StartupTarget

class StartupRouteStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun target(): StartupTarget {
        return StartupTarget.fromPersistenceKey(preferences.getString(KEY_TARGET, ""))
    }

    fun setTarget(target: StartupTarget) {
        preferences.edit().putString(KEY_TARGET, target.persistenceKey()).apply()
    }

    fun recordLast(route: ProductRoute) {
        if (route in supportedRoutes) {
            preferences.edit().putString(KEY_LAST_ROUTE, route.persistenceKey()).apply()
        }
    }

    fun resolve(
        explicitRoute: ProductRoute? = null,
        capabilities: HostCapabilities = HostCapabilities.full(),
    ): ProductRoute {
        if (explicitRoute in supportedRoutes && capabilities.supports(explicitRoute)) return explicitRoute!!
        val last = parseRoute(preferences.getString(KEY_LAST_ROUTE, null))
        return target().resolve(last, capabilities)
    }

    fun keepDesktopResident(): Boolean = preferences.getBoolean(KEY_KEEP_RESIDENT, false)

    fun setKeepDesktopResident(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_RESIDENT, enabled).apply()
    }

    private fun parseRoute(value: String?): ProductRoute? = value
        ?.takeIf(String::isNotBlank)
        ?.let { ProductRoute.fromPersistenceKey(it, ProductRoute.DESKTOP) }
        ?.takeIf { it in supportedRoutes }

    companion object {
        const val PREFS_NAME = "openhouse_feature"
        private const val KEY_TARGET = "startup_target"
        private const val KEY_LAST_ROUTE = "startup_last_route"
        private const val KEY_KEEP_RESIDENT = "desktop_keep_resident"
        private val supportedRoutes = setOf(ProductRoute.DESKTOP, ProductRoute.BASIC, ProductRoute.ADVANCED)
    }
}
