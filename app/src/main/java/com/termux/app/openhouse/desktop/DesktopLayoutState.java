package com.termux.app.openhouse.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesktopLayoutState {

    public static final int DEFAULT_PAGE_SIZE = 20;

    public final List<DesktopLayoutEntry> entries;
    public final List<DesktopLayoutEntry> allEntries;
    public final DesktopLaunchTarget defaultTarget;
    public final DesktopLaunchTarget resolvedDefaultTarget;
    public final DesktopLaunchTarget lastExitedTarget;
    public final int currentPage;

    DesktopLayoutState(
        List<DesktopLayoutEntry> allEntries,
        DesktopLaunchTarget defaultTarget,
        DesktopLaunchTarget resolvedDefaultTarget,
        DesktopLaunchTarget lastExitedTarget,
        int currentPage
    ) {
        this.allEntries = immutableEntries(allEntries);
        this.entries = immutableEntries(filterVisible(allEntries));
        this.defaultTarget = defaultTarget == null ? DesktopLaunchTarget.desktop() : defaultTarget;
        this.resolvedDefaultTarget = resolvedDefaultTarget == null ? DesktopLaunchTarget.desktop() : resolvedDefaultTarget;
        this.lastExitedTarget = lastExitedTarget == null ? DesktopLaunchTarget.desktop() : lastExitedTarget;
        this.currentPage = Math.max(0, currentPage);
    }

    public DesktopLayoutEntry find(String appId) {
        String id = safeTrim(appId);
        if (id.isEmpty()) {
            return null;
        }
        for (DesktopLayoutEntry entry : allEntries) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    public boolean containsApp(String appId) {
        return find(appId) != null;
    }

    public List<String> orderedIds() {
        List<String> ids = new ArrayList<>();
        for (DesktopLayoutEntry entry : allEntries) {
            ids.add(entry.id);
        }
        return Collections.unmodifiableList(ids);
    }

    public List<String> visibleOrderedIds() {
        List<String> ids = new ArrayList<>();
        for (DesktopLayoutEntry entry : entries) {
            ids.add(entry.id);
        }
        return Collections.unmodifiableList(ids);
    }

    public Map<String, DesktopLayoutEntry> entriesById() {
        Map<String, DesktopLayoutEntry> byId = new LinkedHashMap<>();
        for (DesktopLayoutEntry entry : allEntries) {
            byId.put(entry.id, entry);
        }
        return Collections.unmodifiableMap(byId);
    }

    public int pageCount(int pageSize) {
        int safePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        if (entries.isEmpty()) {
            return 1;
        }
        return ((entries.size() - 1) / safePageSize) + 1;
    }

    private static List<DesktopLayoutEntry> filterVisible(List<DesktopLayoutEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<DesktopLayoutEntry> visible = new ArrayList<>();
        for (DesktopLayoutEntry entry : entries) {
            if (entry != null && !entry.hidden) {
                visible.add(entry);
            }
        }
        return visible;
    }

    private static List<DesktopLayoutEntry> immutableEntries(List<DesktopLayoutEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<DesktopLayoutEntry> clean = new ArrayList<>();
        for (DesktopLayoutEntry entry : entries) {
            if (entry != null && !entry.id.isEmpty()) {
                clean.add(entry);
            }
        }
        return clean.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(clean);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
