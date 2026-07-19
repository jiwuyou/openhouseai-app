package com.wuxianpi.ai

import com.wuxianpi.tools.AndroidToolBridgeServer
import com.wuxianpi.tools.DefaultAndroidTools
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

class AndroidBridgeManager(
    context: Context,
    private val http: OkHttpClient,
) : AutoCloseable {
    private val token = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val server = AndroidToolBridgeServer(token, DefaultAndroidTools.create(context))
    private var registered: GatewayCredentials? = null

    fun startAndRegister(credentials: GatewayCredentials): Boolean {
        val port = server.start()
        val body = JSONObject()
            .put("clientId", credentials.clientId)
            .put("port", port)
            .put("token", token)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(credentials.adminUrl + "admin/v1/android-bridge")
            .header("Authorization", "Bearer ${credentials.token}")
            .post(body)
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                response.isSuccessful.also { if (it) registered = credentials }
            }
        }.getOrDefault(false)
    }

    override fun close() {
        registered?.let { credentials ->
            val request = Request.Builder()
                .url(credentials.adminUrl + "admin/v1/android-bridge")
                .header("Authorization", "Bearer ${credentials.token}")
                .delete(
                    JSONObject().put("clientId", credentials.clientId).toString().toRequestBody(JSON),
                )
                .build()
            runCatching { http.newCall(request).execute().close() }
        }
        registered = null
        server.close()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
