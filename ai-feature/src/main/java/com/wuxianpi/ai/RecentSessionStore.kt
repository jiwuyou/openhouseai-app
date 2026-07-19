package com.wuxianpi.ai

import android.content.Context
import com.wuxianpi.pi.PiSessionRef
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal interface StringPreferenceStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

private class AndroidStringPreferenceStore(context: Context) : StringPreferenceStore {
    private val preferences = context.getSharedPreferences("wuxianpi_recent_sessions", Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

/** Persists only Pi's stable JSONL identity; conversation content remains owned by Pi. */
internal class RecentSessionStore private constructor(private val preferences: StringPreferenceStore) {
    constructor(context: Context) : this(AndroidStringPreferenceStore(context.applicationContext))

    fun load(serviceUrl: String): PiSessionRef? {
        val raw = preferences.get(key(serviceUrl)) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PiSessionRef(
                sessionId = json.getString("sessionId"),
                sessionPath = json.getString("sessionPath"),
                cwd = json.optString("cwd").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun save(serviceUrl: String, session: PiSessionRef) {
        val json = JSONObject()
            .put("sessionId", session.sessionId)
            .put("sessionPath", session.sessionPath)
        session.cwd?.let { json.put("cwd", it) }
        preferences.put(key(serviceUrl), json.toString())
    }

    fun clear(serviceUrl: String) {
        preferences.remove(key(serviceUrl))
    }

    internal companion object {
        fun forTest(preferences: StringPreferenceStore) = RecentSessionStore(preferences)

        fun key(serviceUrl: String): String {
            val normalized = serviceUrl.trim().trimEnd('/').lowercase()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "service-$digest"
        }
    }
}
