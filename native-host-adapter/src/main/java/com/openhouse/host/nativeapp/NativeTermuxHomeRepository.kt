package com.openhouse.host.nativeapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ai.assistance.operit.data.preferences.ApiPreferences
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

    private fun requireRoot(): DocumentFile {
        val uri = requireNotNull(persistedTreeUri()) { "Termux Home SAF access is not ready" }
        return requireNotNull(DocumentFile.fromTreeUri(appContext, uri)) {
            "Unable to open Termux Home SAF root"
        }
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
