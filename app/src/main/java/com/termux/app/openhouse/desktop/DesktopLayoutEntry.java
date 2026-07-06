package com.termux.app.openhouse.desktop;

import com.termux.app.openhouse.components.OpenHouseComponent;

public final class DesktopLayoutEntry {

    public final String id;
    public final String title;
    public final String originalTitle;
    public final String subtitle;
    public final String section;
    public final int slotIndex;
    public final int position;
    public final int orderIndex;
    public final int defaultOrder;
    public final String iconKey;
    public final String iconLabel;
    public final String iconColor;
    public final boolean hidden;
    public final boolean pinned;
    public final boolean home;
    public final DesktopAppEntry appEntry;
    public final DesktopAppDescriptor descriptor;
    public final DesktopAppOverride override;
    public final OpenHouseComponent component;

    DesktopLayoutEntry(
        OpenHouseComponent component,
        DesktopAppDescriptor descriptor,
        DesktopAppOverride override,
        int slotIndex,
        int orderIndex,
        boolean hidden
    ) {
        this.component = component;
        this.descriptor = descriptor;
        this.override = override == null ? DesktopAppOverride.EMPTY : override;
        this.id = safeTrim(component == null ? "" : component.id);
        this.originalTitle = safeTrim(component == null ? "" : component.title);
        this.title = firstNonBlank(this.override.title, originalTitle, id);
        this.subtitle = safeTrim(component == null ? "" : component.subtitle);
        this.section = safeTrim(component == null ? "" : component.section);
        this.slotIndex = Math.max(0, slotIndex);
        this.position = this.slotIndex;
        this.orderIndex = orderIndex;
        this.defaultOrder = component == null ? 0 : component.desktopOrder;
        this.iconKey = firstNonBlank(this.override.icon.key, component == null ? "" : component.iconKey, "app");
        this.iconLabel = firstNonBlank(this.override.icon.label, component == null ? "" : component.iconLabel, deriveIconLabel(title));
        this.iconColor = safeTrim(this.override.icon.color);
        this.hidden = hidden;
        this.pinned = component != null && component.desktopPinned;
        this.home = component != null && component.desktopHome;
        this.appEntry = descriptor == null ? DesktopAppEntry.unknown("") : descriptor.entry;
    }

    public int pageIndex(int pageSize) {
        int safePageSize = pageSize <= 0 ? 1 : pageSize;
        return position / safePageSize;
    }

    public int indexInPage(int pageSize) {
        int safePageSize = pageSize <= 0 ? 1 : pageSize;
        return position % safePageSize;
    }

    public boolean hasTitleOverride() {
        return !override.title.isEmpty();
    }

    public boolean hasIconOverride() {
        return !override.icon.isEmpty();
    }

    private static String deriveIconLabel(String title) {
        String text = safeTrim(title);
        return text.length() <= 2 ? text : text.substring(0, 1);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = safeTrim(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
