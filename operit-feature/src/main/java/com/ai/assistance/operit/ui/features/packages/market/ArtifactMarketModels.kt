package com.ai.assistance.operit.ui.features.packages.market

import com.ai.assistance.operit.ui.features.packages.utils.IssueBodyMetadataParser
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val OPERIT_MARKET_OWNER = "AAswordman"
const val OPERIT_FORGE_REPO_NAME = "OperitForge"

private const val ARTIFACT_MARKET_JSON_PREFIX = "<!-- operit-market-json: "
private const val ARTIFACT_MARKET_PARSER_VERSION = "forge-v3"
private const val SCRIPT_MARKET_LABEL = "script-artifact"
private const val PACKAGE_MARKET_LABEL = "package-artifact"
private const val PLACEHOLDER_MARKET_ARTIFACT_ID = "artifact"
private val APP_VERSION_REGEX = Regex("""^(\d+)\.(\d+)\.(\d+)(?:\+(\d+))?$""")
private val MARKET_ARTIFACT_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int?
) {
    override fun toString(): String {
        return build?.let { "$major.$minor.$patch+$it" } ?: "$major.$minor.$patch"
    }
}

enum class PublishArtifactType(
    val wireValue: String,
    val marketRepo: String,
    val releaseTagPrefix: String,
    val titleLabel: String,
    val marketLabel: String,
    val marketLabelColor: String,
    val marketLabelDescription: String
) {
    SCRIPT(
        wireValue = "script",
        marketRepo = "OperitScriptMarket",
        releaseTagPrefix = "script",
        titleLabel = "Script",
        marketLabel = SCRIPT_MARKET_LABEL,
        marketLabelColor = "0e8a16",
        marketLabelDescription = "Published script artifacts managed by Operit."
    ),
    PACKAGE(
        wireValue = "package",
        marketRepo = "OperitPackageMarket",
        releaseTagPrefix = "package",
        titleLabel = "Package",
        marketLabel = PACKAGE_MARKET_LABEL,
        marketLabelColor = "1d76db",
        marketLabelDescription = "Published package artifacts managed by Operit."
    );

    fun marketDefinition(): GitHubIssueMarketDefinition {
        return GitHubIssueMarketDefinition(
            owner = OPERIT_MARKET_OWNER,
            repo = marketRepo,
            label = marketLabel,
            pageSize = 50,
            labelColor = marketLabelColor,
            labelDescription = marketLabelDescription
        )
    }

    companion object {
        fun fromWireValue(value: String?): PublishArtifactType? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

enum class ArtifactMarketScope {
    ALL,
    SCRIPT_ONLY,
    PACKAGE_ONLY;

    fun supportedTypes(): List<PublishArtifactType> {
        return when (this) {
            ALL -> PublishArtifactType.entries
            SCRIPT_ONLY -> listOf(PublishArtifactType.SCRIPT)
            PACKAGE_ONLY -> listOf(PublishArtifactType.PACKAGE)
        }
    }
}

enum class PublishProgressStage {
    IDLE,
    VALIDATING,
    ENSURING_REPO,
    CREATING_RELEASE,
    UPLOADING_ASSET,
    REGISTERING_MARKET,
    COMPLETED
}

data class ForgeRepoInfo(
    val ownerLogin: String,
    val repoName: String,
    val htmlUrl: String,
    val existedBefore: Boolean
)

data class LocalPublishableArtifact(
    val type: PublishArtifactType,
    val packageName: String,
    val displayName: String,
    val description: String,
    val sourceFile: File,
    val hasDeclaredAuthorField: Boolean = false,
    val declaredAuthorSlotCount: Int = 0,
    val inferredVersion: String? = null
)

data class ArtifactPublishClusterContext(
    val projectId: String,
    val rootNodeId: String,
    val runtimePackageId: String,
    val parentNodeIds: List<String>,
    val lockedDisplayName: String,
    val projectDisplayName: String,
    val projectDescription: String
)

data class PublishArtifactDescriptor(
    val type: PublishArtifactType,
    val projectId: String,
    val projectDisplayName: String,
    val projectDescription: String,
    val runtimePackageId: String,
    val nodeId: String,
    val rootNodeId: String,
    val parentNodeIds: List<String>,
    val displayName: String,
    val description: String,
    val version: String,
    val sourceFile: File,
    val contentType: String,
    val assetName: String,
    val minSupportedAppVersion: String?,
    val maxSupportedAppVersion: String?
)

data class PublishReleaseDescriptor(
    val tagName: String,
    val releaseName: String,
    val releaseBody: String
)

data class MarketRegistrationPayload(
    val type: PublishArtifactType,
    val projectId: String,
    val projectDisplayName: String,
    val projectDescription: String,
    val runtimePackageId: String,
    val nodeId: String,
    val rootNodeId: String,
    val parentNodeIds: List<String>,
    val publisherLogin: String,
    val forgeRepo: String,
    val releaseTag: String,
    val assetName: String,
    val downloadUrl: String,
    val sha256: String,
    val version: String,
    val displayName: String,
    val description: String,
    val sourceFileName: String,
    val minSupportedAppVersion: String?,
    val maxSupportedAppVersion: String?
)

@Serializable
data class ArtifactMarketMetadata(
    val type: String = "",
    val projectId: String = "",
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
    val normalizedId: String = "",
    val forgeRepo: String = ""
)

fun ArtifactMarketMetadata.effectiveProjectId(): String {
    val candidate =
        projectId.trim().ifBlank {
            normalizedId.trim().ifBlank {
                normalizeMarketArtifactId(runtimePackageId.ifBlank { displayName.ifBlank { assetName } })
            }
        }
    return normalizeMarketArtifactId(candidate)
}

fun ArtifactMarketMetadata.effectiveProjectDisplayName(): String {
    return projectDisplayName.trim().ifBlank { displayName.trim() }
}

fun ArtifactMarketMetadata.effectiveProjectDescription(): String {
    return projectDescription.trim().ifBlank { description.trim() }
}

fun ArtifactMarketMetadata.effectiveRuntimePackageId(): String {
    val candidate =
        runtimePackageId.trim().ifBlank {
            normalizedId.trim().ifBlank { effectiveProjectId() }
        }
    return candidate.ifBlank { effectiveProjectId() }
}

fun ArtifactMarketMetadata.effectiveNodeId(issueId: Long? = null): String {
    val candidate = nodeId.trim()
    if (candidate.isNotBlank()) {
        return candidate
    }
    return issueId?.let { "legacy-$it" } ?: "legacy-${effectiveProjectId()}"
}

fun ArtifactMarketMetadata.effectiveRootNodeId(issueId: Long? = null): String {
    return rootNodeId.trim().ifBlank { effectiveNodeId(issueId) }
}

fun ArtifactMarketMetadata.effectiveParentNodeIds(): List<String> {
    return parentNodeIds.map(String::trim).filter(String::isNotBlank)
}

fun ArtifactMarketMetadata.toPublishClusterContext(issueId: Long? = null): ArtifactPublishClusterContext {
    return ArtifactPublishClusterContext(
        projectId = effectiveProjectId(),
        rootNodeId = effectiveRootNodeId(issueId),
        runtimePackageId = effectiveRuntimePackageId(),
        parentNodeIds = effectiveParentNodeIds(),
        lockedDisplayName = displayName.trim().ifBlank { effectiveProjectDisplayName() },
        projectDisplayName = effectiveProjectDisplayName(),
        projectDescription = effectiveProjectDescription()
    )
}

fun normalizeMarketArtifactId(raw: String): String {
    val normalized =
        raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    return normalized.ifBlank { PLACEHOLDER_MARKET_ARTIFACT_ID }
}

fun isPlaceholderMarketArtifactId(raw: String): Boolean {
    return normalizeMarketArtifactId(raw) == PLACEHOLDER_MARKET_ARTIFACT_ID
}

fun requiresStandaloneArtifactIdUpgrade(runtimePackageId: String): Boolean {
    val trimmed = runtimePackageId.trim()
    return trimmed.isNotBlank() &&
        isPlaceholderMarketArtifactId(trimmed) &&
        !trimmed.equals(PLACEHOLDER_MARKET_ARTIFACT_ID, ignoreCase = true)
}

fun validateStandaloneArtifactRuntimePackageId(runtimePackageId: String) {
    require(!requiresStandaloneArtifactIdUpgrade(runtimePackageId)) {
        "当前包 ID「$runtimePackageId」无法生成稳定的市场项目 ID。请改用包含英文字母或数字的包 ID（可含 -、_、.），再重新发布。"
    }
}

fun sameArtifactRuntimePackageId(
    left: String,
    right: String
): Boolean {
    val trimmedLeft = left.trim()
    val trimmedRight = right.trim()
    if (trimmedLeft.isBlank() || trimmedRight.isBlank()) {
        return false
    }
    return trimmedLeft.equals(trimmedRight, ignoreCase = true) ||
        normalizeMarketArtifactId(trimmedLeft) == normalizeMarketArtifactId(trimmedRight)
}

fun parseArtifactMarketMetadata(body: String): ArtifactMarketMetadata? {
    return IssueBodyMetadataParser.parseCommentJson(
        body = body,
        prefix = ARTIFACT_MARKET_JSON_PREFIX,
        tag = "ArtifactMarketMetadata",
        metadataName = "artifact market metadata"
    )
}

fun buildPublishArtifactDescriptor(
    type: PublishArtifactType,
    localArtifact: LocalPublishableArtifact,
    displayName: String,
    description: String,
    version: String,
    minSupportedAppVersion: String?,
    maxSupportedAppVersion: String?,
    publishContext: ArtifactPublishClusterContext? = null
): PublishArtifactDescriptor {
    val runtimePackageId = localArtifact.packageName.trim().ifBlank { localArtifact.packageName }
    if (publishContext == null) {
        validateStandaloneArtifactRuntimePackageId(runtimePackageId)
    }
    val normalizedRuntimePackageId = normalizeMarketArtifactId(runtimePackageId)
    val contextRuntimePackageId = publishContext?.runtimePackageId?.trim().orEmpty()
    if (contextRuntimePackageId.isNotBlank()) {
        require(
            normalizeMarketArtifactId(contextRuntimePackageId) == normalizedRuntimePackageId
        ) {
            "Continuation publish must keep runtime package id '$contextRuntimePackageId'"
        }
    }

    val cleanVersion =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .ifBlank { "1.0.0" }
    val lockedDisplayName = publishContext?.lockedDisplayName?.trim().orEmpty()
    if (publishContext != null) {
        require(lockedDisplayName.isNotBlank()) {
            "Continuation publish must keep source display name"
        }
    }
    val resolvedDisplayName =
        lockedDisplayName.ifBlank {
            displayName.trim().ifBlank { localArtifact.displayName }
        }
    val extension = localArtifact.sourceFile.extension.lowercase().ifBlank { "bin" }
    val nodeId = UUID.randomUUID().toString()
    val rootNodeId =
        publishContext?.rootNodeId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: nodeId
    val projectId =
        publishContext?.projectId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeMarketArtifactId)
            ?: normalizeMarketArtifactId(runtimePackageId)
    val parentNodeIds =
        publishContext?.parentNodeIds
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
    val projectDisplayName =
        publishContext?.projectDisplayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: displayName.trim().ifBlank { localArtifact.displayName }
    val projectDescription =
        publishContext?.projectDescription
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: description.trim().ifBlank { localArtifact.description }
    val assetName = "$normalizedRuntimePackageId-v$cleanVersion-${nodeId.take(8)}.$extension"

    return PublishArtifactDescriptor(
        type = type,
        projectId = projectId,
        projectDisplayName = projectDisplayName,
        projectDescription = projectDescription,
        runtimePackageId = runtimePackageId,
        nodeId = nodeId,
        rootNodeId = rootNodeId,
        parentNodeIds = parentNodeIds,
        displayName = resolvedDisplayName,
        description = description.trim().ifBlank { localArtifact.description },
        version = cleanVersion,
        sourceFile = localArtifact.sourceFile,
        contentType = inferArtifactContentType(type, extension),
        assetName = assetName,
        minSupportedAppVersion = normalizeAppVersionOrNull(minSupportedAppVersion),
        maxSupportedAppVersion = normalizeAppVersionOrNull(maxSupportedAppVersion)
    )
}

fun buildPublishReleaseDescriptor(
    descriptor: PublishArtifactDescriptor
): PublishReleaseDescriptor {
    val normalizedRuntimePackageId = normalizeMarketArtifactId(descriptor.runtimePackageId)
    val tagName =
        "${descriptor.type.releaseTagPrefix}-${normalizedRuntimePackageId}-v${descriptor.version}-${descriptor.nodeId.take(8)}"
    return PublishReleaseDescriptor(
        tagName = tagName,
        releaseName = "${descriptor.displayName} v${descriptor.version}",
        releaseBody =
            buildString {
                appendLine("${descriptor.type.titleLabel} artifact published by OperitForge.")
                appendLine()
                appendLine("Project ID: ${descriptor.projectId}")
                appendLine("Runtime package ID: ${descriptor.runtimePackageId}")
                appendLine("Node ID: ${descriptor.nodeId}")
                appendLine("Root node ID: ${descriptor.rootNodeId}")
                appendLine(
                    "Parent node IDs: ${descriptor.parentNodeIds.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"}"
                )
                appendLine("Display name: ${descriptor.displayName}")
                appendLine("Version: ${descriptor.version}")
                appendLine(
                    "Supported app versions: ${formatSupportedAppVersions(descriptor.minSupportedAppVersion, descriptor.maxSupportedAppVersion)}"
                )
            }
    )
}

fun buildArtifactMarketMetadata(
    payload: MarketRegistrationPayload
): ArtifactMarketMetadata {
    return ArtifactMarketMetadata(
        type = payload.type.wireValue,
        projectId = payload.projectId,
        projectDisplayName = payload.projectDisplayName,
        projectDescription = payload.projectDescription,
        runtimePackageId = payload.runtimePackageId,
        nodeId = payload.nodeId,
        rootNodeId = payload.rootNodeId,
        parentNodeIds = payload.parentNodeIds,
        publisherLogin = payload.publisherLogin,
        releaseTag = payload.releaseTag,
        assetName = payload.assetName,
        downloadUrl = payload.downloadUrl,
        sha256 = payload.sha256,
        version = payload.version,
        displayName = payload.displayName,
        description = payload.description,
        sourceFileName = payload.sourceFileName,
        minSupportedAppVersion = payload.minSupportedAppVersion,
        maxSupportedAppVersion = payload.maxSupportedAppVersion
    )
}

fun buildArtifactMarketIssueBody(payload: MarketRegistrationPayload): String {
    val metadata = buildArtifactMarketMetadata(payload)
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    val metadataJson = json.encodeToString(metadata)
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val parentNodeIds = payload.parentNodeIds.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"

    return buildString {
        appendLine("$ARTIFACT_MARKET_JSON_PREFIX$metadataJson -->")
        appendLine("<!-- operit-parser-version: $ARTIFACT_MARKET_PARSER_VERSION -->")
        appendLine()
        appendLine("## ${payload.type.titleLabel}")
        appendLine()
        appendLine(payload.description)
        appendLine()
        appendLine("## Project Cluster")
        appendLine()
        appendLine("- Project ID: `${payload.projectId}`")
        appendLine("- Runtime package ID: `${payload.runtimePackageId}`")
        appendLine("- Node ID: `${payload.nodeId}`")
        appendLine("- Root node ID: `${payload.rootNodeId}`")
        appendLine("- Parent node IDs: `$parentNodeIds`")
        appendLine("- Project display name: `${payload.projectDisplayName}`")
        appendLine("- Project description: ${payload.projectDescription.ifBlank { "-" }}")
        appendLine()
        appendLine("## Artifact")
        appendLine()
        appendLine("- Publisher: `${payload.publisherLogin}`")
        appendLine("- Forge repo: `${payload.forgeRepo}`")
        appendLine("- Release tag: `${payload.releaseTag}`")
        appendLine("- Asset: `${payload.assetName}`")
        appendLine("- SHA-256: `${payload.sha256}`")
        appendLine("- Download: ${payload.downloadUrl}")
        appendLine()
        appendLine("## Metadata")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("| --- | --- |")
        appendLine("| Type | ${payload.type.wireValue} |")
        appendLine("| Project ID | ${payload.projectId} |")
        appendLine("| Runtime package ID | ${payload.runtimePackageId} |")
        appendLine("| Node ID | ${payload.nodeId} |")
        appendLine("| Root node ID | ${payload.rootNodeId} |")
        appendLine("| Parent node IDs | $parentNodeIds |")
        appendLine("| Version | ${payload.version} |")
        appendLine("| Supported app versions | ${formatSupportedAppVersions(payload.minSupportedAppVersion, payload.maxSupportedAppVersion)} |")
        appendLine("| Source file | ${payload.sourceFileName.ifBlank { "-" }} |")
        appendLine("| Updated at | $timestamp |")
    }
}

fun normalizeAppVersionOrNull(value: String?): String? {
    return parseAppVersionOrNull(value)?.toString()
}

fun validateSupportedAppVersions(
    minSupportedAppVersion: String?,
    maxSupportedAppVersion: String?
) {
    val normalizedMin = normalizeAppVersionOrNull(minSupportedAppVersion)
    val normalizedMax = normalizeAppVersionOrNull(maxSupportedAppVersion)
    if (normalizedMin != null && normalizedMax != null) {
        require(compareAppVersions(normalizedMin, normalizedMax) <= 0) {
            "Minimum supported app version cannot be greater than maximum supported app version"
        }
    }
}

fun compareAppVersions(left: String, right: String): Int {
    val leftVersion = requireNotNull(parseAppVersionOrNull(left))
    val rightVersion = requireNotNull(parseAppVersionOrNull(right))

    if (leftVersion.major != rightVersion.major) {
        return leftVersion.major.compareTo(rightVersion.major)
    }
    if (leftVersion.minor != rightVersion.minor) {
        return leftVersion.minor.compareTo(rightVersion.minor)
    }
    if (leftVersion.patch != rightVersion.patch) {
        return leftVersion.patch.compareTo(rightVersion.patch)
    }

    val leftBuild = leftVersion.build ?: 0
    val rightBuild = rightVersion.build ?: 0
    return leftBuild.compareTo(rightBuild)
}

fun isAppVersionSupported(
    appVersion: String,
    minSupportedAppVersion: String?,
    maxSupportedAppVersion: String?
): Boolean {
    val normalizedCurrent = normalizeAppVersionOrNull(appVersion) ?: return true
    val normalizedMin = normalizeAppVersionOrNull(minSupportedAppVersion)
    val normalizedMax = normalizeAppVersionOrNull(maxSupportedAppVersion)
    if (normalizedMin != null && compareAppVersions(normalizedCurrent, normalizedMin) < 0) {
        return false
    }
    if (normalizedMax != null && compareAppVersions(normalizedCurrent, normalizedMax) > 0) {
        return false
    }
    return true
}

fun formatSupportedAppVersions(
    minSupportedAppVersion: String?,
    maxSupportedAppVersion: String?
): String {
    val normalizedMin = normalizeAppVersionOrNull(minSupportedAppVersion)
    val normalizedMax = normalizeAppVersionOrNull(maxSupportedAppVersion)
    return when {
        normalizedMin != null && normalizedMax != null -> "$normalizedMin - $normalizedMax"
        normalizedMin != null -> ">= $normalizedMin"
        normalizedMax != null -> "<= $normalizedMax"
        else -> "Any"
    }
}

private fun inferArtifactContentType(
    type: PublishArtifactType,
    extension: String
): String {
    return when {
        type == PublishArtifactType.PACKAGE && extension == "toolpkg" -> "application/zip"
        extension == "js" -> "application/javascript"
        extension == "ts" -> "text/plain"
        extension == "json" -> "application/json"
        extension == "hjson" -> "application/json"
        extension == "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

private fun parseAppVersionOrNull(value: String?): AppVersion? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return null

    val match =
        APP_VERSION_REGEX.matchEntire(normalized)
            ?: throw IllegalArgumentException("App version must use x.y.z or x.y.z+n format")

    return AppVersion(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
        build = match.groupValues[4].takeIf { it.isNotBlank() }?.toInt()
    )
}
