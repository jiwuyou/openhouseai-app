package com.wuxianpi.openhouse.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OpenHouseComponent {
    public enum EntryType { WEBVIEW, NATIVE_PAGE, TERMINAL, SERVICE_CONTROL, ANDROID_ACTIVITY }

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

    OpenHouseComponent(String id, String title, String subtitle, String section, int order,
                       String iconKey, String iconLabel, int desktopOrder, boolean desktopPinned,
                       boolean desktopHome, boolean desktopVisible, EntryType entryType, String url,
                       String nativePage, String activityClassName, String controlTitle, boolean visible,
                       boolean favorite, boolean home, boolean protectedEntry, String source,
                       List<String> serviceNames, List<String> serviceRefs) {
        this.id = text(id);
        this.title = text(title);
        this.subtitle = text(subtitle);
        this.section = text(section);
        this.order = order;
        this.iconKey = text(iconKey).isEmpty() ? "app" : text(iconKey);
        this.iconLabel = text(iconLabel);
        this.desktopOrder = desktopOrder;
        this.desktopPinned = desktopPinned;
        this.desktopHome = desktopHome;
        this.desktopVisible = desktopVisible;
        this.entryType = entryType;
        this.url = text(url);
        this.nativePage = text(nativePage);
        this.activityClassName = text(activityClassName);
        this.controlTitle = text(controlTitle);
        this.visible = visible;
        this.favorite = favorite;
        this.home = home;
        this.protectedEntry = protectedEntry;
        this.source = text(source);
        this.serviceNames = immutable(serviceNames);
        this.serviceRefs = immutable(serviceRefs);
    }

    public boolean hasEntry() { return entryType != null; }
    public boolean hasControlEntry() { return !serviceNames.isEmpty() || !serviceRefs.isEmpty() || entryType == EntryType.SERVICE_CONTROL; }
    public boolean isServiceBacked() { return !serviceNames.isEmpty() || !serviceRefs.isEmpty(); }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static List<String> immutable(List<String> values) {
        return values == null || values.isEmpty() ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
