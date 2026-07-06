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
    private static final int STATE_VERSION = 1;

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
        saved.orderedIds = sanitizeOrderedIds(orderedAppIds);
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

        List<String> orderedIds = mergeOrderedIds(saved.orderedIds, available);
        List<DesktopLayoutEntry> entries = new ArrayList<>();
        int orderIndex = 0;
        int visiblePosition = 0;
        for (String id : orderedIds) {
            OpenHouseComponent component = available.get(id);
            if (component == null) {
                continue;
            }
            DesktopAppOverride override = saved.overrides.get(id);
            boolean hidden = saved.hiddenIds.contains(id);
            entries.add(new DesktopLayoutEntry(
                component,
                DesktopAppDescriptor.fromComponent(component),
                override,
                hidden ? orderIndex : visiblePosition++,
                orderIndex,
                hidden));
            orderIndex++;
        }

        Set<String> ids = available.keySet();
        DesktopLaunchTarget defaultTarget = readTarget(KEY_DEFAULT_KIND, KEY_DEFAULT_VALUE, DesktopLaunchTarget.desktop());
        DesktopLaunchTarget lastTarget = readTarget(KEY_LAST_EXIT_KIND, KEY_LAST_EXIT_VALUE, DesktopLaunchTarget.desktop());
        DesktopLaunchTarget resolvedDefault = resolveTarget(defaultTarget, ids);
        return new DesktopLayoutState(entries, defaultTarget, resolvedDefault, lastTarget, saved.currentPage);
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
            JSONArray hiddenIds = new JSONArray();
            JSONObject overrides = new JSONObject();
            for (DesktopLayoutEntry entry : state.allEntries) {
                orderedIds.put(entry.id);
                if (entry.hidden) {
                    hiddenIds.put(entry.id);
                }
                if (entry.override != null && !entry.override.isEmpty()) {
                    overrides.put(entry.id, entry.override.toJson());
                }
            }
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

    private List<String> mergeOrderedIds(List<String> savedIds, Map<String, OpenHouseComponent> available) {
        if (available == null || available.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (savedIds != null) {
            for (String rawId : savedIds) {
                String id = safeId(rawId);
                if (available.containsKey(id) && seen.add(id)) {
                    merged.add(id);
                }
            }
        }
        for (String id : available.keySet()) {
            if (seen.add(id)) {
                merged.add(id);
            }
        }
        return merged;
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

    private static String safeId(String value) {
        return safeTrim(value).toLowerCase(Locale.US).replace(' ', '-');
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class SavedLayout {
        private List<String> orderedIds = Collections.emptyList();
        private Set<String> hiddenIds = new LinkedHashSet<>();
        private Map<String, DesktopAppOverride> overrides = new HashMap<>();
        private int currentPage;
    }
}
