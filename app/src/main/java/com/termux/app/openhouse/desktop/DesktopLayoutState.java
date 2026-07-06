package com.termux.app.openhouse.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DesktopLayoutState {

    public static final int DEFAULT_PAGE_SIZE = 20;

    public final List<DesktopLayoutEntry> entries;
    public final List<DesktopLayoutEntry> allEntries;
    public final DesktopLaunchTarget defaultTarget;
    public final DesktopLaunchTarget resolvedDefaultTarget;
    public final DesktopLaunchTarget lastExitedTarget;
    public final int currentPage;
    public final int maxSlotIndex;
    public final int maxVisibleSlotIndex;
    public final int maxPersistedSlotIndex;
    final Map<String, Integer> persistedSlotsById;
    final Set<String> persistedHiddenIds;
    final Map<String, DesktopAppOverride> persistedOverrides;

    DesktopLayoutState(
        List<DesktopLayoutEntry> allEntries,
        Map<String, Integer> persistedSlotsById,
        Set<String> persistedHiddenIds,
        Map<String, DesktopAppOverride> persistedOverrides,
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
        this.persistedSlotsById = immutableSlotMap(persistedSlotsById);
        this.persistedHiddenIds = immutableIdSet(persistedHiddenIds);
        this.persistedOverrides = immutableOverrides(persistedOverrides);
        this.maxSlotIndex = maxSlotIndex(this.allEntries);
        this.maxVisibleSlotIndex = maxSlotIndex(this.entries);
        this.maxPersistedSlotIndex = maxSlotValue(this.persistedSlotsById);
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

    public Map<String, Integer> slotsById() {
        return persistedSlotsById;
    }

    public DesktopLayoutEntry entryAtSlot(int slotIndex) {
        int slot = Math.max(0, slotIndex);
        for (DesktopLayoutEntry entry : allEntries) {
            if (entry.slotIndex == slot) {
                return entry;
            }
        }
        return null;
    }

    public DesktopLayoutEntry visibleEntryAtSlot(int slotIndex) {
        int slot = Math.max(0, slotIndex);
        for (DesktopLayoutEntry entry : entries) {
            if (entry.slotIndex == slot) {
                return entry;
            }
        }
        return null;
    }

    public int slotIndexOf(String appId) {
        String id = safeTrim(appId);
        if (id.isEmpty()) {
            return -1;
        }
        Integer slot = persistedSlotsById.get(id);
        return slot == null ? -1 : slot;
    }

    public boolean isSlotOccupied(int slotIndex) {
        return persistedSlotsById.containsValue(Math.max(0, slotIndex));
    }

    public List<DesktopLayoutSlot> slotsForPage(int pageIndex, int pageSize) {
        int safePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        int page = Math.max(0, pageIndex);
        int start = page * safePageSize;
        List<DesktopLayoutSlot> slots = new ArrayList<>(safePageSize);
        for (int offset = 0; offset < safePageSize; offset++) {
            int slotIndex = start + offset;
            slots.add(new DesktopLayoutSlot(slotIndex, safePageSize, visibleEntryAtSlot(slotIndex)));
        }
        return Collections.unmodifiableList(slots);
    }

    public int pageCount(int pageSize) {
        int safePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        int maxSlot = Math.max(maxVisibleSlotIndex, currentPage * safePageSize);
        return maxSlot < 0 ? 1 : (maxSlot / safePageSize) + 1;
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

    private static Map<String, Integer> immutableSlotMap(Map<String, Integer> slotsById) {
        if (slotsById == null || slotsById.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : slotsById.entrySet()) {
            String id = safeTrim(entry.getKey());
            Integer slot = entry.getValue();
            if (!id.isEmpty() && slot != null && slot >= 0) {
                clean.put(id, slot);
            }
        }
        return clean.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(clean);
    }

    private static Set<String> immutableIdSet(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> clean = new LinkedHashSet<>();
        for (String rawId : ids) {
            String id = safeTrim(rawId);
            if (!id.isEmpty()) {
                clean.add(id);
            }
        }
        return clean.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(clean);
    }

    private static Map<String, DesktopAppOverride> immutableOverrides(Map<String, DesktopAppOverride> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DesktopAppOverride> clean = new LinkedHashMap<>();
        for (Map.Entry<String, DesktopAppOverride> entry : overrides.entrySet()) {
            String id = safeTrim(entry.getKey());
            DesktopAppOverride override = entry.getValue();
            if (!id.isEmpty() && override != null && !override.isEmpty()) {
                clean.put(id, override);
            }
        }
        return clean.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(clean);
    }

    private static int maxSlotIndex(List<DesktopLayoutEntry> entries) {
        int max = -1;
        if (entries == null) {
            return max;
        }
        for (DesktopLayoutEntry entry : entries) {
            if (entry != null) {
                max = Math.max(max, entry.slotIndex);
            }
        }
        return max;
    }

    private static int maxSlotValue(Map<String, Integer> slotsById) {
        int max = -1;
        if (slotsById == null) {
            return max;
        }
        for (Integer slot : slotsById.values()) {
            if (slot != null && slot >= 0) {
                max = Math.max(max, slot);
            }
        }
        return max;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
