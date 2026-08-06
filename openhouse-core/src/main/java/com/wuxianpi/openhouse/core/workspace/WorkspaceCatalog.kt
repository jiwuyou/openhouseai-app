package com.wuxianpi.openhouse.core.workspace

import com.wuxianpi.openhouse.core.HostCapabilities
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import java.security.MessageDigest

data class WorkspaceCatalogEntry(
    val destination: WorkspaceDestination,
    val title: String,
    val subtitle: String,
    val section: String,
    val order: Int,
    val component: OpenHouseComponent,
)

/** Builds the daily application switcher without depending on either Android host. */
object WorkspaceCatalog {
    private val dailyRouteIds = linkedMapOf(
        OpenHouseBuiltins.BASIC_ID to ProductRoute.BASIC,
        OpenHouseBuiltins.REPAIR_ID to ProductRoute.REPAIR,
    )

    @JvmStatic
    fun applications(
        dynamicComponents: List<OpenHouseComponent>?,
        capabilities: HostCapabilities = HostCapabilities.full(),
    ): List<WorkspaceCatalogEntry> {
        val entries = ArrayList<WorkspaceCatalogEntry>()
        val builtins = OpenHouseBuiltins.components().associateBy { it.id }
        dailyRouteIds.forEach { (id, route) ->
            val component = builtins[id] ?: return@forEach
            if (capabilities.supports(route)) {
                entries += component.toWorkspaceEntry(WorkspaceDestination.forRoute(route))
            }
        }

        val seen = OpenHouseBuiltins.protectedIds()
            .mapTo(linkedSetOf(), WorkspaceDestination::normalizeId)
        dynamicComponents.orEmpty()
            .asSequence()
            .filter { it.visible && it.hasEntry() }
            .filter { it.entryType == OpenHouseComponent.EntryType.WEBVIEW ||
                it.entryType == OpenHouseComponent.EntryType.ANDROID_ACTIVITY ||
                it.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE }
            .filter { WorkspaceDestination.normalizeId(it.id).isNotEmpty() }
            .filter { seen.add(WorkspaceDestination.normalizeId(it.id)) }
            .map { component ->
                component.toWorkspaceEntry(WorkspaceDestination.Component(component.id))
            }
            .sortedWith(
                compareBy<WorkspaceCatalogEntry> { it.section }
                    .thenBy { it.order }
                    .thenBy { it.title },
            )
            .forEach(entries::add)
        return entries
    }

    /** Invalidates cached workspace pages whenever the effective registry contract changes. */
    @JvmStatic
    fun componentFingerprint(component: OpenHouseComponent): String {
        val fields = listOf(
            component.id,
            component.title,
            component.subtitle,
            component.section,
            component.order.toString(),
            component.iconKey,
            component.iconLabel,
            component.entryType?.name.orEmpty(),
            component.url,
            component.nativePage,
            component.activityClassName,
            component.controlTitle,
            component.visible.toString(),
            component.source,
            "serviceNames=${component.serviceNames.size}",
        ) + component.serviceNames +
            listOf("serviceRefs=${component.serviceRefs.size}") + component.serviceRefs
        val encoded = buildString {
            fields.forEach { value -> append(value.length).append(':').append(value).append(';') }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun OpenHouseComponent.toWorkspaceEntry(
        destination: WorkspaceDestination,
    ): WorkspaceCatalogEntry = WorkspaceCatalogEntry(
        destination = destination,
        title = title.ifBlank { id },
        subtitle = subtitle,
        section = section.ifBlank { "apps" },
        order = order,
        component = this,
    )
}
