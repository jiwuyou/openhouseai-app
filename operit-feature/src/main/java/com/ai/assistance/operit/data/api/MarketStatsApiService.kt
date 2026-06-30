package com.ai.assistance.operit.data.api

import android.os.SystemClock
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitRuntimeContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable
data class MarketStatsEntryResponse(
    val downloads: Int = 0,
    val lastDownloadAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class MarketTypeStatsResponse(
    val updatedAt: String? = null,
    val items: Map<String, MarketStatsEntryResponse> = emptyMap()
)

@Serializable
data class MarketRankIssueEntryResponse(
    val id: String,
    val downloads: Int = 0,
    val lastDownloadAt: String? = null,
    val updatedAt: String? = null,
    val statsUpdatedAt: String? = null,
    val displayTitle: String = "",
    val summaryDescription: String = "",
    val authorLogin: String = "",
    val authorAvatarUrl: String = "",
    val metadata: JsonElement? = null,
    val issue: GitHubIssue
)

@Serializable
data class MarketRankPageResponse(
    val updatedAt: String? = null,
    val type: String = "",
    val metric: String = "",
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    val items: List<MarketRankIssueEntryResponse> = emptyList()
)

@Serializable
data class ArtifactProjectRankDefaultNodeResponse(
    val nodeId: String = "",
    val runtimePackageId: String = "",
    val sha256: String = "",
    val version: String = "",
    val downloadUrl: String = "",
    val state: String = "open",
    val publishedAt: String? = null
)

@Serializable
data class ArtifactProjectRankEntryResponse(
    val projectId: String = "",
    val type: String = "",
    val projectDisplayName: String = "",
    val projectDescription: String = "",
    val rootPublisherLogin: String = "",
    val rootPublisherAvatarUrl: String = "",
    val contributorCount: Int = 0,
    val downloads: Int = 0,
    val likes: Int = 0,
    val latestNodeId: String = "",
    val latestOpenNodeId: String = "",
    val defaultNodeId: String = "",
    val latestPublishedAt: String? = null,
    val defaultNode: ArtifactProjectRankDefaultNodeResponse? = null,
    val runtimePackageNodeSha256s: List<String> = emptyList()
)

@Serializable
data class ArtifactProjectRankPageResponse(
    val updatedAt: String? = null,
    val type: String = "",
    val metric: String = "",
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    val items: List<ArtifactProjectRankEntryResponse> = emptyList()
)

@Serializable
data class ArtifactProjectEdgeResponse(
    val parentNodeId: String = "",
    val childNodeId: String = ""
)

@Serializable
data class ArtifactProjectNodeResponse(
    val projectId: String = "",
    val type: String = "",
    val projectDisplayName: String = "",
    val projectDescription: String = "",
    val runtimePackageId: String = "",
    val nodeId: String = "",
    val rootNodeId: String = "",
    val parentNodeIds: List<String> = emptyList(),
    val publisherLogin: String = "",
    val releaseTag: String = "",
    val assetName: String = "",
    val downloadUrl: String = "",
    val sha256: String = "",
    val version: String = "",
    val displayName: String = "",
    val description: String = "",
    val sourceFileName: String = "",
    val minSupportedAppVersion: String? = null,
    val maxSupportedAppVersion: String? = null,
    val publishedAt: String? = null,
    val state: String = "open",
    val issue: GitHubIssue
)

@Serializable
data class ArtifactProjectDetailResponse(
    val projectId: String = "",
    val type: String = "",
    val projectDisplayName: String = "",
    val projectDescription: String = "",
    val rootNodeId: String = "",
    val rootPublisherLogin: String = "",
    val rootPublisherAvatarUrl: String = "",
    val contributorCount: Int = 0,
    val downloads: Int = 0,
    val likes: Int = 0,
    val latestNodeId: String = "",
    val latestOpenNodeId: String = "",
    val defaultNodeId: String = "",
    val latestPublishedAt: String? = null,
    val nodes: List<ArtifactProjectNodeResponse> = emptyList(),
    val edges: List<ArtifactProjectEdgeResponse> = emptyList()
)

class MarketStatsApiService {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val staticClient = STATIC_CLIENT
    private val noRedirectTrackingClient = NO_REDIRECT_TRACKING_CLIENT

    suspend fun getStats(type: String): Result<MarketTypeStatsResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                requestStaticJson(
                    pathSegments = listOf("stats", "$type.json"),
                    label = "getStats type=$type"
                ) { body ->
                    json.decodeFromString(MarketTypeStatsResponse.serializer(), body)
                }
            }
        }

    suspend fun getRankPage(
        type: String,
        metric: String,
        page: Int
    ): Result<MarketRankPageResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                requestStaticJson(
                    pathSegments = listOf("rank", "${type}-${metric}-page-${page}.json"),
                    label = "getRankPage type=$type metric=$metric page=$page"
                ) { body ->
                    json.decodeFromString(MarketRankPageResponse.serializer(), body)
                }
            }
        }

    suspend fun getArtifactRankPage(
        type: String,
        metric: String,
        page: Int
    ): Result<ArtifactProjectRankPageResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                requestStaticJson(
                    pathSegments = listOf("artifact-rank", "${type}-${metric}-page-${page}.json"),
                    label = "getArtifactRankPage type=$type metric=$metric page=$page"
                ) { body ->
                    json.decodeFromString(ArtifactProjectRankPageResponse.serializer(), body)
                }
            }
        }

    suspend fun getArtifactProject(
        projectId: String
    ): Result<ArtifactProjectDetailResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                requestStaticJson(
                    pathSegments = listOf("artifact-projects", "$projectId.json"),
                    label = "getArtifactProject projectId=$projectId"
                ) { body ->
                    json.decodeFromString(ArtifactProjectDetailResponse.serializer(), body)
                }
            }
        }

    suspend fun trackDownload(
        type: String,
        id: String,
        targetUrl: String
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    TRACK_BASE_URL.toHttpUrl()
                        .newBuilder()
                        .addPathSegment("download")
                        .addQueryParameter("type", type)
                        .addQueryParameter("id", id)
                        .addQueryParameter("target", targetUrl)
                        .build()

                val request =
                    Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("User-Agent", USER_AGENT)
                        .build()

                val startedAt = SystemClock.elapsedRealtime()
                AppLogger.d(TAG, "HTTP GET trackDownload type=$type id=$id url=$url")
                noRedirectTrackingClient.newCall(request).execute().use { response ->
                    AppLogger.d(
                        TAG,
                        "HTTP RESP trackDownload type=$type id=$id code=${response.code} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms url=$url"
                    )
                    if (response.code in 300..399 || response.isSuccessful) {
                        Unit
                    } else {
                        val body = response.body?.string().orEmpty()
                        error(buildHttpErrorMessage(response, body))
                    }
                }
            }
        }

    private inline fun <T> requestStaticJson(
        pathSegments: List<String>,
        label: String,
        decode: (String) -> T
    ): T {
        val urlBuilder = STATIC_BASE_URL.toHttpUrl().newBuilder()
        pathSegments.forEach(urlBuilder::addPathSegment)
        val url = urlBuilder.build()

        val request =
            Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", USER_AGENT)
                .build()

        val startedAt = SystemClock.elapsedRealtime()
        AppLogger.d(TAG, "HTTP GET $label url=$url")
        staticClient.newCall(request).execute().use { response ->
            AppLogger.d(
                TAG,
                "HTTP RESP $label code=${response.code} source=${resolveResponseSource(response)} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms url=$url"
            )
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(buildHttpErrorMessage(response, body))
            }
            return decode(body)
        }
    }

    private fun buildHttpErrorMessage(
        response: Response,
        body: String
    ): String {
        val requestPath = response.request.url.encodedPath
        val summary =
            when {
                body.isBlank() -> ""
                body.contains("<html", ignoreCase = true) || body.contains("<!DOCTYPE html", ignoreCase = true) ->
                    " [html body omitted]"
                else -> " ${body.lineSequence().firstOrNull().orEmpty().trim().take(180)}"
            }

        return "HTTP ${response.code}: ${response.message} ($requestPath)$summary"
    }

    private fun resolveResponseSource(response: Response): String {
        return when {
            response.cacheResponse != null && response.networkResponse == null -> "cache"
            response.cacheResponse != null && response.networkResponse != null -> "conditional-cache"
            else -> "network"
        }
    }

    companion object {
        private const val TAG = "MarketStatsApiService"
        const val STATIC_BASE_URL = "https://static.operit.app/market-stats"
        const val TRACK_BASE_URL = "https://api.operit.app/market-stats"
        private const val USER_AGENT = "Operit-Market-Stats"
        private const val TIMEOUT_SECONDS = 15L
        private const val STATIC_CACHE_SIZE_BYTES = 8L * 1024L * 1024L

        private val STATIC_CACHE by lazy {
            Cache(
                directory = OperitRuntimeContext.cacheDir("market_stats_http_cache"),
                maxSize = STATIC_CACHE_SIZE_BYTES
            )
        }

        private val STATIC_CLIENT by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cache(STATIC_CACHE)
                .build()
        }

        private val TRACKING_CLIENT by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private val NO_REDIRECT_TRACKING_CLIENT by lazy {
            TRACKING_CLIENT.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}
