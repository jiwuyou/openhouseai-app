package com.wuxianpi.openhouse.feature.desktop

data class DesktopIconOverride(
    val key: String = "",
    val label: String = "",
    val color: String = "",
) {
    val isEmpty: Boolean get() = key.isBlank() && label.isBlank() && color.isBlank()
}

data class DesktopAppOverride(
    val title: String = "",
    val icon: DesktopIconOverride = DesktopIconOverride(),
) {
    val isEmpty: Boolean get() = title.isBlank() && icon.isEmpty
}

data class DesktopLayoutEntry(
    val component: DesktopComponent,
    val slotIndex: Int,
    val hidden: Boolean,
    val override: DesktopAppOverride,
) {
    val id: String get() = component.normalizedId
    val title: String get() = override.title.trim().ifEmpty { component.displayTitle() }
    val subtitle: String get() = component.subtitle
    val iconKey: String get() = override.icon.key.trim().ifEmpty { component.iconKey }
    val iconLabel: String get() = override.icon.label.trim().ifEmpty { component.displayIconLabel() }
}

data class DesktopLayoutState(
    val allEntries: List<DesktopLayoutEntry>,
    val currentPage: Int,
) {
    val entries: List<DesktopLayoutEntry> = allEntries.filterNot { it.hidden }

    fun find(id: String?): DesktopLayoutEntry? {
        val normalized = DesktopComponent.normalizeId(id)
        return allEntries.firstOrNull { it.id == normalized }
    }
}
