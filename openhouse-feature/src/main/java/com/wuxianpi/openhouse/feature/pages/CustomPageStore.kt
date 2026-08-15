package com.wuxianpi.openhouse.feature.pages

import android.content.Context
import com.wuxianpi.openhouse.core.workspace.HttpUrlNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class CustomPage(
    val id: String,
    val title: String,
    val url: String,
    val icon: String = "globe",
)

internal class CustomPageStore(
    private val file: File,
) {
    constructor(context: Context) : this(
        context.applicationContext.filesDir.resolve("openhouse/custom-pages.json"),
    )

    @Synchronized
    fun load(): List<CustomPage> {
        val raw = OpenHousePageFiles.read(file) ?: return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("pages") ?: return@runCatching emptyList()
            val result = mutableListOf<CustomPage>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                val url = HttpUrlNormalizer.normalize(item.optString("url")) ?: continue
                if (!id.startsWith("custom.page.") || title.isEmpty() || result.any { page -> page.id == id }) continue
                result += CustomPage(id, title, url, safeIcon(item.optString("icon")))
            }
            result
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun add(title: String, url: String, icon: String): CustomPage? {
        val name = title.trim().take(80)
        val normalizedUrl = HttpUrlNormalizer.normalize(url) ?: return null
        if (name.isEmpty()) return null
        val page = CustomPage(
            id = "custom.page.${UUID.randomUUID().toString().replace("-", "")}",
            title = name,
            url = normalizedUrl,
            icon = safeIcon(icon),
        )
        save(load() + page)
        return page
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = load()
        if (current.none { it.id == id }) return false
        save(current.filterNot { it.id == id })
        return true
    }

    private fun save(values: List<CustomPage>) {
        val payload = JSONObject()
            .put("version", 1)
            .put("pages", JSONArray().apply {
                values.forEach { page ->
                    put(JSONObject()
                        .put("id", page.id)
                        .put("title", page.title)
                        .put("url", page.url)
                        .put("icon", page.icon))
                }
            })
        OpenHousePageFiles.write(file, payload.toString())
    }

    companion object {
        private fun safeIcon(value: String?): String {
            val normalized = value.orEmpty().trim().lowercase()
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return normalized.ifEmpty { "globe" }
        }
    }
}
