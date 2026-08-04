package com.ai.assistance.operit.ui.features.packages.market

import android.content.Context
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.api.ArtifactProjectNodeResponse
import com.ai.assistance.operit.data.api.MarketStatsApiService
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalInstalledArtifactSnapshot(
    val packageName: String,
    val sha256: String,
    val isBuiltIn: Boolean
)

enum class LocalArtifactInstallStateKind {
    NOT_INSTALLED,
    EXACT_INSTALLED,
    SAME_PROJECT_VARIANT_INSTALLED,
    NAME_CONFLICT,
    BUILT_IN_CONFLICT
}

data class LocalArtifactInstallState(
    val kind: LocalArtifactInstallStateKind,
    val snapshot: LocalInstalledArtifactSnapshot? = null
)

fun PackageManager.getInstalledArtifactSnapshots(): Map<String, LocalInstalledArtifactSnapshot> {
    val publishableSourcesByName =
        getPublishablePackageSources().associateBy { source -> source.packageName }

    return getTopLevelAvailablePackages()
        .mapValues { (packageName, toolPackage) ->
            val sourceFile =
                publishableSourcesByName[packageName]
                    ?.sourcePath
                    ?.let(::File)
            LocalInstalledArtifactSnapshot(
                packageName = packageName,
                // Built-in packages do not always have a stable external source file path.
                sha256 = if (sourceFile != null && sourceFile.exists() && sourceFile.isFile) sha256Hex(sourceFile) else "",
                isBuiltIn = toolPackage.isBuiltIn
            )
        }
}

private fun findInstalledArtifactSnapshot(
    installedSnapshots: Map<String, LocalInstalledArtifactSnapshot>,
    packageName: String
): LocalInstalledArtifactSnapshot? {
    val trimmedPackageName = packageName.trim()
    if (trimmedPackageName.isBlank()) {
        return null
    }

    installedSnapshots[trimmedPackageName]?.let { return it }

    return installedSnapshots.values.firstOrNull { snapshot ->
        sameArtifactRuntimePackageId(snapshot.packageName, trimmedPackageName)
    }
}

fun resolveLocalArtifactInstallState(
    installedSnapshots: Map<String, LocalInstalledArtifactSnapshot>,
    packageName: String,
    targetSha256: String,
    projectNodeSha256s: Collection<String>
): LocalArtifactInstallState {
    val installed =
        findInstalledArtifactSnapshot(
            installedSnapshots = installedSnapshots,
            packageName = packageName
        ) ?: return LocalArtifactInstallState(LocalArtifactInstallStateKind.NOT_INSTALLED)
    val normalizedInstalledSha = installed.sha256.trim().lowercase()
    val normalizedTargetSha = targetSha256.trim().lowercase()
    if (normalizedInstalledSha.isNotBlank() && normalizedInstalledSha == normalizedTargetSha) {
        return LocalArtifactInstallState(
            kind = LocalArtifactInstallStateKind.EXACT_INSTALLED,
            snapshot = installed
        )
    }
    if (installed.isBuiltIn) {
        return LocalArtifactInstallState(
            kind = LocalArtifactInstallStateKind.BUILT_IN_CONFLICT,
            snapshot = installed
        )
    }

    val projectHashes =
        projectNodeSha256s
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    return if (normalizedInstalledSha.isNotBlank() && normalizedInstalledSha in projectHashes) {
        LocalArtifactInstallState(
            kind = LocalArtifactInstallStateKind.SAME_PROJECT_VARIANT_INSTALLED,
            snapshot = installed
        )
    } else {
        LocalArtifactInstallState(
            kind = LocalArtifactInstallStateKind.NAME_CONFLICT,
            snapshot = installed
        )
    }
}

suspend fun installArtifactProjectNode(
    context: Context,
    packageManager: PackageManager,
    marketStatsApiService: MarketStatsApiService,
    projectId: String,
    projectNodes: List<ArtifactProjectNodeResponse>,
    node: ArtifactProjectNodeResponse,
    logTag: String
) {
    val installedSnapshots =
        withContext(Dispatchers.IO) {
            packageManager.getInstalledArtifactSnapshots()
        }
    val installState =
        resolveLocalArtifactInstallState(
            installedSnapshots = installedSnapshots,
            packageName = node.runtimePackageId,
            targetSha256 = node.sha256,
            projectNodeSha256s =
                projectNodes
                    .filter {
                        sameArtifactRuntimePackageId(it.runtimePackageId, node.runtimePackageId)
                    }
                    .map { it.sha256 }
        )

    when (installState.kind) {
        LocalArtifactInstallStateKind.EXACT_INSTALLED -> return
        LocalArtifactInstallStateKind.BUILT_IN_CONFLICT ->
            throw IllegalStateException("本地已安装同名内置插件 `${node.runtimePackageId}`，不能直接覆盖。")
        LocalArtifactInstallStateKind.NAME_CONFLICT ->
            throw IllegalStateException("本地已安装同名但不属于当前项目簇的插件 `${node.runtimePackageId}`，请先手动卸载后再安装。")
        LocalArtifactInstallStateKind.NOT_INSTALLED,
        LocalArtifactInstallStateKind.SAME_PROJECT_VARIANT_INSTALLED -> Unit
    }

    val tempFile = withContext(Dispatchers.IO) { downloadArtifactProjectNodeToTempFile(context, node) }
    try {
        if (installState.kind == LocalArtifactInstallStateKind.SAME_PROJECT_VARIANT_INSTALLED) {
            val installedPackageName = installState.snapshot?.packageName ?: node.runtimePackageId
            val deleted =
                withContext(Dispatchers.IO) {
                    packageManager.deletePackage(installedPackageName)
                }
            if (!deleted) {
                throw IllegalStateException("替换已安装插件 `${installedPackageName}` 失败。")
            }
        }
        val importResult =
            withContext(Dispatchers.IO) {
                packageManager.addPackageFileFromExternalStorage(tempFile.absolutePath)
            }
        if (!importResult.startsWith("Successfully imported", ignoreCase = true)) {
            throw IllegalStateException(importResult)
        }
        trackArtifactProjectNodeDownload(
            marketStatsApiService = marketStatsApiService,
            projectId = projectId,
            node = node,
            logTag = logTag
        )
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

private suspend fun trackArtifactProjectNodeDownload(
    marketStatsApiService: MarketStatsApiService,
    projectId: String,
    node: ArtifactProjectNodeResponse,
    logTag: String
) {
    val artifactType =
        PublishArtifactType.fromWireValue(node.type)
            ?: return
    val targetUrl =
        resolveMarketDownloadTarget(
            preferredUrl = node.downloadUrl,
            fallbackUrl = node.issue.html_url
        )
    marketStatsApiService.trackDownload(
        type = artifactType.toMarketStatsType().wireValue,
        id = projectId,
        targetUrl = targetUrl
    ).onFailure { error ->
        AppLogger.w(logTag, "Failed to track artifact download for project=$projectId: ${error.message}")
    }
}

private fun downloadArtifactProjectNodeToTempFile(
    context: Context,
    node: ArtifactProjectNodeResponse
): File {
    val downloadDir = File(context.cacheDir, "market_downloads")
    if (!downloadDir.exists() && !downloadDir.mkdirs()) {
        throw IllegalStateException("Failed to create market download cache")
    }

    val targetFile = File(downloadDir, node.assetName.ifBlank { "${node.runtimePackageId}.bin" })
    val connection = URL(node.downloadUrl).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    connection.requestMethod = "GET"

    try {
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Download failed: HTTP $code")
        }

        val inputStream = connection.inputStream ?: throw IllegalStateException("Empty download stream")
        inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } finally {
        connection.disconnect()
    }

    val actualSha256 = sha256Hex(targetFile)
    if (!actualSha256.equals(node.sha256, ignoreCase = true)) {
        targetFile.delete()
        throw IllegalStateException("Downloaded file sha256 mismatch")
    }

    return targetFile
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
