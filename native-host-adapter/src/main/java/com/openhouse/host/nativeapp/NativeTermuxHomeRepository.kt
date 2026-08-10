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
internal const val SETUP_RESOURCES_ASSET = "wuxianpi-install/resources.tar"
internal const val PRE_TMUX_HOME_PATH = ".local/share/wuxianpi/bootstrap/pre-tmux.sh"
internal const val SETUP_RESOURCES_HOME_PATH = ".local/share/wuxianpi/install-resources/resources.tar"
internal const val SETUP_REQUEST_HOME_PATH = ".local/state/wuxianpi-setup/request.json"
internal const val RESOURCE_SET_ASSET_ROOT = "openhouse-resources-v2"
internal const val RESOURCE_SET_ASSET = "$RESOURCE_SET_ASSET_ROOT/resource-set.json"
internal const val APK_RESOURCE_HOME_ROOT = ".local/share/openhouseai/update-resources"
internal data class BundledResourceSetStageResult(
    val version: String,
    val sequence: Long,
    val apkVersionCode: Long,
    val resourceDirectory: String,
    val runtimeArchive: String,
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
            val temporaryName = ".$fileName.${UUID.randomUUID()}.tmp"
            val temporary = requireNotNull(parent.createFile("application/octet-stream", temporaryName)) {
                "Unable to create temporary $homePath in Termux Home"
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                ByteArrayInputStream(bytes).use { input ->
                    requireNotNull(resolver.openOutputStream(temporary.uri, "w")).use { output ->
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
                replaceTemporaryFile(parent, temporary, fileName)
                bytes.size.toLong()
            } catch (failure: Throwable) {
                temporary.delete()
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

    suspend fun stageBundledResourceSet(): BundledResourceSetStageResult = withContext(Dispatchers.IO) {
        val resourceSet = appContext.assets.open(RESOURCE_SET_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }
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
        val expectedArchives = linkedMapOf(
            "service-manager" to "service-manager.tgz",
            "openhouse-control-plane" to "openhouse-control-plane.tgz",
            "openhouse-runtime" to "runtime-aarch64.tgz",
            "wuyou" to "wuyou.tgz",
            "openhouse-web" to "openhouse-web.tgz",
        )
        val resources = resourceSet.optJSONArray("resources")
            ?: error("Bundled resource set has no resources")
        require(resources.length() == expectedArchives.size) {
            "Bundled resource set must contain exactly five resources"
        }
        val expectedDigests = linkedMapOf<String, String>()
        for (index in 0 until resources.length()) {
            val resource = resources.optJSONObject(index) ?: error("Invalid bundled resource entry")
            val id = resource.optString("id")
            require(expectedArchives.containsKey(id) && !expectedDigests.containsKey(id)) {
                "Unexpected or duplicate bundled resource: $id"
            }
            val digest = resource.optString("sha256").lowercase()
            require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid bundled resource checksum: $id" }
            expectedDigests[id] = digest
        }
        require(expectedDigests.keys == expectedArchives.keys) { "Bundled resource set is incomplete" }

        val root = requireRoot()
        val directoryName = "apk-$apkVersionCode"
        val relativeDirectory = "$APK_RESOURCE_HOME_ROOT/$directoryName"
        findDocument(root, "$relativeDirectory/.complete")?.delete()
        findDocument(root, "$relativeDirectory/.pending")?.delete()
        var copiedBytes = stageVerifiedAsset(
            root = root,
            assetPath = RESOURCE_SET_ASSET,
            homePath = "$relativeDirectory/product-payloads/resource-set.json",
            expectedSha256 = sha256Asset(RESOURCE_SET_ASSET),
        )
        for ((id, archive) in expectedArchives) {
            copiedBytes += stageVerifiedAsset(
                root = root,
                assetPath = "$RESOURCE_SET_ASSET_ROOT/$archive",
                homePath = "$relativeDirectory/product-payloads/$archive",
                expectedSha256 = requireNotNull(expectedDigests[id]),
            )
        }
        val versionName = packageInfo.versionName.orEmpty()
        val marker = JSONObject()
            .put("schemaVersion", 4)
            .put("layout", "openhouse-resource-set-v2")
            .put("apkVersionName", versionName)
            .put("apkVersionCode", apkVersionCode)
            .put("resourceSetId", resourceSet.getString("id"))
            .put("resourceSetVersion", resourceSet.getString("version"))
            .put("resourceSetSequence", resourceSet.getLong("sequence"))
        writeTextAtomically(root, "$relativeDirectory/.complete", marker.toString() + "\n")
        writeTextAtomically(root, "$relativeDirectory/.pending", marker.toString() + "\n")
        writeTextAtomically(
            root,
            "$APK_RESOURCE_HOME_ROOT/PENDING_APK_RESOURCES.json",
            JSONObject(marker.toString())
                .put("resourceDir", "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory")
                .put("reason", "first_install")
                .toString() + "\n",
        )
        BundledResourceSetStageResult(
            version = resourceSet.getString("version"),
            sequence = resourceSet.getLong("sequence"),
            apkVersionCode = apkVersionCode,
            resourceDirectory = "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory",
            runtimeArchive = "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory/product-payloads/runtime-aarch64.tgz",
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
    ): Long {
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        val existing = parent.findFile(fileName)
        if (existing?.isFile == true && sha256Document(existing) == expectedSha256) {
            return 0L
        }
        val temporaryName = ".${fileName}.${UUID.randomUUID()}.tmp"
        val temporary = requireNotNull(parent.createFile("application/octet-stream", temporaryName)) {
            "Unable to create temporary $homePath in Termux Home"
        }
        try {
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            appContext.assets.open(assetPath).use { input ->
                requireNotNull(resolver.openOutputStream(temporary.uri, "w")) {
                    "Unable to open temporary $homePath for writing"
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
            require(actualSha256 == expectedSha256) {
                "Bundled control-plane checksum mismatch for $fileName"
            }
            replaceTemporaryFile(parent, temporary, fileName)
            return copied
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun writeTextAtomically(root: DocumentFile, homePath: String, content: String) {
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        val temporaryName = ".${fileName}.${UUID.randomUUID()}.tmp"
        val temporary = requireNotNull(parent.createFile("application/json", temporaryName)) {
            "Unable to create temporary $homePath in Termux Home"
        }
        try {
            requireNotNull(resolver.openOutputStream(temporary.uri, "w")).bufferedWriter().use {
                it.write(content)
            }
            replaceTemporaryFile(parent, temporary, fileName)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
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

    private fun replaceTemporaryFile(parent: DocumentFile, temporary: DocumentFile, name: String) {
        val previous = parent.findFile(name)
        val backupName = ".${name}.${UUID.randomUUID()}.backup"
        var backup: DocumentFile? = null
        if (previous != null) {
            check(previous.renameTo(backupName)) { "Unable to backup existing $name" }
            backup = parent.findFile(backupName)
        }
        if (!temporary.renameTo(name)) {
            backup?.renameTo(name)
            throw IllegalStateException("Unable to publish $name in Termux Home")
        }
        backup?.delete()
    }

    private fun sha256Asset(assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.assets.open(assetPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

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
