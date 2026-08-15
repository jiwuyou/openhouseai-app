package com.wuxianpi.openhouse.feature.pages

import android.content.Context
import android.os.SystemClock
import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.workspace.HttpUrlNormalizer
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class OpenHousePageDefinition(
    val id: String,
    val title: String,
    val icon: String,
    val url: String,
    val order: Int,
)

internal data class OpenHousePageManifest(
    val revision: Long,
    val pages: List<OpenHousePageDefinition>,
    val allowedHosts: Set<String>,
    val raw: String,
)

internal class BuiltInPageRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val pageDirectory = appContext.filesDir.resolve("openhouse")
    private val activeFile = pageDirectory.resolve("pages-active.json")
    private val previousFile = pageDirectory.resolve("pages-previous.json")
    private val sourceStore = ResolverSourceStore(appContext)
    private val customStore = CustomPageStore(appContext)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "openhouse-page-resolver").apply { isDaemon = true }
    }
    private val refreshing = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var manifest = loadInitialManifest()

    fun components(): List<OpenHouseComponent> {
        val official = manifest.pages.mapNotNull { page -> page.toComponent(SOURCE_OFFICIAL) }
        val custom = customStore.load().mapNotNull { page -> page.toComponent() }
        return (official + custom).distinctBy { WorkspaceDestination.normalizeId(it.id) }
    }

    fun isManagedPage(id: String?): Boolean {
        val normalized = WorkspaceDestination.normalizeId(id)
        return manifest.pages.any { WorkspaceDestination.normalizeId(it.id) == normalized } ||
            customStore.load().any { WorkspaceDestination.normalizeId(it.id) == normalized }
    }

    fun isCustomPage(id: String?): Boolean {
        val normalized = WorkspaceDestination.normalizeId(id)
        return customStore.load().any { WorkspaceDestination.normalizeId(it.id) == normalized }
    }

    fun canOpenInside(componentId: String, uri: android.net.Uri): Boolean {
        if (!isManagedPage(componentId)) return true
        val host = uri.host.orEmpty().lowercase(Locale.US)
        if (host.isEmpty() || uri.scheme.orEmpty().lowercase(Locale.US) !in setOf("http", "https")) return false
        val normalized = WorkspaceDestination.normalizeId(componentId)
        val custom = customStore.load().firstOrNull {
            WorkspaceDestination.normalizeId(it.id) == normalized
        }
        val allowed = if (custom != null) {
            setOfNotNull(runCatching { URI(custom.url).host?.lowercase(Locale.US) }.getOrNull())
        } else {
            val pageHost = manifest.pages.firstOrNull {
                WorkspaceDestination.normalizeId(it.id) == normalized
            }?.let { runCatching { URI(it.url).host?.lowercase(Locale.US) }.getOrNull() }
            manifest.allowedHosts + listOfNotNull(pageHost)
        }
        return allowed.any { candidate -> host == candidate || host.endsWith(".$candidate") }
    }

    fun customPages(): List<CustomPage> = customStore.load()

    fun addCustomPage(title: String, url: String, icon: String): CustomPage? =
        customStore.add(title, url, icon)

    fun removeCustomPage(id: String): Boolean = customStore.remove(id)

    fun resolverSources(): List<ResolverSource> = sourceStore.load()

    fun addResolverSource(url: String): Boolean = sourceStore.add(url)

    fun setResolverSourceEnabled(url: String, enabled: Boolean) = sourceStore.setEnabled(url, enabled)

    fun moveResolverSource(url: String, delta: Int) = sourceStore.move(url, delta)

    fun removeResolverSource(url: String): Boolean = sourceStore.remove(url)

    fun resetResolverSources() = sourceStore.reset()

    fun refreshAsync(force: Boolean = false, onComplete: () -> Unit = {}) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS) {
            onComplete()
            return
        }
        if (closed || !refreshing.compareAndSet(false, true)) {
            onComplete()
            return
        }
        executor.execute {
            try {
                sourceStore.load().asSequence()
                    .filter { it.enabled }
                    .mapNotNull { source -> fetchManifest(source.url) }
                    .firstOrNull { candidate -> candidate.revision >= manifest.revision }
                    ?.let(::activate)
                lastRefreshAt = SystemClock.elapsedRealtime()
            } finally {
                refreshing.set(false)
                if (!closed) onComplete()
            }
        }
    }

    fun testSourceAsync(url: String, onComplete: (Boolean, String) -> Unit) {
        val normalized = ResolverSourceStore.normalizeResolverUrl(url)
        if (normalized == null) {
            onComplete(false, "请输入有效的 HTTPS JSON 地址。")
            return
        }
        executor.execute {
            val loaded = fetchManifest(normalized)
            if (!closed) {
                if (loaded == null) onComplete(false, "无法读取有效的页面清单。")
                else onComplete(true, "revision ${loaded.revision}，${loaded.pages.size} 个页面")
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun loadInitialManifest(): OpenHousePageManifest {
        listOf(activeFile, previousFile).forEach { file ->
            OpenHousePageFiles.read(file)?.let(::parseManifest)?.let { return it }
        }
        val seed = appContext.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
        return requireNotNull(parseManifest(seed)) { "Bundled OpenHouse page manifest is invalid" }
    }

    private fun activate(candidate: OpenHousePageManifest) {
        OpenHousePageFiles.read(activeFile)?.let { current ->
            if (parseManifest(current) != null) OpenHousePageFiles.write(previousFile, current)
        }
        OpenHousePageFiles.write(activeFile, candidate.raw)
        manifest = candidate
    }

    private fun fetchManifest(url: String): OpenHousePageManifest? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "OpenHouse-Android/1")
            if (connection.responseCode !in 200..299) return null
            parseManifest(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseManifest(raw: String): OpenHousePageManifest? = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("schema", 0) == 1)
        require(root.optString("kind", "openhouse-links") == "openhouse-links")
        val revision = root.optLong("revision", 0L).coerceAtLeast(0L)
        val urls = root.optJSONObject("urls") ?: JSONObject()
        val urlByKey = buildMap<String, String> {
            val keys = urls.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val normalized = HttpUrlNormalizer.normalize(urls.optString(key)) ?: continue
                if (URI(normalized).scheme.equals("https", ignoreCase = true)) put(key, normalized)
            }
        }
        val pagesArray = root.optJSONArray("pages") ?: return@runCatching null
        val pages = buildList {
            for (index in 0 until pagesArray.length()) {
                val page = pagesArray.optJSONObject(index) ?: continue
                if (!page.optBoolean("enabled", true)) continue
                val id = page.optString("id").trim()
                val title = page.optString("title").trim()
                val url = urlByKey[page.optString("urlKey").trim()] ?: continue
                if (!validId(id) || title.isEmpty() || OpenHouseBuiltins.isProtectedId(id)) continue
                add(OpenHousePageDefinition(
                    id = id,
                    title = title,
                    icon = safeIcon(page.optString("icon")),
                    url = url,
                    order = page.optInt("order", 1000),
                ))
            }
        }.distinctBy { WorkspaceDestination.normalizeId(it.id) }
        require(pages.isNotEmpty())
        val allowedHosts = buildSet {
            root.optJSONArray("allowedHosts")?.let { hosts ->
                for (index in 0 until hosts.length()) {
                    hosts.optString(index).trim().lowercase(Locale.US)
                        .takeIf(String::isNotEmpty)?.let(::add)
                }
            }
            pages.mapNotNullTo(this) { page -> runCatching { URI(page.url).host?.lowercase(Locale.US) }.getOrNull() }
        }
        OpenHousePageManifest(revision, pages, allowedHosts, root.toString())
    }.getOrNull()

    private fun OpenHousePageDefinition.toComponent(source: String): OpenHouseComponent? =
        component(id, title, icon, url, order, source)

    private fun CustomPage.toComponent(): OpenHouseComponent? =
        component(id, title, icon, url, 10_000, SOURCE_CUSTOM)

    private fun component(
        id: String,
        title: String,
        icon: String,
        url: String,
        order: Int,
        source: String,
    ): OpenHouseComponent? = runCatching {
        val json = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("description", "网页小 App")
            .put("kind", "web")
            .put("order", order)
            .put("visible", true)
            .put("entry", JSONObject().put("type", "webview").put("url", url))
            .put("desktop", JSONObject()
                .put("visible", true)
                .put("order", order)
                .put("icon", icon)
                .put("label", title.take(1)))
        OpenHouseComponentParser().parse(RegistryManifest.fromManifestJson(json.toString()), source)
    }.getOrNull()

    companion object {
        const val SOURCE_OFFICIAL = "openhouse-online-page"
        const val SOURCE_CUSTOM = "openhouse-custom-page"
        private const val SEED_ASSET = "openhouse/openhouse-links.json"
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 3_500
        private const val READ_TIMEOUT_MS = 5_000

        private fun validId(value: String): Boolean =
            value.isNotEmpty() && value.length <= 128 && value.all {
                it.isLetterOrDigit() || it == '_' || it == '-' || it == '.'
            }

        private fun safeIcon(value: String?): String {
            val normalized = value.orEmpty().trim().lowercase(Locale.US)
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return normalized.ifEmpty { "globe" }
        }
    }
}
