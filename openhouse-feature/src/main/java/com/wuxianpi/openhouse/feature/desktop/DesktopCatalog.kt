package com.wuxianpi.openhouse.feature.desktop

import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent

object DesktopCatalog {
    const val ID_DESKTOP = "desktop"
    const val ID_BASIC = "basic"
    const val ID_ADVANCED = "advanced"
    const val ID_REPAIR = "repair"
    const val ID_TERMINAL = "terminal"
    const val ID_FILES = "openhouse-files"
    const val ID_SERVICE_CONTROL = "service-control"
    const val ID_SETTINGS = "settings"
    const val ID_PERMISSIONS = "permissions"
    const val ID_ABOUT = "about-wuxianpi"

    private val routeById = mapOf(
        ID_DESKTOP to ProductRoute.DESKTOP,
        ID_BASIC to ProductRoute.BASIC,
        ID_ADVANCED to ProductRoute.ADVANCED,
        ID_REPAIR to ProductRoute.REPAIR,
        ID_SERVICE_CONTROL to ProductRoute.SERVICE_CONTROL,
        ID_PERMISSIONS to ProductRoute.PERMISSIONS,
        ID_SETTINGS to ProductRoute.SETTINGS,
        ID_ABOUT to ProductRoute.ABOUT,
        OpenHouseBuiltins.SETUP_ID to ProductRoute.SETUP,
    )

    // These routes remain available to host code, but are temporarily reached through
    // maintenance/repair flows instead of occupying a desktop slot.
    private val hiddenDefaultDesktopIds = setOf(
        OpenHouseBuiltins.SETUP_ID,
        ID_BASIC,
        ID_ADVANCED,
    )

    private val fixedEntries = OpenHouseBuiltins.components()
        .filterNot { it.id in hiddenDefaultDesktopIds }
        .map { DesktopComponent.fromCore(it, routeById[it.id]) }

    // Keep hidden built-ins protected so remote components cannot replace their IDs.
    private val protectedIds = OpenHouseBuiltins.protectedIds()
        .mapTo(linkedSetOf(), DesktopComponent::normalizeId)

    @JvmStatic
    fun fixed(): List<DesktopComponent> = fixedEntries

    @JvmStatic
    fun merge(
        dynamic: List<OpenHouseComponent>?,
        capabilities: HostCapabilities = HostCapabilities.full(),
    ): List<DesktopComponent> {
        val result = ArrayList(fixedEntries.map { component ->
            component.copy(enabled = component.route == null || capabilities.supports(component.route))
        })
        val seen = protectedIds.toMutableSet()
        dynamic.orEmpty()
            .asSequence()
            .filter { it.desktopVisible }
            .map { DesktopComponent.fromCore(it, routeById[it.id]) }
            .filter { it.normalizedId.isNotEmpty() && it.normalizedId !in seen }
            .sortedWith(compareBy<DesktopComponent> { it.order }.thenBy { it.displayTitle() })
            .forEach { component ->
                result += component
                seen += component.normalizedId
            }
        return result
    }

    @JvmStatic
    fun isProtected(id: String?): Boolean = DesktopComponent.normalizeId(id) in protectedIds

}
