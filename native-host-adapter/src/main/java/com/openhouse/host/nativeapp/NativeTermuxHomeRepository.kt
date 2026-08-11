package com.openhouse.host.nativeapp

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.ai.assistance.operit.data.preferences.ApiPreferences
import java.security.MessageDigest
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val TERMUX_HOME_BOOKMARK = "termux-home"
internal const val TERMUX_HOME_ENVIRONMENT = "repo:$TERMUX_HOME_BOOKMARK"
internal const val PRE_TMUX_ASSET = "wuxianpi-install/pre-tmux.sh"
internal const val PRE_TMUX_HOME_PATH = ".local/share/wuxianpi/bootstrap/pre-tmux.sh"
internal const val SETUP_REQUEST_HOME_PATH = ".local/state/wuxianpi-setup/request.json"
internal const val INSTALL_BUNDLE_ASSET = "wuxianpi-install/openhouse-install-bundle.tar"
internal const val INSTALL_BUNDLE_METADATA_ASSET = "wuxianpi-install/openhouse-install-bundle.json"
internal const val INSTALL_BUNDLE_NAME = "openhouse-install-bundle.tar"
internal const val APK_RESOURCE_INBOX_ROOT = ".local/share/openhouseai/apk-resource-inbox"

internal data class InstallBundleStageResult(
    val offerId: String,
    val apkVersionCode: Long,
    val inboxDirectory: String,
    val bundlePath: String,
    val bundleSha256: String,
    val bundleSize: Long,
    val resourceSetVersion: String,
    val resourceSetSequence: Long,
    val copiedBytes: Long,
)

internal class NativeTermuxHomeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = ApiPreferences.getInstance(appContext)

    fun persistedTreeUri(): Uri? =
        resolver.persistedUriPermissions.firstOrNull { permission ->
            permission.isReadPermission && permission.isWritePermission &&
                isValidatedTermuxHomeTree(permission.uri)
        }?.uri

    suspend fun registerAndProbe(uri: Uri = requireNotNull(persistedTreeUri())): JSONObject =
        withContext(Dispatchers.IO) {
            preferences.addSafBookmark(uri.toString(), TERMUX_HOME_BOOKMARK)
            val bookmarkReady = preferences.safBookmarksFlow.first().any {
                it.name.equals(TERMUX_HOME_BOOKMARK, ignoreCase = true) && it.uri == uri.toString()
            }
            val probeReady = probeReadWrite(uri)
            JSONObject()
                .put("termuxHomeAccess", true)
                .put("termuxHomeBookmark", TERMUX_HOME_BOOKMARK)
                .put("termuxHomeEnvironment", TERMUX_HOME_ENVIRONMENT)
                .put("termuxHomeBookmarkReady", bookmarkReady)
                .put("termuxHomeReadWriteReady", probeReady)
                .put("rescueWorkspaceReady", bookmarkReady && probeReady)
        }

    suspend fun stageAsset(assetPath: String, homePath: String): Long = withContext(Dispatchers.IO) {
        val root = requireRoot()
        val parentPath = homePath.substringBeforeLast('/', "")
        val fileName = homePath.substringAfterLast('/')
        val parent = ensureDirectory(root, parentPath)
        parent.findFile(fileName)?.delete()
        val target = requireNotNull(parent.createFile("application/octet-stream", fileName)) {
            "Unable to create $homePath in Termux Home"
        }
        var copied = 0L
        appContext.assets.open(assetPath).use { input ->
            requireNotNull(resolver.openOutputStream(target.uri, "w")) {
                "Unable to open $homePath for writing"
            }.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    copied += read
                }
            }
        }
        copied
    }

    suspend fun writeText(homePath: String, content: String) = withContext(Dispatchers.IO) {
        val root = requireRoot()
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        parent.findFile(fileName)?.delete()
        val target = requireNotNull(parent.createFile("application/json", fileName)) {
            "Unable to create $homePath in Termux Home"
        }
        requireNotNull(resolver.openOutputStream(target.uri, "w")).bufferedWriter().use {
            it.write(content)
        }
    }

    suspend fun stageBytes(homePath: String, bytes: ByteArray, expectedSha256: String): Long =
        withContext(Dispatchers.IO) {
            val root = requireRoot()
            val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
            val fileName = homePath.substringAfterLast('/')
            val existing = parent.findFile(fileName)
            if (existing?.isFile == true && sha256Document(existing) == expectedSha256.lowercase()) {
                return@withContext 0L
            }
            existing?.delete()
            val target = requireNotNull(parent.createFile("application/octet-stream", fileName)) {
                "Unable to create $homePath in Termux Home"
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                ByteArrayInputStream(bytes).use { input ->
                    requireNotNull(resolver.openOutputStream(target.uri, "w")).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                            }
                        }
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                require(actualSha256 == expectedSha256.lowercase()) {
                    "APK resource checksum changed before SAF staging"
                }
                require(sha256Document(target) == actualSha256) {
                    "APK resource checksum changed after SAF staging"
                }
                bytes.size.toLong()
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        }

    suspend fun readBytes(homePath: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val root = requireRoot()
        val file = findDocument(root, homePath)?.takeIf(DocumentFile::isFile)
            ?: error("Termux Home file does not exist: $homePath")
        requireNotNull(resolver.openInputStream(file.uri)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    require(output.size() + read <= maxBytes) { "Termux Home file is too large" }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    suspend fun stageInstallBundle(): InstallBundleStageResult = withContext(Dispatchers.IO) {
        val metadata = appContext.assets.open(INSTALL_BUNDLE_METADATA_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }
        require(metadata.optInt("schema") == 1) { "Unsupported install bundle metadata" }
        require(metadata.optString("bundleAsset") == INSTALL_BUNDLE_ASSET) {
            "Unexpected install bundle asset"
        }
        val bundleSha256 = metadata.getString("bundleSha256").lowercase()
        val bundleSize = metadata.getLong("bundleSize")
        require(bundleSha256.matches(Regex("[0-9a-f]{64}")) && bundleSize > 0L) {
            "Invalid install bundle integrity metadata"
        }
        val resourceSet = metadata.getJSONObject("resourceSet")
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        require(resourceSet.optInt("schema", 0) == 2) { "Unsupported bundled resource set schema" }
        require(resourceSet.optString("id") == "openhouse-core-stack") { "Unexpected bundled resource set" }
        require(resourceSet.optString("abi") == "arm64-v8a") { "Bundled resource set is not ARM64" }
        require(resourceSet.optLong("minApkVersionCode", Long.MAX_VALUE) <= apkVersionCode) {
            "Bundled resource set requires a newer APK"
        }
        val expectedIds = setOf(
            "service-manager", "openhouse-control-plane", "openhouse-runtime", "wuyou", "openhouse-web",
        )
        val resources = resourceSet.optJSONArray("resources")
            ?: error("Bundled resource set has no resources")
        require(resources.length() == expectedIds.size) {
            "Bundled resource set must contain exactly five resources"
        }
        val actualIds = linkedSetOf<String>()
        for (index in 0 until resources.length()) {
            val resource = resources.optJSONObject(index) ?: error("Invalid bundled resource entry")
            val id = resource.optString("id")
            require(id in expectedIds && actualIds.add(id)) {
                "Unexpected or duplicate bundled resource: $id"
            }
            val digest = resource.optString("sha256").lowercase()
            require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid bundled resource checksum: $id" }
        }
        require(actualIds == expectedIds) { "Bundled resource set is incomplete" }

        val root = requireRoot()
        val offerId = sha256("${appContext.packageName}:$apkVersionCode:${packageInfo.lastUpdateTime}").take(24)
        val relativeDirectory = "$APK_RESOURCE_INBOX_ROOT/$offerId"
        findDocument(root, "$relativeDirectory/.ready")?.let { ready ->
            require(ready.delete()) { "Unable to clear the previous APK resource ready marker" }
        }
        val copiedBytes = stageVerifiedAsset(
            root = root,
            assetPath = INSTALL_BUNDLE_ASSET,
            homePath = "$relativeDirectory/$INSTALL_BUNDLE_NAME",
            expectedSha256 = bundleSha256,
            expectedSize = bundleSize,
        )
        val offer = JSONObject()
            .put("schema", 1)
            .put("offerId", offerId)
            .put("apkVersionName", packageInfo.versionName.orEmpty())
            .put("apkVersionCode", apkVersionCode)
            .put("bundleFile", INSTALL_BUNDLE_NAME)
            .put("bundleSha256", bundleSha256)
            .put("bundleSize", bundleSize)
            .put("resourceSet", JSONObject(resourceSet.toString()))
        writeTextDirect(root, "$relativeDirectory/offer.json", offer.toString(2) + "\n")
        createReadyMarker(root, "$relativeDirectory/.ready")
        InstallBundleStageResult(
            offerId = offerId,
            apkVersionCode = apkVersionCode,
            inboxDirectory = "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory",
            bundlePath = "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory/$INSTALL_BUNDLE_NAME",
            bundleSha256 = bundleSha256,
            bundleSize = bundleSize,
            resourceSetVersion = resourceSet.getString("version"),
            resourceSetSequence = resourceSet.getLong("sequence"),
            copiedBytes = copiedBytes,
        )
    }

    private fun requireRoot(): DocumentFile {
        val uri = requireNotNull(persistedTreeUri()) { "Termux Home SAF access is not ready" }
        return requireNotNull(DocumentFile.fromTreeUri(appContext, uri)) {
            "Unable to open Termux Home SAF root"
        }
    }

    private fun stageVerifiedAsset(
        root: DocumentFile,
        assetPath: String,
        homePath: String,
        expectedSha256: String,
        expectedSize: Long,
    ): Long {
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        val existing = parent.findFile(fileName)
        if (existing?.isFile == true && existing.length() == expectedSize &&
            sha256Document(existing) == expectedSha256
        ) {
            return 0L
        }
        existing?.let { document ->
            require(document.delete()) { "Unable to replace invalid $homePath in Termux Home" }
        }
        val target = requireNotNull(parent.createFile("application/octet-stream", fileName)) {
            "Unable to create $homePath in Termux Home"
        }
        try {
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            appContext.assets.open(assetPath).use { input ->
                requireNotNull(resolver.openOutputStream(target.uri, "w")) {
                    "Unable to open $homePath for writing"
                }.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
            }
            val actualSha256 = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            require(copied == expectedSize && actualSha256 == expectedSha256) {
                "Install bundle integrity mismatch for $fileName"
            }
            require(target.length() == expectedSize && sha256Document(target) == expectedSha256) {
                "Install bundle changed after SAF staging"
            }
            return copied
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun writeTextDirect(root: DocumentFile, homePath: String, content: String) {
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        parent.findFile(fileName)?.let { existing ->
            require(existing.delete()) { "Unable to replace $homePath in Termux Home" }
        }
        val target = requireNotNull(parent.createFile("application/json", fileName)) {
            "Unable to create $homePath in Termux Home"
        }
        try {
            requireNotNull(resolver.openOutputStream(target.uri, "w")).bufferedWriter().use {
                it.write(content)
            }
            require(resolver.openInputStream(target.uri)?.bufferedReader()?.use { it.readText() } == content) {
                "Unable to verify $homePath after SAF write"
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun createReadyMarker(root: DocumentFile, homePath: String) {
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        parent.findFile(fileName)?.let { existing ->
            require(existing.delete()) { "Unable to replace the APK resource ready marker" }
        }
        val marker = requireNotNull(parent.createFile("application/octet-stream", fileName)) {
            "Unable to publish APK resource ready marker"
        }
        require(marker.isFile && marker.length() == 0L) {
            marker.delete()
            "APK resource ready marker is not an empty file"
        }
    }

    private fun findDocument(root: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile? = root
        for (segment in path.split('/').filter(String::isNotBlank)) {
            current = current?.findFile(segment) ?: return null
        }
        return current
    }

    private fun sha256Document(file: DocumentFile): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        requireNotNull(resolver.openInputStream(file.uri)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ensureDirectory(root: DocumentFile, path: String): DocumentFile {
        var current = root
        path.split('/').filter(String::isNotBlank).forEach { segment ->
            current = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: requireNotNull(current.createDirectory(segment)) {
                    "Unable to create Termux Home directory $segment"
                }
        }
        return current
    }

    private fun probeReadWrite(uri: Uri): Boolean = runCatching {
        val root = requireNotNull(DocumentFile.fromTreeUri(appContext, uri))
        val name = ".wuxianpi-saf-probe-${UUID.randomUUID()}"
        val probe = requireNotNull(root.createFile("text/plain", name))
        try {
            resolver.openOutputStream(probe.uri, "w")!!.bufferedWriter().use { it.write("ready") }
            resolver.openInputStream(probe.uri)!!.bufferedReader().use { it.readText() } == "ready"
        } finally {
            probe.delete()
        }
    }.getOrDefault(false)
}

internal fun isTermuxHomeWorkspaceReady(details: JSONObject): Boolean =
    details.optBoolean("termuxHomeBookmarkReady", false) &&
        details.optBoolean("termuxHomeReadWriteReady", false)
