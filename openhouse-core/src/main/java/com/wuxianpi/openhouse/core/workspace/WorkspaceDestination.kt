package com.wuxianpi.openhouse.core.workspace

import com.wuxianpi.openhouse.core.ProductRoute

/** A stable, host-neutral address inside the shared OpenHouse workspace. */
sealed class WorkspaceDestination {
    abstract val stableKey: String

    data object Desktop : WorkspaceDestination() {
        override val stableKey: String = "desktop"
    }

    data class Route(val route: ProductRoute) : WorkspaceDestination() {
        init {
            require(route != ProductRoute.DESKTOP) { "Use WorkspaceDestination.Desktop for the desktop route" }
        }

        override val stableKey: String = "route:${route.persistenceKey()}"
    }

    class Component(componentId: String) : WorkspaceDestination() {
        val normalizedComponentId: String = normalizeId(componentId)

        init {
            require(normalizedComponentId.isNotEmpty()) { "Workspace component id must not be blank" }
        }

        override val stableKey: String = "component:$normalizedComponentId"

        override fun equals(other: Any?): Boolean =
            other is Component && normalizedComponentId == other.normalizedComponentId

        override fun hashCode(): Int = normalizedComponentId.hashCode()

        override fun toString(): String = "Component(componentId=$normalizedComponentId)"
    }

    companion object {
        @JvmStatic
        fun forRoute(route: ProductRoute): WorkspaceDestination =
            if (route == ProductRoute.DESKTOP) Desktop else Route(route)

        @JvmStatic
        fun normalizeId(value: String?): String = value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
    }
}
