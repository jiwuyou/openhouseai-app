package com.ai.assistance.operit.rescue.plugins

import android.content.Context
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RescuePluginHubSettings(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getHubUrl(): String {
        val stored = preferences.getString(KEY_HUB_URL, null)
        if (stored.isNullOrBlank() || stored.trimEnd('/') == LEGACY_HUB_URL) {
            preferences.edit().putString(KEY_HUB_URL, RescuePluginContract.DEFAULT_HUB_URL).apply()
            return RescuePluginContract.DEFAULT_HUB_URL
        }
        return RescuePluginContract.normalizeHubUrl(stored)
    }

    fun setHubUrl(value: String): String {
        val normalized = RescuePluginContract.normalizeHubUrl(value)
        preferences.edit().putString(KEY_HUB_URL, normalized).apply()
        return normalized
    }

    fun getOrCreateClientId(): String {
        preferences.getString(KEY_CLIENT_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_CLIENT_ID, id).commit()
        return id
    }

    private companion object {
        const val PREFERENCES_NAME = "rescue_plugin_hub"
        const val KEY_HUB_URL = "hub_url"
        const val KEY_CLIENT_ID = "client_id"
        const val LEGACY_HUB_URL = "https://wuxianpi.webefficacy.com"
    }
}

class RescuePluginCatalogClient(
    private val settings: RescuePluginHubSettings,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun search(query: String = ""): List<RescuePluginListing> = withContext(Dispatchers.IO) {
        val urlBuilder =
            "${settings.getHubUrl()}/api/v1/plugins".toHttpUrl().newBuilder()
        query.trim().takeIf { it.isNotEmpty() }?.let { urlBuilder.addQueryParameter("q", it) }
        val body = executeText(Request.Builder().url(urlBuilder.build()).get().build())
        parseArrayPayload(body, "plugins").map { RescuePluginListing.parseCatalogPlugin(it) }
    }

    suspend fun getPlugin(pluginId: String, version: String? = null): RescuePluginListing =
        withContext(Dispatchers.IO) {
            val id = RescuePluginContract.requirePluginId(pluginId)
            val urlBuilder =
                "${settings.getHubUrl()}/api/v1/plugins/$id".toHttpUrl().newBuilder()
            version?.trim()?.takeIf { it.isNotEmpty() }?.let {
                urlBuilder.addQueryParameter("version", RescuePluginContract.requireVersion(it))
            }
            val body = executeText(Request.Builder().url(urlBuilder.build()).get().build())
            val root = JSONObject(body)
            RescuePluginListing.parseCatalogPlugin(root, version)
        }

    suspend fun download(plugin: RescuePluginListing): ByteArray = withContext(Dispatchers.IO) {
        val relative = plugin.downloadUrl ?: "/plugins/${plugin.id}/${plugin.version}.zip"
        val url = resolveUrl(relative)
        executeBytes(Request.Builder().url(url).get().build())
    }

    suspend fun getComments(pluginId: String, version: String? = null): List<RescuePluginComment> =
        withContext(Dispatchers.IO) {
            val id = RescuePluginContract.requirePluginId(pluginId)
            val urlBuilder =
                "${settings.getHubUrl()}/api/v1/plugins/$id/comments".toHttpUrl()
                    .newBuilder()
            version?.trim()?.takeIf { it.isNotEmpty() }?.let {
                urlBuilder.addQueryParameter("version", RescuePluginContract.requireVersion(it))
            }
            val body = executeText(Request.Builder().url(urlBuilder.build()).get().build())
            parseArrayPayload(body, "comments").map(RescuePluginComment::parse)
        }

    suspend fun publishComment(comment: JSONObject): RescuePluginComment =
        withContext(Dispatchers.IO) {
            val pluginId = RescuePluginContract.requirePluginId(comment.getString("pluginId"))
            val payload = buildCommentWirePayload(comment, settings.getOrCreateClientId())
            val request =
                Request.Builder()
                    .url("${settings.getHubUrl()}/api/v1/plugins/$pluginId/comments")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            val body = executeText(request)
            val root = JSONObject(body)
            RescuePluginComment.parse(root.optJSONObject("comment") ?: root)
        }

    private fun resolveUrl(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "${settings.getHubUrl()}/${value.trimStart('/')}"
        }

    private fun executeText(request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Hub HTTP ${response.code}: ${body.take(2048)}")
            }
            body
        }

    private fun executeBytes(request: Request): ByteArray =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Hub download HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Hub returned an empty plugin archive")
        }

    private fun parseArrayPayload(body: String, vararg keys: String): List<JSONObject> {
        val trimmed = body.trim()
        val array =
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                keys.firstNotNullOfOrNull(root::optJSONArray)
                    ?: throw IOException("Hub response does not contain ${keys.joinToString()}")
            }
        return array.objectList()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun buildCommentWirePayload(comment: JSONObject, clientId: String): JSONObject =
    JSONObject()
        .put("version", RescuePluginContract.requireVersion(comment.getString("pluginVersion")))
        .put("authorType", comment.getString("authorType"))
        .put("authorName", comment.getString("authorName"))
        .put("clientId", clientId)
        .put("content", comment.getString("content"))
        .put("rating", if (comment.has("rating") && !comment.isNull("rating")) comment.getInt("rating") else JSONObject.NULL)
        .put("environment", comment.optJSONObject("environment") ?: JSONObject.NULL)
