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
internal const val INSTALL_BUNDLE_METADATA_ASSET = "wuxianpi-install/bundle-index.json"
internal const val INSTALL_BUNDLE_NAME = "openhouse-install-bundle.tar"
internal const val APK_RESOURCE_INBOX_ROOT = ".local/share/openhouseai/apk-resource-inbox"
internal const val TERMUX_PROPERTIES_HOME_PATH = ".termux/termux.properties"

internal data class TermuxPropertiesState(
    val content: String,
    val allowExternalApps: Boolean,
    val duplicateCount: Int,
)

internal data class InstallBundleStageResult(
    val offerId: String,
    val apkVersionCode: Long,
    val inboxDirectory: String,
    val bundlePath: String,
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

    suspend fun inspectExternalAppsConfiguration(): JSONObject = withContext(Dispatchers.IO) {
        val root = requireRoot()
        val parent = findDocument(root, TERMUX_PROPERTIES_HOME_PATH.substringBeforeLast('/'))
            ?.takeIf(DocumentFile::isDirectory)
        val properties = findDocument(root, TERMUX_PROPERTIES_HOME_PATH)?.takeIf(DocumentFile::isFile)
        if (properties == null) {
            return@withContext JSONObject()
                .put("propertiesPath", "\$HOME/$TERMUX_PROPERTIES_HOME_PATH")
                .put("propertiesReadable", false)
                .put("parentWritable", parent != null)
                .put("allowExternalApps", false)
                .put("duplicateCount", 0)
        }
        val state = readTermuxProperties(properties)
        JSONObject()
            .put("propertiesPath", "\$HOME/$TERMUX_PROPERTIES_HOME_PATH")
            .put("propertiesReadable", true)
            .put("parentWritable", true)
            .put("allowExternalApps", state.allowExternalApps)
            .put("duplicateCount", state.duplicateCount)
    }

    /**
     * Enables Termux's external command opt-in without using RUN_COMMAND or relying on
     * DocumentsProvider rename support. All unrelated properties and comments are retained.
     */
    suspend fun configureExternalApps(): JSONObject = withContext(Dispatchers.IO) {
        val root = requireRoot()
        val parent = ensureDirectory(root, TERMUX_PROPERTIES_HOME_PATH.substringBeforeLast('/'))
        val fileName = TERMUX_PROPERTIES_HOME_PATH.substringAfterLast('/')
        val properties = parent.findFile(fileName)?.also {
            require(it.isFile) { "$TERMUX_PROPERTIES_HOME_PATH is not a regular file" }
        } ?: requireNotNull(parent.createFile("text/plain", fileName)) {
            "Unable to create $TERMUX_PROPERTIES_HOME_PATH"
        }
        val current = readTermuxProperties(properties)
        val normalized = normalizeTermuxProperties(current.content)
        requireNotNull(resolver.openOutputStream(properties.uri, "wt")) {
            "Unable to open $TERMUX_PROPERTIES_HOME_PATH for writing"
        }.bufferedWriter().use { it.write(normalized) }
        val verified = readTermuxProperties(properties)
        require(verified.content == normalized) {
            "Unable to verify $TERMUX_PROPERTIES_HOME_PATH after SAF write"
        }
        require(verified.allowExternalApps && verified.duplicateCount == 0) {
            "Termux external-app configuration was not normalized"
        }
        JSONObject()
            .put("propertiesPath", "\$HOME/$TERMUX_PROPERTIES_HOME_PATH")
            .put("propertiesReadable", true)
            .put("parentWritable", true)
            .put("allowExternalApps", true)
            .put("duplicateCount", 0)
            .put("userActionRequired", true)
            .put("action", "reload_termux_settings")
            .put("command", "termux-reload-settings")
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
        require(metadata.optInt("schema") == 2) { "Unsupported install bundle index" }
        require(metadata.optString("bundleAsset") == INSTALL_BUNDLE_ASSET) {
            "Unexpected install bundle asset"
        }
        val bundleSize = metadata.getLong("bundleSize")
        val resourceSetSequence = metadata.getLong("resourceSetSequence")
        require(bundleSize > 0L && resourceSetSequence > 0L) {
            "Invalid install bundle index"
        }
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val root = requireRoot()
        val offerId = "${appContext.packageName}-$apkVersionCode-$resourceSetSequence"
        val relativeDirectory = "$APK_RESOURCE_INBOX_ROOT/$offerId"
        val existingReady = findDocument(root, "$relativeDirectory/.ready")
        val existingBundle = findDocument(root, "$relativeDirectory/$INSTALL_BUNDLE_NAME")
        val reusable = existingReady?.isFile == true && existingReady.length() == 0L &&
            existingBundle?.isFile == true && existingBundle.length() == bundleSize
        if (!reusable) existingReady?.let { ready ->
            require(ready.delete()) { "Unable to clear the previous APK resource ready marker" }
        }
        val copiedBytes = if (reusable) {
            0L
        } else {
            existingBundle?.let { bundle ->
                require(bundle.delete()) { "Unable to replace the unpublished APK install bundle" }
            }
            stageAsset(
                root = root,
                assetPath = INSTALL_BUNDLE_ASSET,
                homePath = "$relativeDirectory/$INSTALL_BUNDLE_NAME",
                expectedSize = bundleSize,
            ).also { createReadyMarker(root, "$relativeDirectory/.ready") }
        }
        InstallBundleStageResult(
            offerId = offerId,
            apkVersionCode = apkVersionCode,
            inboxDirectory = "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory",
            bundlePath = "${NativeOpenHouseHost.TERMUX_HOME}/$relativeDirectory/$INSTALL_BUNDLE_NAME",
            bundleSize = bundleSize,
            resourceSetVersion = metadata.optString("resourceSetVersion"),
            resourceSetSequence = resourceSetSequence,
            copiedBytes = copiedBytes,
        )
    }

    private fun requireRoot(): DocumentFile {
        val uri = requireNotNull(persistedTreeUri()) { "Termux Home SAF access is not ready" }
        return requireNotNull(DocumentFile.fromTreeUri(appContext, uri)) {
            "Unable to open Termux Home SAF root"
        }
    }

    private fun stageAsset(
        root: DocumentFile,
        assetPath: String,
        homePath: String,
        expectedSize: Long,
    ): Long {
        val parent = ensureDirectory(root, homePath.substringBeforeLast('/', ""))
        val fileName = homePath.substringAfterLast('/')
        val existing = parent.findFile(fileName)
        if (existing?.isFile == true && existing.length() == expectedSize) {
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
            require(copied == expectedSize && target.length() == expectedSize) {
                "Install bundle size mismatch for $fileName"
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

    private fun readTermuxProperties(file: DocumentFile): TermuxPropertiesState {
        val content = requireNotNull(resolver.openInputStream(file.uri)) {
            "Unable to read $TERMUX_PROPERTIES_HOME_PATH"
        }.bufferedReader().use { it.readText() }
        return inspectTermuxProperties(content)
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

private val TERMUX_EXTERNAL_APPS_LINE =
    Regex("""^\s*#?\s*allow-external-apps\s*=.*$""")
private val TERMUX_EXTERNAL_APPS_ACTIVE =
    Regex("""^\s*allow-external-apps\s*=\s*true\s*(?:#.*)?$""", RegexOption.IGNORE_CASE)
private val TERMUX_EXTERNAL_APPS_ANY_ACTIVE =
    Regex("""^\s*allow-external-apps\s*=.*$""")

internal fun inspectTermuxProperties(content: String): TermuxPropertiesState {
    val activeLines = content.lineSequence().filter(TERMUX_EXTERNAL_APPS_ANY_ACTIVE::matches).toList()
    return TermuxPropertiesState(
        content = content,
        allowExternalApps = activeLines.size == 1 && TERMUX_EXTERNAL_APPS_ACTIVE.matches(activeLines.single()),
        duplicateCount = (activeLines.size - 1).coerceAtLeast(0),
    )
}

internal fun normalizeTermuxProperties(content: String): String {
    val source = content.replace("\r\n", "\n").replace('\r', '\n')
    val lines = source.split('\n').toMutableList()
    if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
    val first = lines.indexOfFirst(TERMUX_EXTERNAL_APPS_LINE::matches)
    val retained = lines.filterNot(TERMUX_EXTERNAL_APPS_LINE::matches).toMutableList()
    val insertion = if (first < 0) retained.size else first.coerceAtMost(retained.size)
    retained.add(insertion, "allow-external-apps = true")
    return retained.joinToString("\n") + "\n"
}
