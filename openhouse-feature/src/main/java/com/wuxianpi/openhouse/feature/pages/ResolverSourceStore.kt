package com.wuxianpi.openhouse.feature.pages

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal data class ResolverSource(
    val url: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

internal class ResolverSourceStore(
    private val file: File,
) {
    constructor(context: Context) : this(
        context.applicationContext.filesDir.resolve("openhouse/resolver-sources.json"),
    )

    @Synchronized
    fun load(): List<ResolverSource> {
        val raw = OpenHousePageFiles.read(file) ?: return defaults()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("sources") ?: return@runCatching defaults()
            val result = mutableListOf<ResolverSource>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = normalizeResolverUrl(item.optString("url")) ?: continue
                if (result.none { source -> source.url == url }) {
                    result += ResolverSource(url, item.optBoolean("enabled", true), url in DEFAULT_URLS)
                }
            }
            result.ifEmpty { defaults() }
        }.getOrElse { defaults() }
    }

    @Synchronized
    fun add(url: String): Boolean {
        val normalized = normalizeResolverUrl(url) ?: return false
        val current = load().toMutableList()
        if (current.any { it.url == normalized }) return false
        current += ResolverSource(normalized, enabled = true, builtIn = normalized in DEFAULT_URLS)
        save(current)
        return true
    }

    @Synchronized
    fun setEnabled(url: String, enabled: Boolean) {
        save(load().map { if (it.url == url) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun move(url: String, delta: Int) {
        val current = load().toMutableList()
        val from = current.indexOfFirst { it.url == url }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (from == to) return
        val value = current.removeAt(from)
        current.add(to, value)
        save(current)
    }

    @Synchronized
    fun remove(url: String): Boolean {
        val current = load()
        if (current.firstOrNull { it.url == url }?.builtIn != false) return false
        save(current.filterNot { it.url == url })
        return true
    }

    @Synchronized
    fun reset() = save(defaults())

    private fun save(values: List<ResolverSource>) {
        val payload = JSONObject()
            .put("version", 1)
            .put("sources", JSONArray().apply {
                values.forEach { source ->
                    put(JSONObject().put("url", source.url).put("enabled", source.enabled))
                }
            })
        OpenHousePageFiles.write(file, payload.toString())
    }

    companion object {
        val DEFAULT_URLS = listOf(
            "https://openhouse.webefficacy.com/.well-known/openhouse-links.json",
            "https://app.webefficacy.com/.well-known/openhouse-links.json",
            "https://app.stcplus.com/.well-known/openhouse-links.json",
        )

        fun defaults(): List<ResolverSource> = DEFAULT_URLS.map { ResolverSource(it, builtIn = true) }

        fun normalizeResolverUrl(value: String?): String? = runCatching {
            val uri = URI(value.orEmpty().trim()).normalize()
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.userInfo == null && !uri.host.isNullOrBlank())
            require(uri.path.orEmpty().endsWith(".json", ignoreCase = true))
            uri.toASCIIString()
        }.getOrNull()
    }
}
