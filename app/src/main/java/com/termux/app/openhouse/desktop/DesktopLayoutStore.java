package com.termux.app.openhouse.desktop;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.openhouse.components.OpenHouseComponent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DesktopLayoutStore {

    private static final String PREFS_NAME = "openhouse_desktop";
    private static final String KEY_LAYOUT_STATE = "layout_state_v1";
    private static final String KEY_DEFAULT_KIND = "default_target_kind";
    private static final String KEY_DEFAULT_VALUE = "default_target_value";
    private static final String KEY_LAST_EXIT_KIND = "last_exit_target_kind";
    private static final String KEY_LAST_EXIT_VALUE = "last_exit_target_value";
    private static final int STATE_VERSION = 2;

    private final SharedPreferences preferences;

    public DesktopLayoutStore(Context context) {
        Context appContext = context == null ? null : context.getApplicationContext();
        this.preferences = appContext == null ? null
            : appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public DesktopLayoutState load(List<OpenHouseComponent> components) {
        return buildState(components, readSavedLayout());
    }

    public DesktopLayoutState merge(List<OpenHouseComponent> components) {
        DesktopLayoutState state = load(components);
        persistLayout(state);
        return state;
    }

    public void save(DesktopLayoutState state) {
        if (state == null) {
            return;
        }
        persistLayout(state);
        writeTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, state.defaultTarget);
        writeTarget(KEY_LAST_EXIT_KIND, KEY_LAST_EXIT_VALUE, state.lastExitedTarget);
    }

    public DesktopLayoutState reorder(List<OpenHouseComponent> components, List<String> orderedAppIds) {
        SavedLayout saved = readSavedLayout();
        DesktopLayoutState current = buildState(components, saved);
        Set<String> requestedOrder = new LinkedHashSet<>(sanitizeOrderedIds(orderedAppIds));
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : requestedOrder) {
            if (current.find(id) != null) {
                ordered.add(id);
            }
        }
        Map<String, Integer> slots = new LinkedHashMap<>(current.persistedSlotsById);
        Set<Integer> reservedSlots = new HashSet<>();
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            if (!ordered.contains(entry.getKey()) && entry.getValue() != null && entry.getValue() >= 0) {
                reservedSlots.add(entry.getValue());
            }
        }
        int nextSlot = 0;
        for (String id : ordered) {
            nextSlot = firstFreeSlot(reservedSlots, nextSlot);
            slots.put(id, nextSlot);
            reservedSlots.add(nextSlot);
            nextSlot++;
        }
        saved.slotById = slots;
        saved.orderedIds = sortedIdsBySlot(slots);
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState move(List<OpenHouseComponent> components, String appId, int targetIndex) {
        DesktopLayoutState current = load(components);
        List<String> ids = current.orderedIds();
        String id = safeId(appId);
        if (id.isEmpty() || !ids.remove(id)) {
            return current;
        }
        int index = Math.max(0, Math.min(targetIndex, ids.size()));
        ids.add(index, id);
        return reorder(components, ids);
    }

    public DesktopLayoutState moveToSlot(List<OpenHouseComponent> components, String appId, int targetSlot) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        DesktopLayoutState current = buildState(components, saved);
        if (current.find(id) == null) {
            return current;
        }
        Map<String, Integer> slots = new LinkedHashMap<>(current.persistedSlotsById);
        Integer fromSlot = slots.get(id);
        if (fromSlot == null || fromSlot < 0) {
            fromSlot = firstFreeSlot(new HashSet<Integer>(slots.values()), 0);
        }
        int target = Math.max(0, targetSlot);
        String occupant = idAtSlot(slots, target, id);
        slots.put(id, target);
        if (occupant != null) {
            slots.put(occupant, fromSlot);
        }
        saved.slotById = slots;
        saved.orderedIds = sortedIdsBySlot(slots);
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState moveToPageSlot(
        List<OpenHouseComponent> components,
        String appId,
        int pageIndex,
        int indexInPage,
        int pageSize
    ) {
        int safePageSize = pageSize <= 0 ? DesktopLayoutState.DEFAULT_PAGE_SIZE : pageSize;
        int targetSlot = Math.max(0, pageIndex) * safePageSize + Math.max(0, Math.min(indexInPage, safePageSize - 1));
        return moveToSlot(components, appId, targetSlot);
    }

    public DesktopLayoutState updateOverride(
        List<OpenHouseComponent> components,
        String appId,
        String title,
        DesktopIconOverride icon
    ) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        DesktopAppOverride override = DesktopAppOverride.of(title, icon);
        if (override.isEmpty()) {
            saved.overrides.remove(id);
        } else {
            saved.overrides.put(id, override);
        }
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState updateTitleOverride(List<OpenHouseComponent> components, String appId, String title) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        DesktopAppOverride current = saved.overrides.get(id);
        DesktopAppOverride override = DesktopAppOverride.of(title, current == null ? DesktopIconOverride.EMPTY : current.icon);
        if (override.isEmpty()) {
            saved.overrides.remove(id);
        } else {
            saved.overrides.put(id, override);
        }
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState updateIconOverride(List<OpenHouseComponent> components, String appId, DesktopIconOverride icon) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        DesktopAppOverride current = saved.overrides.get(id);
        DesktopAppOverride override = DesktopAppOverride.of(current == null ? "" : current.title, icon);
        if (override.isEmpty()) {
            saved.overrides.remove(id);
        } else {
            saved.overrides.put(id, override);
        }
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState clearOverride(List<OpenHouseComponent> components, String appId) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        saved.overrides.remove(id);
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState hide(List<OpenHouseComponent> components, String appId, boolean hidden) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        if (hidden) {
            saved.hiddenIds.add(id);
        } else {
            saved.hiddenIds.remove(id);
        }
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState saveCurrentPage(List<OpenHouseComponent> components, int currentPage) {
        SavedLayout saved = readSavedLayout();
        saved.currentPage = Math.max(0, currentPage);
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState resetApp(List<OpenHouseComponent> components, String appId) {
        String id = safeId(appId);
        if (id.isEmpty()) {
            return load(components);
        }
        SavedLayout saved = readSavedLayout();
        saved.hiddenIds.remove(id);
        saved.overrides.remove(id);
        saved.slotById.remove(id);
        saved.orderedIds = sortedIdsBySlot(saved.slotById);
        DesktopLayoutState state = buildState(components, saved);
        persistLayout(state);
        return state;
    }

    public DesktopLayoutState reset(List<OpenHouseComponent> components) {
        if (preferences != null) {
            preferences.edit().remove(KEY_LAYOUT_STATE).apply();
        }
        return load(components);
    }

    public DesktopLayoutState resetAll(List<OpenHouseComponent> components) {
        if (preferences != null) {
            preferences.edit()
                .remove(KEY_LAYOUT_STATE)
                .remove(KEY_DEFAULT_KIND)
                .remove(KEY_DEFAULT_VALUE)
                .remove(KEY_LAST_EXIT_KIND)
                .remove(KEY_LAST_EXIT_VALUE)
                .apply();
        }
        return load(components);
    }

    public DesktopLaunchTarget loadDefaultTarget(List<OpenHouseComponent> components) {
        return resolveTarget(readTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, DesktopLaunchTarget.desktop()),
            appIds(components));
    }

    public DesktopLaunchTarget setDefaultTarget(List<OpenHouseComponent> components, DesktopLaunchTarget target) {
        DesktopLaunchTarget resolved = resolveTarget(target, appIds(components));
        writeTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, target == null ? DesktopLaunchTarget.desktop() : target);
        return resolved;
    }

    public DesktopLaunchTarget setDefaultDesktop() {
        DesktopLaunchTarget target = DesktopLaunchTarget.desktop();
        writeTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, target);
        return target;
    }

    public DesktopLaunchTarget setDefaultApp(List<OpenHouseComponent> components, String appId) {
        return setDefaultTarget(components, DesktopLaunchTarget.app(appId));
    }

    public DesktopLaunchTarget setDefaultLastExited() {
        DesktopLaunchTarget target = DesktopLaunchTarget.lastExited();
        writeTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, target);
        return target;
    }

    public void recordLastExitedTarget(DesktopLaunchTarget target) {
        DesktopLaunchTarget cleanTarget = target == null || target.isLastExited()
            ? DesktopLaunchTarget.desktop()
            : target;
        writeTarget(KEY_LAST_EXIT_KIND, KEY_LAST_EXIT_VALUE, cleanTarget);
    }

    public DesktopLaunchTarget resolveDefaultTarget(List<OpenHouseComponent> components) {
        return resolveTarget(readTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, DesktopLaunchTarget.desktop()),
            appIds(components));
    }

    private DesktopLayoutState buildState(List<OpenHouseComponent> components, SavedLayout saved) {
        List<OpenHouseComponent> sorted = sortComponents(components);
        Map<String, OpenHouseComponent> available = new LinkedHashMap<>();
        for (OpenHouseComponent component : sorted) {
            String id = safeId(component == null ? "" : component.id);
            if (!id.isEmpty() && component.isDesktopVisible() && !available.containsKey(id)) {
                available.put(id, component);
            }
        }

        Map<String, Integer> slotsById = mergeSlots(saved, available);
        List<String> orderedIds = sortedCurrentIdsBySlot(slotsById, available);
        List<DesktopLayoutEntry> entries = new ArrayList<>();
        int orderIndex = 0;
        for (String id : orderedIds) {
            OpenHouseComponent component = available.get(id);
            if (component == null) {
                continue;
            }
            DesktopAppOverride override = saved.overrides.get(id);
            boolean hidden = saved.hiddenIds.contains(id);
            Integer slot = slotsById.get(id);
            entries.add(new DesktopLayoutEntry(
                component,
                DesktopAppDescriptor.fromComponent(component),
                override,
                slot == null ? orderIndex : slot,
                orderIndex,
                hidden));
            orderIndex++;
        }

        Set<String> ids = available.keySet();
        DesktopLaunchTarget defaultTarget = readTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, DesktopLaunchTarget.desktop());
        DesktopLaunchTarget lastTarget = readTarget(KEY_LAST_EXIT_KIND, KEY_LAST_EXIT_VALUE, DesktopLaunchTarget.desktop());
        DesktopLaunchTarget resolvedDefault = resolveTarget(defaultTarget, ids);
        return new DesktopLayoutState(
            entries,
            slotsById,
            saved.hiddenIds,
            saved.overrides,
            defaultTarget,
            resolvedDefault,
            lastTarget,
            saved.currentPage);
    }

    private void persistLayout(DesktopLayoutState state) {
        if (preferences == null || state == null) {
            return;
        }
        JSONObject root = new JSONObject();
        try {
            root.put("version", STATE_VERSION);
            root.put("currentPage", state.currentPage);

            JSONArray orderedIds = new JSONArray();
            JSONArray slots = new JSONArray();
            JSONArray hiddenIds = new JSONArray();
            JSONObject overrides = new JSONObject();
            for (String id : sortedIdsBySlot(state.persistedSlotsById)) {
                Integer slot = state.persistedSlotsById.get(id);
                if (slot == null || slot < 0) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("slot", slot);
                slots.put(item);
                orderedIds.put(id);
            }
            for (String id : state.persistedHiddenIds) {
                hiddenIds.put(id);
            }
            for (Map.Entry<String, DesktopAppOverride> entry : state.persistedOverrides.entrySet()) {
                overrides.put(entry.getKey(), entry.getValue().toJson());
            }
            root.put("slots", slots);
            root.put("orderedIds", orderedIds);
            root.put("hiddenIds", hiddenIds);
            root.put("overrides", overrides);
        } catch (Exception ignored) {
            return;
        }
        preferences.edit().putString(KEY_LAYOUT_STATE, root.toString()).apply();
    }

    private SavedLayout readSavedLayout() {
        SavedLayout saved = new SavedLayout();
        if (preferences == null) {
            return saved;
        }
        String raw = preferences.getString(KEY_LAYOUT_STATE, "");
        if (raw == null || raw.trim().isEmpty()) {
            return saved;
        }
        try {
            JSONObject root = new JSONObject(raw);
            saved.currentPage = Math.max(0, root.optInt("currentPage", 0));
            saved.orderedIds = readStringArray(root.optJSONArray("orderedIds"));
            saved.slotById = readSlotMap(root);
            if (saved.slotById.isEmpty() && !saved.orderedIds.isEmpty()) {
                saved.slotById = slotsFromOrderedIds(saved.orderedIds);
            }
            saved.hiddenIds = new LinkedHashSet<>(readStringArray(root.optJSONArray("hiddenIds")));
            JSONObject overrides = root.optJSONObject("overrides");
            if (overrides != null) {
                JSONArray names = overrides.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String id = safeId(names.optString(i, ""));
                        DesktopAppOverride override = DesktopAppOverride.fromJson(overrides.optJSONObject(id));
                        if (!id.isEmpty() && !override.isEmpty()) {
                            saved.overrides.put(id, override);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return new SavedLayout();
        }
        return saved;
    }

    private DesktopLaunchTarget readTarget(String kindKey, String valueKey, DesktopLaunchTarget fallback) {
        if (preferences == null) {
            return fallback == null ? DesktopLaunchTarget.desktop() : fallback;
        }
        return DesktopLaunchTarget.parse(
            preferences.getString(kindKey, fallback == null ? "" : fallback.persistedKind()),
            preferences.getString(valueKey, fallback == null ? "" : fallback.value));
    }

    private void writeTarget(String kindKey, String valueKey, DesktopLaunchTarget target) {
        if (preferences == null) {
            return;
        }
        DesktopLaunchTarget cleanTarget = target == null ? DesktopLaunchTarget.desktop() : target;
        preferences.edit()
            .putString(kindKey, cleanTarget.persistedKind())
            .putString(valueKey, cleanTarget.value)
            .apply();
    }

    private DesktopLaunchTarget resolveTarget(DesktopLaunchTarget target, Set<String> availableIds) {
        DesktopLaunchTarget cleanTarget = target == null ? DesktopLaunchTarget.desktop() : target;
        if (cleanTarget.isDesktop()) {
            return cleanTarget;
        }
        if (cleanTarget.isApp()) {
            return availableIds != null && availableIds.contains(cleanTarget.value)
                ? cleanTarget
                : DesktopLaunchTarget.desktop();
        }
        if (cleanTarget.isPage()) {
            return cleanTarget;
        }
        if (cleanTarget.isLastExited()) {
            DesktopLaunchTarget lastTarget = readTarget(KEY_LAST_EXIT_KIND, KEY_LAST_EXIT_VALUE, DesktopLaunchTarget.desktop());
            if (lastTarget.isLastExited()) {
                return DesktopLaunchTarget.desktop();
            }
            return resolveTarget(lastTarget, availableIds);
        }
        return DesktopLaunchTarget.desktop();
    }

    private List<OpenHouseComponent> sortComponents(List<OpenHouseComponent> components) {
        if (components == null || components.isEmpty()) {
            return Collections.emptyList();
        }
        List<OpenHouseComponent> sorted = new ArrayList<>();
        for (OpenHouseComponent component : components) {
            if (component != null) {
                sorted.add(component);
            }
        }
        Collections.sort(sorted, new Comparator<OpenHouseComponent>() {
            @Override
            public int compare(OpenHouseComponent left, OpenHouseComponent right) {
                int orderCompare = Integer.compare(left.desktopOrder, right.desktopOrder);
                if (orderCompare != 0) {
                    return orderCompare;
                }
                int sectionCompare = safeTrim(left.section).compareToIgnoreCase(safeTrim(right.section));
                if (sectionCompare != 0) {
                    return sectionCompare;
                }
                return safeTrim(left.title).compareToIgnoreCase(safeTrim(right.title));
            }
        });
        return sorted;
    }

    private Map<String, Integer> mergeSlots(SavedLayout saved, Map<String, OpenHouseComponent> available) {
        Map<String, Integer> raw = saved == null ? Collections.<String, Integer>emptyMap() : saved.slotById;
        Map<String, Integer> merged = normalizeSlots(raw);
        Set<Integer> occupied = new HashSet<>(merged.values());
        if (available != null) {
            for (String id : available.keySet()) {
                if (merged.containsKey(id)) {
                    continue;
                }
                int slot = firstFreeSlot(occupied, 0);
                merged.put(id, slot);
                occupied.add(slot);
            }
        }
        return merged;
    }

    private Map<String, Integer> normalizeSlots(Map<String, Integer> slotsById) {
        if (slotsById == null || slotsById.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<SlotRecord> records = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, Integer> entry : slotsById.entrySet()) {
            String id = safeId(entry.getKey());
            Integer slot = entry.getValue();
            if (!id.isEmpty() && slot != null && slot >= 0) {
                records.add(new SlotRecord(id, slot, order++));
            }
        }
        Collections.sort(records, new Comparator<SlotRecord>() {
            @Override
            public int compare(SlotRecord left, SlotRecord right) {
                int slotCompare = Integer.compare(left.slot, right.slot);
                if (slotCompare != 0) {
                    return slotCompare;
                }
                return Integer.compare(left.order, right.order);
            }
        });
        Map<String, Integer> clean = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        Set<Integer> occupied = new HashSet<>();
        for (SlotRecord record : records) {
            if (!seenIds.add(record.id)) {
                continue;
            }
            int slot = occupied.contains(record.slot)
                ? firstFreeSlot(occupied, record.slot)
                : record.slot;
            clean.put(record.id, slot);
            occupied.add(slot);
        }
        return clean;
    }

    private List<String> sortedCurrentIdsBySlot(Map<String, Integer> slotsById, Map<String, OpenHouseComponent> available) {
        if (available == null || available.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sorted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : sortedIdsBySlot(slotsById)) {
            if (available.containsKey(id) && seen.add(id)) {
                sorted.add(id);
            }
        }
        for (String id : available.keySet()) {
            if (seen.add(id)) {
                sorted.add(id);
            }
        }
        return sorted;
    }

    private List<String> sortedIdsBySlot(Map<String, Integer> slotsById) {
        if (slotsById == null || slotsById.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(slotsById.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> left, Map.Entry<String, Integer> right) {
                int leftSlot = left.getValue() == null ? Integer.MAX_VALUE : left.getValue();
                int rightSlot = right.getValue() == null ? Integer.MAX_VALUE : right.getValue();
                int slotCompare = Integer.compare(leftSlot, rightSlot);
                if (slotCompare != 0) {
                    return slotCompare;
                }
                return safeTrim(left.getKey()).compareToIgnoreCase(safeTrim(right.getKey()));
            }
        });
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Integer> entry : entries) {
            String id = safeId(entry.getKey());
            Integer slot = entry.getValue();
            if (!id.isEmpty() && slot != null && slot >= 0 && seen.add(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Map<String, Integer> slotsFromOrderedIds(List<String> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        int slot = 0;
        for (String rawId : orderedIds) {
            String id = safeId(rawId);
            if (!id.isEmpty() && !slots.containsKey(id)) {
                slots.put(id, slot++);
            }
        }
        return slots;
    }

    private String idAtSlot(Map<String, Integer> slotsById, int slot, String ignoreId) {
        if (slotsById == null || slotsById.isEmpty()) {
            return null;
        }
        int target = Math.max(0, slot);
        String ignored = safeId(ignoreId);
        for (Map.Entry<String, Integer> entry : slotsById.entrySet()) {
            String id = safeId(entry.getKey());
            Integer currentSlot = entry.getValue();
            if (!id.isEmpty() && !id.equals(ignored) && currentSlot != null && currentSlot == target) {
                return id;
            }
        }
        return null;
    }

    private int firstFreeSlot(Set<Integer> occupied, int startSlot) {
        int slot = Math.max(0, startSlot);
        while (occupied != null && occupied.contains(slot)) {
            slot++;
        }
        return slot;
    }

    private Set<String> appIds(List<OpenHouseComponent> components) {
        Set<String> ids = new HashSet<>();
        if (components == null) {
            return ids;
        }
        for (OpenHouseComponent component : components) {
            String id = safeId(component == null ? "" : component.id);
            if (!id.isEmpty() && component.isDesktopVisible()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> sanitizeOrderedIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawId : ids) {
            String id = safeId(rawId);
            if (!id.isEmpty() && seen.add(id)) {
                clean.add(id);
            }
        }
        return clean;
    }

    private List<String> readStringArray(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = safeId(array.optString(i, ""));
            if (!value.isEmpty() && seen.add(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, Integer> readSlotMap(JSONObject root) {
        if (root == null) {
            return new LinkedHashMap<>();
        }
        JSONArray array = root.optJSONArray("slots");
        if (array != null) {
            Map<String, Integer> slots = new LinkedHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String id = safeId(item.optString("id", ""));
                int slot = item.optInt("slot", -1);
                if (!id.isEmpty() && slot >= 0 && !slots.containsKey(id)) {
                    slots.put(id, slot);
                }
            }
            return slots;
        }
        JSONObject object = root.optJSONObject("slots");
        if (object == null) {
            object = root.optJSONObject("slotById");
        }
        if (object == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        JSONArray names = object.names();
        if (names == null) {
            return slots;
        }
        for (int i = 0; i < names.length(); i++) {
            String rawKey = names.optString(i, "");
            String id = safeId(rawKey);
            int slot = object.optInt(rawKey, -1);
            if (!id.isEmpty() && slot >= 0 && !slots.containsKey(id)) {
                slots.put(id, slot);
            }
        }
        return slots;
    }

    private static String safeId(String value) {
        return safeTrim(value).toLowerCase(Locale.US).replace(' ', '-');
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class SlotRecord {
        private final String id;
        private final int slot;
        private final int order;

        private SlotRecord(String id, int slot, int order) {
            this.id = id;
            this.slot = Math.max(0, slot);
            this.order = Math.max(0, order);
        }
    }

    private static final class SavedLayout {
        private Map<String, Integer> slotById = new LinkedHashMap<>();
        private List<String> orderedIds = Collections.emptyList();
        private Set<String> hiddenIds = new LinkedHashSet<>();
        private Map<String, DesktopAppOverride> overrides = new HashMap<>();
        private int currentPage;
    }
}
