package com.openhouse.host.nativeapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ai.assistance.operit.data.preferences.ApiPreferences
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val TERMUX_HOME_BOOKMARK = "termux-home"
internal const val TERMUX_HOME_ENVIRONMENT = "repo:$TERMUX_HOME_BOOKMARK"
internal const val PRE_TMUX_ASSET = "wuxianpi-install/pre-tmux.sh"
internal const val SETUP_RESOURCES_ASSET = "wuxianpi-install/resources.tar"
internal const val RUNTIME_ASSET = "openhouse-runtime/runtime-aarch64.tgz"
internal const val PRE_TMUX_HOME_PATH = ".local/share/wuxianpi/bootstrap/pre-tmux.sh"
internal const val SETUP_RESOURCES_HOME_PATH = ".local/share/wuxianpi/install-resources/resources.tar"
internal const val RUNTIME_HOME_PATH = ".local/share/wuxianpi/install-resources/runtime-aarch64.tgz"
internal const val SETUP_REQUEST_HOME_PATH = ".local/state/wuxianpi-setup/request.json"
internal const val CONTROL_PLANE_ASSET_ROOT = "openhouse-host/control-plane"
internal const val CONTROL_PLANE_HOME_DIRECTORY = ".local/share/openhouseai/control-plane/current"
private const val CONTROL_PLANE_MANIFEST_ASSET = "$CONTROL_PLANE_ASSET_ROOT/control-plane-manifest.json"

internal data class ControlPlaneBundleStageResult(
    val version: String,
    val totalBytes: Long,
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

    /**
     * Installs the scripts invoked by the Native Android control-plane entrypoint. The manifest is
     * written last, so its presence is the completeness marker for the private bundle directory.
     */
    fun stageControlPlaneBundle(): ControlPlaneBundleStageResult {
        val manifest = readControlPlaneManifest()
        val root = requireRoot()
        val expectedFiles = manifest.getJSONArray("files")
        var totalBytes = 0L
        for (index in 0 until expectedFiles.length()) {
            val file = requireNotNull(expectedFiles.optJSONObject(index)) {
                "Invalid control-plane bundle file entry"
            }
            val name = file.optString("name").trim()
            val expectedSha256 = file.optString("sha256").trim().lowercase()
            require(name.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
                "Invalid control-plane bundle file name"
            }
            require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid control-plane bundle checksum for $name"
            }
            totalBytes += stageVerifiedAsset(
                root = root,
                assetPath = "$CONTROL_PLANE_ASSET_ROOT/$name",
                homePath = "$CONTROL_PLANE_HOME_DIRECTORY/$name",
                expectedSha256 = expectedSha256,
            )
        }
        stageVerifiedAsset(
            root = root,
            assetPath = CONTROL_PLANE_MANIFEST_ASSET,
            homePath = "$CONTROL_PLANE_HOME_DIRECTORY/control-plane-manifest.json",
            expectedSha256 = sha256Asset(CONTROL_PLANE_MANIFEST_ASSET),
        )
        return ControlPlaneBundleStageResult(
            version = manifest.optString("version", "unknown"),
            totalBytes = totalBytes,
        )
    }

    private fun requireRoot(): DocumentFile {
        val uri = requireNotNull(persistedTreeUri()) { "Termux Home SAF access is not ready" }
        return requireNotNull(DocumentFile.fromTreeUri(appContext, uri)) {
            "Unable to open Termux Home SAF root"
        }
    }

    private fun readControlPlaneManifest(): JSONObject = appContext.assets
        .open(CONTROL_PLANE_MANIFEST_ASSET)
        .bufferedReader()
        .use { JSONObject(it.readText()) }
        .also { manifest ->
            require(manifest.optInt("schemaVersion", 0) == 1) {
                "Unsupported control-plane bundle manifest"
            }
            require(manifest.optString("bundleId") == "openhouse-control-plane") {
                "Unexpected control-plane bundle manifest"
            }
            require(manifest.optJSONArray("files")?.length()?.let { it > 0 } == true) {
                "Control-plane bundle manifest has no files"
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
