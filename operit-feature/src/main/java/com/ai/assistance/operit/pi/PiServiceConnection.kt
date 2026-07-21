package com.ai.assistance.operit.pi

import android.content.Context
import java.util.UUID

/** Connection coordinates shared by Basic UI and host-specific setup flows. */
data class PiServiceCredentials(
    val serviceUrl: String,
    val clientId: String,
) {
    init {
        require(serviceUrl.startsWith("http://127.0.0.1:") || serviceUrl.startsWith("http://localhost:")) {
            "Pi service must use a loopback URL"
        }
        require(clientId.isNotBlank()) { "Pi clientId is required" }
    }
}

/** Persists the shared Pi endpoint. Runtime data and conversation content remain Pi-owned. */
class PiServiceStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PiServiceCredentials? {
        val serviceUrl = preferences.getString(KEY_URL, null) ?: return null
        val clientId = preferences.getString(KEY_CLIENT, null) ?: return null
        return runCatching { PiServiceCredentials(normalizeUrl(serviceUrl), clientId) }.getOrNull()
    }

    fun save(serviceUrl: String, clientId: String = currentOrNewClientId()) {
        preferences.edit()
            .putString(KEY_URL, normalizeUrl(serviceUrl))
            .putString(KEY_CLIENT, clientId)
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()

    fun currentOrNewClientId(): String =
        preferences.getString(KEY_CLIENT, null) ?: "operit-${UUID.randomUUID()}"

    private fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        require(trimmed.startsWith("http://127.0.0.1:") || trimmed.startsWith("http://localhost:")) {
            "Pi service must use a loopback URL"
        }
        return trimmed.trimEnd('/') + "/"
    }

    private companion object {
        const val PREFERENCES_NAME = "operit_pi_service"
        const val KEY_URL = "service_url"
        const val KEY_CLIENT = "client_id"
    }
}
