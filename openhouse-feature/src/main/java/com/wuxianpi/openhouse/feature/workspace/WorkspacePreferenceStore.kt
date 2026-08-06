package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.content.SharedPreferences
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.StartupRouteStore

class WorkspacePreferenceStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(StartupRouteStore.PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun isPinned(component: OpenHouseComponent): Boolean {
        val id = normalize(component.id)
        if (id in values(KEY_PINNED)) return true
        if (id in values(KEY_UNPINNED)) return false
        return component.favorite
    }

    fun setPinned(component: OpenHouseComponent, pinned: Boolean) {
        val id = normalize(component.id)
        if (id.isEmpty()) return
        val pinnedIds = values(KEY_PINNED).toMutableSet()
        val unpinnedIds = values(KEY_UNPINNED).toMutableSet()
        if (pinned) {
            pinnedIds += id
            unpinnedIds -= id
        } else {
            pinnedIds -= id
            unpinnedIds += id
        }
        preferences.edit()
            .putStringSet(KEY_PINNED, pinnedIds)
            .putStringSet(KEY_UNPINNED, unpinnedIds)
            .apply()
    }

    private fun values(key: String): Set<String> = preferences.getStringSet(key, emptySet())
        .orEmpty()
        .mapTo(linkedSetOf(), ::normalize)

    private fun normalize(value: String): String = WorkspaceDestination.normalizeId(value)

    companion object {
        private const val KEY_PINNED = "workspace_pinned_ids_v1"
        private const val KEY_UNPINNED = "workspace_unpinned_ids_v1"
    }
}
