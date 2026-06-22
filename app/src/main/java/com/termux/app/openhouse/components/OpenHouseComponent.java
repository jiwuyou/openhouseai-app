package com.termux.app.openhouse.components;

import java.util.Collections;
import java.util.List;

public final class OpenHouseComponent {

    public enum EntryType {
        WEBVIEW,
        NATIVE_PAGE,
        TERMINAL
    }

    public final String id;
    public final String title;
    public final String subtitle;
    public final String section;
    public final int order;
    public final EntryType entryType;
    public final String url;
    public final String nativePage;
    public final String controlTitle;
    public final List<String> serviceNames;
    public final List<String> serviceRefs;

    OpenHouseComponent(
        String id,
        String title,
        String subtitle,
        String section,
        int order,
        EntryType entryType,
        String url,
        String nativePage,
        String controlTitle,
        List<String> serviceNames,
        List<String> serviceRefs
    ) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.section = section;
        this.order = order;
        this.entryType = entryType;
        this.url = url;
        this.nativePage = nativePage;
        this.controlTitle = controlTitle;
        this.serviceNames = serviceNames == null ? Collections.emptyList() : Collections.unmodifiableList(serviceNames);
        this.serviceRefs = serviceRefs == null ? Collections.emptyList() : Collections.unmodifiableList(serviceRefs);
    }

    public boolean hasEntry() {
        return entryType != null;
    }

    public boolean hasControlEntry() {
        return !serviceNames.isEmpty() || !serviceRefs.isEmpty();
    }
}
