package com.wuxianpi.openhouse.feature

import android.content.Context
import android.content.SharedPreferences
import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.StartupTarget
import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination

sealed class StartupSelection {
    data object Automatic : StartupSelection()
    data object LastPage : StartupSelection()
    data object Desktop : StartupSelection()
    data class Route(val route: ProductRoute) : StartupSelection()
    data class Component(val componentId: String) : StartupSelection()
}

class StartupRouteStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    ) {
        migrateLegacyPreferences(context.applicationContext)
    }

    fun selection(): StartupSelection {
        preferences.getString(KEY_DESTINATION, null)?.let(::parseSelection)?.let { return it }
        if (!preferences.contains(KEY_TARGET)) return StartupSelection.Automatic
        return selectionFor(StartupTarget.fromPersistenceKey(preferences.getString(KEY_TARGET, "")))
    }

    fun target(): StartupTarget {
        return when (val selection = selection()) {
            StartupSelection.LastPage -> StartupTarget.LAST_PAGE
            StartupSelection.Desktop, StartupSelection.Automatic, is StartupSelection.Component -> StartupTarget.DESKTOP
            is StartupSelection.Route -> when (selection.route) {
                ProductRoute.BASIC -> StartupTarget.BASIC
                ProductRoute.ADVANCED -> StartupTarget.ADVANCED
                else -> StartupTarget.DESKTOP
            }
        }
    }

    fun setTarget(target: StartupTarget) {
        preferences.edit()
            .putString(KEY_TARGET, target.persistenceKey())
            .putString(KEY_DESTINATION, encode(selectionFor(target)))
            .apply()
    }

    fun setHomeDestination(destination: WorkspaceDestination) {
        val selection = when (destination) {
            WorkspaceDestination.Desktop -> StartupSelection.Desktop
            is WorkspaceDestination.Component -> StartupSelection.Component(destination.normalizedComponentId)
            is WorkspaceDestination.Route -> StartupSelection.Route(destination.route)
        }
        preferences.edit()
            .putString(KEY_DESTINATION, encode(selection))
            .putString(KEY_TARGET, targetFor(selection).persistenceKey())
            .apply()
    }

    fun recordLast(route: ProductRoute) {
        recordLast(WorkspaceDestination.forRoute(route))
    }

    fun recordLast(destination: WorkspaceDestination) {
        if (!isEligible(destination)) return
        val editor = preferences.edit().putString(KEY_LAST_DESTINATION, destination.stableKey)
        val route = (destination as? WorkspaceDestination.Route)?.route
            ?: if (destination == WorkspaceDestination.Desktop) ProductRoute.DESKTOP else null
        route?.let { editor.putString(KEY_LAST_ROUTE, it.persistenceKey()) }
        editor.apply()
    }

    fun resolve(
        explicitRoute: ProductRoute? = null,
        capabilities: HostCapabilities = HostCapabilities.full(),
    ): ProductRoute {
        if (explicitRoute in supportedRoutes && capabilities.supports(explicitRoute)) return explicitRoute!!
        val last = parseRoute(preferences.getString(KEY_LAST_ROUTE, null))
        return target().resolve(last, capabilities)
    }

    fun resolveDestination(
        components: List<OpenHouseComponent>,
        capabilities: HostCapabilities = HostCapabilities.full(),
    ): WorkspaceDestination {
        val selection = selection()
        val candidate = when (selection) {
            StartupSelection.Automatic -> manifestHome(components)
            StartupSelection.LastPage -> parseDestination(preferences.getString(KEY_LAST_DESTINATION, null))
                ?: parseRoute(preferences.getString(KEY_LAST_ROUTE, null))?.let(WorkspaceDestination::forRoute)
            StartupSelection.Desktop -> WorkspaceDestination.Desktop
            is StartupSelection.Route -> WorkspaceDestination.forRoute(selection.route)
            is StartupSelection.Component -> runCatching {
                WorkspaceDestination.Component(selection.componentId)
            }.getOrNull()
        }
        return validate(candidate, components, capabilities) ?: WorkspaceDestination.Desktop
    }

    fun hasExplicitSelection(): Boolean = preferences.contains(KEY_DESTINATION) || preferences.contains(KEY_TARGET)

    fun keepDesktopResident(): Boolean = preferences.getBoolean(KEY_KEEP_RESIDENT, false)

    fun setKeepDesktopResident(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_RESIDENT, enabled).apply()
    }

    fun showManualHint(): Boolean = preferences.getBoolean(KEY_SHOW_MANUAL_HINT, true)

    fun setShowManualHint(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_MANUAL_HINT, enabled).apply()
    }

    private fun parseRoute(value: String?): ProductRoute? = value
        ?.takeIf(String::isNotBlank)
        ?.let { ProductRoute.fromPersistenceKey(it, ProductRoute.DESKTOP) }
        ?.takeIf { it in supportedRoutes }

    private fun manifestHome(components: List<OpenHouseComponent>): WorkspaceDestination? = components
        .asSequence()
        .filter {
            !OpenHouseBuiltins.isProtectedId(it.id) && it.visible && it.hasEntry() &&
                (it.home || it.desktopHome)
        }
        .sortedWith(compareBy<OpenHouseComponent> { it.order }.thenBy { it.title }.thenBy { it.id })
        .mapNotNull { runCatching { WorkspaceDestination.Component(it.id) }.getOrNull() }
        .firstOrNull()

    private fun validate(
        destination: WorkspaceDestination?,
        components: List<OpenHouseComponent>,
        capabilities: HostCapabilities,
    ): WorkspaceDestination? = when (destination) {
        null -> null
        WorkspaceDestination.Desktop -> WorkspaceDestination.Desktop
        is WorkspaceDestination.Route -> destination.takeIf {
            it.route in supportedRoutes && capabilities.supports(it.route)
        }
        is WorkspaceDestination.Component -> destination.takeIf { target ->
            components.any { component ->
                component.visible && component.hasEntry() &&
                    WorkspaceDestination.normalizeId(component.id) == target.normalizedComponentId
            }
        }
    }

    private fun isEligible(destination: WorkspaceDestination): Boolean = when (destination) {
        WorkspaceDestination.Desktop -> true
        is WorkspaceDestination.Component -> true
        is WorkspaceDestination.Route -> destination.route in supportedRoutes
    }

    private fun parseDestination(value: String?): WorkspaceDestination? {
        val stableKey = value.orEmpty().trim()
        if (stableKey == "desktop") return WorkspaceDestination.Desktop
        if (stableKey.startsWith("component:")) {
            return runCatching { WorkspaceDestination.Component(stableKey.substringAfter(':')) }.getOrNull()
        }
        if (stableKey.startsWith("route:")) {
            val route = ProductRoute.fromPersistenceKey(stableKey.substringAfter(':'), ProductRoute.DESKTOP)
            return WorkspaceDestination.forRoute(route)
        }
        return null
    }

    private fun parseSelection(value: String?): StartupSelection? {
        val encoded = value.orEmpty().trim()
        return when {
            encoded == "auto" -> StartupSelection.Automatic
            encoded == "last" -> StartupSelection.LastPage
            encoded == "desktop" -> StartupSelection.Desktop
            encoded.startsWith("route:") -> {
                val route = ProductRoute.fromPersistenceKey(encoded.substringAfter(':'), ProductRoute.DESKTOP)
                if (route in supportedRoutes && route != ProductRoute.DESKTOP) StartupSelection.Route(route)
                else StartupSelection.Desktop
            }
            encoded.startsWith("component:") -> WorkspaceDestination.normalizeId(encoded.substringAfter(':'))
                .takeIf(String::isNotEmpty)
                ?.let(StartupSelection::Component)
            else -> null
        }
    }

    private fun encode(selection: StartupSelection): String = when (selection) {
        StartupSelection.Automatic -> "auto"
        StartupSelection.LastPage -> "last"
        StartupSelection.Desktop -> "desktop"
        is StartupSelection.Route -> "route:${selection.route.persistenceKey()}"
        is StartupSelection.Component -> "component:${WorkspaceDestination.normalizeId(selection.componentId)}"
    }

    private fun selectionFor(target: StartupTarget): StartupSelection = when (target) {
        StartupTarget.LAST_PAGE -> StartupSelection.LastPage
        StartupTarget.DESKTOP -> StartupSelection.Desktop
        StartupTarget.BASIC -> StartupSelection.Route(ProductRoute.BASIC)
        StartupTarget.ADVANCED -> StartupSelection.Route(ProductRoute.ADVANCED)
    }

    private fun targetFor(selection: StartupSelection): StartupTarget = when (selection) {
        StartupSelection.LastPage -> StartupTarget.LAST_PAGE
        is StartupSelection.Route -> when (selection.route) {
            ProductRoute.BASIC -> StartupTarget.BASIC
            ProductRoute.ADVANCED -> StartupTarget.ADVANCED
            else -> StartupTarget.DESKTOP
        }
        else -> StartupTarget.DESKTOP
    }

    private fun migrateLegacyPreferences(context: Context) {
        if (preferences.contains(KEY_DESTINATION) || preferences.contains(KEY_TARGET)) return
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!legacy.contains(LEGACY_START_MODE)) return
        val selection = when (legacy.getString(LEGACY_START_MODE, "desktop")) {
            "last" -> StartupSelection.LastPage
            "page" -> legacy.getString(LEGACY_HOME_PAGE, "")
                ?.let(::legacyPageSelection)
                ?: StartupSelection.Desktop
            else -> StartupSelection.Desktop
        }
        preferences.edit()
            .putString(KEY_DESTINATION, encode(selection))
            .putString(KEY_TARGET, targetFor(selection).persistenceKey())
            .apply()
    }

    private fun legacyPageSelection(page: String): StartupSelection? {
        val value = page.trim().lowercase()
        return when {
            value == "desktop" -> StartupSelection.Desktop
            value == "basic" -> StartupSelection.Route(ProductRoute.BASIC)
            value == "advanced" -> StartupSelection.Route(ProductRoute.ADVANCED)
            value.startsWith("component:") -> WorkspaceDestination.normalizeId(value.substringAfter(':'))
                .takeIf(String::isNotEmpty)
                ?.let(StartupSelection::Component)
            else -> null
        }
    }

    companion object {
        const val PREFS_NAME = "openhouse_feature"
        private const val KEY_TARGET = "startup_target"
        private const val KEY_LAST_ROUTE = "startup_last_route"
        private const val KEY_DESTINATION = "startup_destination_v2"
        private const val KEY_LAST_DESTINATION = "startup_last_destination_v2"
        private const val KEY_KEEP_RESIDENT = "desktop_keep_resident"
        private const val KEY_SHOW_MANUAL_HINT = "desktop_show_manual_hint"
        private const val LEGACY_PREFS_NAME = "openhouse_home"
        private const val LEGACY_START_MODE = "start_page_mode"
        private const val LEGACY_HOME_PAGE = "home_page"
        private val supportedRoutes = setOf(ProductRoute.DESKTOP, ProductRoute.BASIC, ProductRoute.ADVANCED)
    }
}
