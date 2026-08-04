package com.ai.assistance.operit.ui.features.packages.market

import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.ToolPkgArchiveParser
import com.ai.assistance.operit.data.api.ArtifactProjectDetailResponse
import com.ai.assistance.operit.data.api.ArtifactProjectNodeResponse
import java.io.File
import java.util.zip.ZipFile
import org.hjson.JsonValue
import org.json.JSONArray
import org.json.JSONObject

data class LocalArtifactAuthorDeclaration(
    val hasAuthorField: Boolean,
    val declaredAuthorSlotCount: Int
)

fun inspectLocalArtifactAuthorDeclaration(
    source: PackageManager.PublishablePackageSource
): LocalArtifactAuthorDeclaration {
    val sourceFile = File(source.sourcePath)
    if (!sourceFile.exists() || !sourceFile.isFile) {
        return LocalArtifactAuthorDeclaration(
            hasAuthorField = false,
            declaredAuthorSlotCount = 0
        )
    }

    return if (source.isToolPkg) {
        inspectToolPkgAuthorDeclaration(sourceFile)
    } else {
        inspectJsAuthorDeclaration(sourceFile)
    }
}

fun collectArtifactPredecessorPublisherLogins(
    project: ArtifactProjectDetailResponse,
    parentNodeIds: List<String>
): Set<String> {
    val nodeById = project.nodes.associateBy { it.nodeId }
    val publishers = linkedSetOf<String>()

    parentNodeIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { nodeId ->
            val node =
                nodeById[nodeId]
                    ?: throw IllegalStateException("找不到前驱节点 `$nodeId`，无法校验作者数量。")
            val publisherLogin = artifactNodePublisherLogin(node)
            if (publisherLogin.isNotBlank()) {
                publishers += publisherLogin
            }
        }

    return publishers
}

private fun inspectJsAuthorDeclaration(sourceFile: File): LocalArtifactAuthorDeclaration {
    val jsContent = sourceFile.readText()
    val metadataString = extractJsMetadata(jsContent)
    val metadataJson = JSONObject(JsonValue.readHjson(metadataString).toString())
    return LocalArtifactAuthorDeclaration(
        hasAuthorField = metadataJson.has("author"),
        declaredAuthorSlotCount = countDeclaredAuthorSlots(metadataJson.opt("author"))
    )
}

private fun inspectToolPkgAuthorDeclaration(sourceFile: File): LocalArtifactAuthorDeclaration {
    ZipFile(sourceFile).use { archive ->
        val entryIndex = ToolPkgArchiveParser.buildZipEntryIndex(archive)
        val manifestEntryName =
            findToolPkgManifestEntryName(entryIndex.entryNames)
                ?: throw IllegalStateException("toolpkg 缺少 manifest.hjson 或 manifest.json")
        val manifestText =
            ToolPkgArchiveParser.readZipEntryText(
                archive = archive,
                entryIndex = entryIndex,
                rawPath = manifestEntryName
            )
                ?: throw IllegalStateException("无法读取 toolpkg manifest")
        val manifestJson = JSONObject(JsonValue.readHjson(manifestText).toString())
        return LocalArtifactAuthorDeclaration(
            hasAuthorField = manifestJson.has("author"),
            declaredAuthorSlotCount = countDeclaredAuthorSlots(manifestJson.opt("author"))
        )
    }
}

private fun extractJsMetadata(jsContent: String): String {
    val metadataPattern = """/\*\s*METADATA\s*([\s\S]*?)\*/""".toRegex()
    val match = metadataPattern.find(jsContent)
    return match?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "{}" }
}

private fun findToolPkgManifestEntryName(entryNames: Set<String>): String? {
    entryNames.firstOrNull { it.equals("manifest.hjson", ignoreCase = true) }?.let { return it }
    entryNames.firstOrNull { it.equals("manifest.json", ignoreCase = true) }?.let { return it }
    entryNames.firstOrNull {
        it.substringAfterLast('/').equals("manifest.hjson", ignoreCase = true)
    }?.let { return it }
    return entryNames.firstOrNull {
        it.substringAfterLast('/').equals("manifest.json", ignoreCase = true)
    }
}

private fun countDeclaredAuthorSlots(rawAuthorValue: Any?): Int {
    return when (rawAuthorValue) {
        null -> 0
        is JSONArray -> rawAuthorValue.length()
        is String -> 1
        else -> 1
    }
}

private fun artifactNodePublisherLogin(node: ArtifactProjectNodeResponse): String {
    return node.publisherLogin.ifBlank { node.issue.user.login }.trim()
}
