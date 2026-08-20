package com.wuxianpi.openhouse.feature

import android.content.Context
import org.json.JSONObject

data class FloatingWindowSnapshot(
    val title: String,
    val conversationUrl: String,
    val returnUrl: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val updatedAt: Long,
)

/** Stores the single most recent in-app floating page locally. */
class FloatingWindowStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): FloatingWindowSnapshot? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            FloatingWindowSnapshot(
                title = json.optString("title"),
                conversationUrl = json.optString("conversationUrl"),
                returnUrl = json.optString("returnUrl"),
                x = json.optInt("x"),
                y = json.optInt("y"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                updatedAt = json.optLong("updatedAt"),
            ).takeIf { it.conversationUrl.isNotBlank() }
        }.getOrNull()
    }

    fun save(snapshot: FloatingWindowSnapshot) {
        preferences.edit()
            .putString(
                KEY_SNAPSHOT,
                JSONObject().apply {
                    put("title", snapshot.title)
                    put("conversationUrl", snapshot.conversationUrl)
                    put("returnUrl", snapshot.returnUrl)
                    put("x", snapshot.x)
                    put("y", snapshot.y)
                    put("width", snapshot.width)
                    put("height", snapshot.height)
                    put("updatedAt", snapshot.updatedAt)
                }.toString(),
            )
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_SNAPSHOT).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "openhouse-floating-window"
        private const val KEY_SNAPSHOT = "last"
    }
}
