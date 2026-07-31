package com.ai.assistance.operit.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    suspend fun getStats(type: String): Result<MarketTypeStatsResponse> =
        Result.success(MarketTypeStatsResponse())

    suspend fun getRankPage(
        type: String,
        metric: String,
        page: Int
    ): Result<MarketRankPageResponse> =
        Result.success(MarketRankPageResponse(type = type, metric = metric, page = page))

    suspend fun getArtifactRankPage(
        type: String,
        metric: String,
        page: Int
    ): Result<ArtifactProjectRankPageResponse> =
        Result.success(ArtifactProjectRankPageResponse(type = type, metric = metric, page = page))

    suspend fun getArtifactProject(
        projectId: String
    ): Result<ArtifactProjectDetailResponse> =
        Result.failure(UnsupportedOperationException("Online artifact details are unavailable in WuxianPi"))

    suspend fun trackDownload(
        type: String,
        id: String,
        targetUrl: String
    ): Result<Unit> =
        Result.success(Unit)
}
