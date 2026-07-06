package com.termux.app.openhouse.components;

import java.util.Collections;
import java.util.List;

public final class OpenHouseComponent {

    public enum EntryType {
        WEBVIEW,
        NATIVE_PAGE,
        TERMINAL,
        SERVICE_CONTROL,
        ANDROID_ACTIVITY
    }

    public final String id;
    public final String title;
    public final String subtitle;
    public final String section;
    public final int order;
    public final String iconKey;
    public final String iconLabel;
    public final int desktopOrder;
    public final boolean desktopPinned;
    public final boolean desktopHome;
    public final boolean desktopVisible;
    public final EntryType entryType;
    public final String url;
    public final String nativePage;
    public final String activityClassName;
    public final String controlTitle;
    public final boolean visible;
    public final boolean favorite;
    public final boolean home;
    public final boolean protectedEntry;
    public final String source;
    public final List<String> serviceNames;
    public final List<String> serviceRefs;

    OpenHouseComponent(
        String id,
        String title,
        String subtitle,
        String section,
        int order,
        String iconKey,
        String iconLabel,
        int desktopOrder,
        boolean desktopPinned,
        boolean desktopHome,
        boolean desktopVisible,
        EntryType entryType,
        String url,
        String nativePage,
        String activityClassName,
        String controlTitle,
        boolean visible,
        boolean favorite,
        boolean home,
        boolean protectedEntry,
        String source,
        List<String> serviceNames,
        List<String> serviceRefs
    ) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.section = section;
        this.order = order;
        this.iconKey = isBlank(iconKey) ? "app" : iconKey.trim();
        this.iconLabel = isBlank(iconLabel) ? deriveIconLabel(title) : iconLabel.trim();
        this.desktopOrder = desktopOrder;
        this.desktopPinned = desktopPinned;
        this.desktopHome = desktopHome;
        this.desktopVisible = desktopVisible;
        this.entryType = entryType;
        this.url = url;
        this.nativePage = nativePage;
        this.activityClassName = activityClassName;
        this.controlTitle = controlTitle;
        this.visible = visible;
        this.favorite = favorite;
        this.home = home;
        this.protectedEntry = protectedEntry;
        this.source = source == null ? "" : source;
        this.serviceNames = serviceNames == null ? Collections.emptyList() : Collections.unmodifiableList(serviceNames);
        this.serviceRefs = serviceRefs == null ? Collections.emptyList() : Collections.unmodifiableList(serviceRefs);
    }

    public boolean hasEntry() {
        return entryType != null;
    }

    public boolean hasControlEntry() {
        return !serviceNames.isEmpty() || !serviceRefs.isEmpty();
    }

    public boolean isNavigationVisible() {
        return visible;
    }

    public boolean isDesktopVisible() {
        return desktopVisible;
    }

    public boolean isDesktopPinned() {
        return desktopPinned;
    }

    public boolean isDesktopHome() {
        return desktopHome;
    }

    public boolean isServiceBacked() {
        return !serviceNames.isEmpty() || !serviceRefs.isEmpty();
    }

    public String entryTypeName() {
        return entryType == null ? "" : entryType.name().toLowerCase().replace('_', '-');
    }

    private static String deriveIconLabel(String title) {
        if (isBlank(title)) {
            return "";
        }
        String trimmed = title.trim();
        return trimmed.length() <= 2 ? trimmed : trimmed.substring(0, 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
