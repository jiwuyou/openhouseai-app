package com.wuxianpi.openhouse.feature.desktop

import android.content.Context
import android.content.SharedPreferences
import com.wuxianpi.openhouse.feature.StartupRouteStore
import org.json.JSONArray
import org.json.JSONObject

class DesktopLayoutStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(StartupRouteStore.PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun load(components: List<DesktopComponent>): DesktopLayoutState = buildState(components, readSaved())

    fun merge(components: List<DesktopComponent>): DesktopLayoutState = load(components).also(::persist)

    fun moveToSlot(
        components: List<DesktopComponent>,
        appId: String,
        targetSlot: Int,
    ): DesktopLayoutState {
        val saved = readSaved()
        val state = buildState(components, saved)
        val id = DesktopComponent.normalizeId(appId)
        val source = state.find(id) ?: return state
        val target = targetSlot.coerceAtLeast(0)
        val occupant = state.allEntries.firstOrNull { it.slotIndex == target && it.id != id }
        saved.slots[id] = target
        occupant?.let { saved.slots[it.id] = source.slotIndex }
        return buildState(components, saved).also(::persist)
    }

    fun reorder(components: List<DesktopComponent>, orderedIds: List<String>): DesktopLayoutState {
        val saved = readSaved()
        val available = components.map { it.normalizedId }.toSet()
        val requested = orderedIds.map(DesktopComponent::normalizeId)
            .filter { it in available }
            .distinct()
        val reserved = saved.slots.filterKeys { it !in requested }.values.toMutableSet()
        var slot = 0
        requested.forEach { id ->
            while (slot in reserved) slot++
            saved.slots[id] = slot
            reserved += slot
            slot++
        }
        return buildState(components, saved).also(::persist)
    }

    fun updateOverride(
        components: List<DesktopComponent>,
        appId: String,
        title: String,
        icon: DesktopIconOverride,
    ): DesktopLayoutState {
        val saved = readSaved()
        val id = DesktopComponent.normalizeId(appId)
        if (components.none { it.normalizedId == id }) return buildState(components, saved)
        val override = DesktopAppOverride(title.trim(), icon)
        if (override.isEmpty) saved.overrides.remove(id) else saved.overrides[id] = override
        return buildState(components, saved).also(::persist)
    }

    fun hide(components: List<DesktopComponent>, appId: String, hidden: Boolean): DesktopLayoutState {
        val saved = readSaved()
        val id = DesktopComponent.normalizeId(appId)
        if (DesktopCatalog.isProtected(id)) return buildState(components, saved)
        if (hidden) saved.hidden += id else saved.hidden -= id
        return buildState(components, saved).also(::persist)
    }

    fun resetApp(components: List<DesktopComponent>, appId: String): DesktopLayoutState {
        val saved = readSaved()
        val id = DesktopComponent.normalizeId(appId)
        saved.hidden -= id
        saved.overrides.remove(id)
        saved.slots.remove(id)
        return buildState(components, saved).also(::persist)
    }

    fun restoreHidden(components: List<DesktopComponent>): DesktopLayoutState {
        val saved = readSaved()
        saved.hidden.clear()
        return buildState(components, saved).also(::persist)
    }

    fun saveCurrentPage(components: List<DesktopComponent>, currentPage: Int): DesktopLayoutState {
        val saved = readSaved().apply { page = currentPage.coerceAtLeast(0) }
        return buildState(components, saved).also(::persist)
    }

    fun reset(components: List<DesktopComponent>): DesktopLayoutState {
        preferences.edit().remove(KEY_LAYOUT).apply()
        return merge(components)
    }

    private fun buildState(components: List<DesktopComponent>, saved: Saved): DesktopLayoutState {
        val unique = linkedMapOf<String, DesktopComponent>()
        components.sortedWith(compareBy<DesktopComponent> { it.order }.thenBy { it.displayTitle() })
            .forEach { unique.putIfAbsent(it.normalizedId, it) }

        val normalizedSlots = normalizeSlots(saved.slots)
        val occupied = normalizedSlots.values.toMutableSet()
        unique.keys.forEach { id ->
            if (id !in normalizedSlots) {
                var slot = 0
                while (slot in occupied) slot++
                normalizedSlots[id] = slot
                occupied += slot
            }
        }

        val entries = unique.values.map { component ->
            DesktopLayoutEntry(
                component = component,
                slotIndex = normalizedSlots.getValue(component.normalizedId),
                hidden = !component.fixed && component.normalizedId in saved.hidden,
                override = saved.overrides[component.normalizedId] ?: DesktopAppOverride(),
            )
        }.sortedBy { it.slotIndex }
        val maxPage = (entries.maxOfOrNull { it.slotIndex } ?: 0) / DEFAULT_PAGE_SIZE
        return DesktopLayoutState(entries, saved.page.coerceIn(0, maxPage))
    }

    private fun normalizeSlots(source: Map<String, Int>): LinkedHashMap<String, Int> {
        val result = linkedMapOf<String, Int>()
        val occupied = mutableSetOf<Int>()
        source.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (rawId, rawSlot) ->
                val id = DesktopComponent.normalizeId(rawId)
                if (id.isEmpty() || rawSlot < 0 || id in result) return@forEach
                var slot = rawSlot
                while (slot in occupied) slot++
                result[id] = slot
                occupied += slot
            }
        return result
    }

    private fun persist(state: DesktopLayoutState) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("currentPage", state.currentPage)
        root.put("slots", JSONArray().apply {
            state.allEntries.sortedBy { it.slotIndex }.forEach { entry ->
                put(JSONObject().put("id", entry.id).put("slot", entry.slotIndex))
            }
        })
        root.put("hiddenIds", JSONArray().apply {
            state.allEntries.filter { it.hidden }.forEach { put(it.id) }
        })
        root.put("overrides", JSONObject().apply {
            state.allEntries.filterNot { it.override.isEmpty }.forEach { entry ->
                put(entry.id, JSONObject().apply {
                    if (entry.override.title.isNotBlank()) put("title", entry.override.title)
                    if (!entry.override.icon.isEmpty) {
                        put("icon", JSONObject().apply {
                            entry.override.icon.key.takeIf(String::isNotBlank)?.let { put("key", it) }
                            entry.override.icon.label.takeIf(String::isNotBlank)?.let { put("label", it) }
                            entry.override.icon.color.takeIf(String::isNotBlank)?.let { put("color", it) }
                        })
                    }
                })
            }
        })
        preferences.edit().putString(KEY_LAYOUT, root.toString()).apply()
    }

    private fun readSaved(): Saved {
        val raw = preferences.getString(KEY_LAYOUT, "").orEmpty()
        if (raw.isBlank()) return Saved()
        return try {
            val root = JSONObject(raw)
            val saved = Saved(page = root.optInt("currentPage", 0).coerceAtLeast(0))
            val slots = root.optJSONArray("slots")
            if (slots != null) {
                for (index in 0 until slots.length()) {
                    val item = slots.optJSONObject(index) ?: continue
                    val id = DesktopComponent.normalizeId(item.optString("id"))
                    val slot = item.optInt("slot", -1)
                    if (id.isNotEmpty() && slot >= 0) saved.slots.putIfAbsent(id, slot)
                }
            }
            root.optJSONArray("hiddenIds")?.let { hidden ->
                for (index in 0 until hidden.length()) {
                    val id = DesktopComponent.normalizeId(hidden.optString(index))
                    if (id.isNotEmpty() && !DesktopCatalog.isProtected(id)) saved.hidden += id
                }
            }
            root.optJSONObject("overrides")?.let { overrides ->
                val names = overrides.names() ?: JSONArray()
                for (index in 0 until names.length()) {
                    val id = DesktopComponent.normalizeId(names.optString(index))
                    val value = overrides.optJSONObject(id) ?: continue
                    val icon = value.optJSONObject("icon")
                    val override = DesktopAppOverride(
                        value.optString("title"),
                        DesktopIconOverride(
                            icon?.optString("key").orEmpty(),
                            icon?.optString("label").orEmpty(),
                            icon?.optString("color").orEmpty(),
                        ),
                    )
                    if (id.isNotEmpty() && !override.isEmpty) saved.overrides[id] = override
                }
            }
            saved
        } catch (_: Exception) {
            Saved()
        }
    }

    private data class Saved(
        val slots: LinkedHashMap<String, Int> = linkedMapOf(),
        val hidden: MutableSet<String> = linkedSetOf(),
        val overrides: MutableMap<String, DesktopAppOverride> = linkedMapOf(),
        var page: Int = 0,
    )

    companion object {
        private const val DEFAULT_PAGE_SIZE = 12
        private const val KEY_LAYOUT = "desktop_layout_v1"
    }
}
