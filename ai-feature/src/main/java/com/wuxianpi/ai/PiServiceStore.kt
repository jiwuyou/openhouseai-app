package com.wuxianpi.ai

import android.content.Context
import java.util.UUID

data class PiServiceCredentials(
    val serviceUrl: String,
    val clientId: String,
)

/** Persists the paired loopback endpoint while keeping legacy preference keys readable. */
class PiServiceStore(context: Context) {
    private val preferences = context.getSharedPreferences("wuxianpi_gateway", Context.MODE_PRIVATE)

    fun load(): PiServiceCredentials? {
        val serviceUrl = preferences.getString(KEY_URL, null) ?: return null
        val clientId = preferences.getString(KEY_CLIENT, null) ?: return null
        return runCatching { PiServiceCredentials(requireLoopback(serviceUrl), clientId) }.getOrNull()
    }

    fun save(serviceUrl: String, clientId: String = currentOrNewClientId()) {
        preferences.edit()
            .putString(KEY_URL, requireLoopback(serviceUrl))
            .putString(KEY_CLIENT, clientId)
            .remove(LEGACY_KEY_TOKEN)
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()

    fun currentOrNewClientId(): String = preferences.getString(KEY_CLIENT, null)
        ?: "wuxianpi-${UUID.randomUUID()}"

    private fun requireLoopback(value: String): String {
        require(value.startsWith("http://127.0.0.1:") || value.startsWith("http://localhost:")) {
            "Only a loopback Pi service URL is allowed"
        }
        return if (value.endsWith('/')) value else "$value/"
    }

    private companion object {
        const val KEY_URL = "admin_url"
        const val KEY_CLIENT = "client_id"
        const val LEGACY_KEY_TOKEN = "token_encrypted"
    }
}
