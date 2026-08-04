package com.ai.assistance.operit.ui.features.packages.utils

import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.ui.features.packages.market.ArtifactMarketMetadata
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
import com.ai.assistance.operit.ui.features.packages.market.effectiveNodeId
import com.ai.assistance.operit.ui.features.packages.market.effectiveParentNodeIds
import com.ai.assistance.operit.ui.features.packages.market.effectiveProjectDescription
import com.ai.assistance.operit.ui.features.packages.market.effectiveProjectDisplayName
import com.ai.assistance.operit.ui.features.packages.market.effectiveProjectId
import com.ai.assistance.operit.ui.features.packages.market.effectiveRootNodeId
import com.ai.assistance.operit.ui.features.packages.market.effectiveRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.market.parseArtifactMarketMetadata

object ArtifactIssueParser {
    data class ParsedArtifactInfo(
        val title: String,
        val description: String,
        val type: PublishArtifactType? = null,
        val publisherLogin: String = "",
        val normalizedId: String = "",
        val projectId: String = "",
        val projectDisplayName: String = "",
        val projectDescription: String = "",
        val runtimePackageId: String = "",
        val nodeId: String = "",
        val rootNodeId: String = "",
        val parentNodeIds: List<String> = emptyList(),
        val downloadUrl: String = "",
        val forgeRepo: String = "",
        val releaseTag: String = "",
        val assetName: String = "",
        val sha256: String = "",
        val version: String = "",
        val sourceFileName: String = "",
        val repositoryOwner: String = "",
        val minSupportedAppVersion: String? = null,
        val maxSupportedAppVersion: String? = null,
        val metadata: ArtifactMarketMetadata? = null
    )

    fun parseArtifactInfo(issue: GitHubIssue): ParsedArtifactInfo {
        val body = issue.body
        if (body.isNullOrBlank()) {
            return ParsedArtifactInfo(
                title = issue.title,
                description = ""
            )
        }

        val metadata = parseArtifactMarketMetadata(body)
        if (metadata == null) {
            return ParsedArtifactInfo(
                title = issue.title,
                description = ""
            )
        }

        return ParsedArtifactInfo(
            title = metadata.displayName.ifBlank { issue.title },
            description = metadata.description,
            type = PublishArtifactType.fromWireValue(metadata.type),
            publisherLogin = metadata.publisherLogin,
            normalizedId = metadata.effectiveRuntimePackageId(),
            projectId = metadata.effectiveProjectId(),
            projectDisplayName = metadata.effectiveProjectDisplayName(),
            projectDescription = metadata.effectiveProjectDescription(),
            runtimePackageId = metadata.effectiveRuntimePackageId(),
            nodeId = metadata.effectiveNodeId(issue.id),
            rootNodeId = metadata.effectiveRootNodeId(issue.id),
            parentNodeIds = metadata.effectiveParentNodeIds(),
            downloadUrl = metadata.downloadUrl,
            forgeRepo = metadata.forgeRepo,
            releaseTag = metadata.releaseTag,
            assetName = metadata.assetName,
            sha256 = metadata.sha256,
            version = metadata.version,
            sourceFileName = metadata.sourceFileName,
            repositoryOwner = metadata.publisherLogin,
            minSupportedAppVersion = metadata.minSupportedAppVersion,
            maxSupportedAppVersion = metadata.maxSupportedAppVersion,
            metadata = metadata
        )
    }
}
