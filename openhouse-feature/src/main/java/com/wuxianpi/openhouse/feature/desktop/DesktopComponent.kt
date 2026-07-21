package com.wuxianpi.openhouse.feature.desktop

import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent

data class DesktopComponent(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val iconKey: String = "app",
    val iconLabel: String = "",
    val order: Int = 0,
    val route: ProductRoute? = null,
    val fixed: Boolean = false,
    val enabled: Boolean = true,
    val source: OpenHouseComponent,
) {
    val normalizedId: String = normalizeId(id)

    init {
        require(normalizedId.isNotEmpty()) { "Desktop component id must not be blank" }
    }

    fun displayTitle(): String = title.trim().ifEmpty { normalizedId }

    fun displayIconLabel(): String {
        val value = iconLabel.trim().ifEmpty {
            iconKey.trim().ifEmpty { displayTitle() }
        }
        val count = value.codePointCount(0, value.length)
        return if (count <= 2) value else value.substring(0, value.offsetByCodePoints(0, 1))
    }

    companion object {
        @JvmStatic
        fun fromCore(component: OpenHouseComponent, route: ProductRoute?): DesktopComponent = DesktopComponent(
            id = component.id,
            title = component.title,
            subtitle = component.subtitle,
            iconKey = component.iconKey,
            iconLabel = component.iconLabel,
            order = component.desktopOrder,
            route = route,
            fixed = component.protectedEntry,
            enabled = component.hasEntry(),
            source = component,
        )

        @JvmStatic
        fun normalizeId(value: String?): String = value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
    }
}
