package com.ai.assistance.operit.pi

import android.content.Context
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Connection coordinates shared by Basic UI and host-specific setup flows. */
class PiServiceCredentials(
    serviceUrl: String,
    val clientId: String,
) {
    val serviceUrl: String = normalizePiServiceUrl(serviceUrl)

    init {
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
        return runCatching { PiServiceCredentials(serviceUrl, clientId) }.getOrNull()
    }

    fun save(serviceUrl: String, clientId: String = currentOrNewClientId()) {
        preferences.edit()
            .putString(KEY_URL, normalizePiServiceUrl(serviceUrl))
            .putString(KEY_CLIENT, clientId)
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()

    fun currentOrNewClientId(): String =
        preferences.getString(KEY_CLIENT, null) ?: "operit-${UUID.randomUUID()}"

    private companion object {
        const val PREFERENCES_NAME = "operit_pi_service"
        const val KEY_URL = "service_url"
        const val KEY_CLIENT = "client_id"
    }
}

private fun normalizePiServiceUrl(value: String): String {
    val parsed = value.trim().toHttpUrl()
    require(parsed.scheme == "http" || parsed.scheme == "https") {
        "Pi service must use HTTP(S)"
    }
    require(
        parsed.host == "127.0.0.1" ||
            parsed.host == "::1" ||
            parsed.host.equals("localhost", ignoreCase = true)
    ) {
        "Pi service must use a loopback URL"
    }
    require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
        "Pi service URL must not contain credentials"
    }
    return parsed.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
}
