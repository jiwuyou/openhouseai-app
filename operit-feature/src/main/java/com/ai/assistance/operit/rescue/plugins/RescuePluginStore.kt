package com.ai.assistance.operit.rescue.plugins

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class InstalledRescuePlugin(
    val manifest: RescuePluginManifest,
    val activeVersion: String,
    val previousVersion: String?,
    val bundled: Boolean,
) {
    fun toJson(): JSONObject =
        manifest.toJson()
            .put("activeVersion", activeVersion)
            .put("previousVersion", previousVersion ?: JSONObject.NULL)
            .put("bundled", bundled)
}

internal class RescuePluginArchiveInstaller(private val stagingRoot: File) {
    fun extractAndValidate(
        archive: ByteArray,
        expectedSha256: String,
        expectedPluginId: String,
        expectedVersion: String,
    ): Pair<File, RescuePluginManifest> {
        val actualSha = sha256(archive)
        require(actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
            "Plugin SHA-256 mismatch: expected $expectedSha256, got $actualSha"
        }

        stagingRoot.mkdirs()
        val staging = File(stagingRoot, "install-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Unable to create plugin staging directory" }
        try {
            unzip(archive, staging)
            val manifestFile = File(staging, MANIFEST_FILE)
            require(manifestFile.isFile) { "Plugin archive is missing $MANIFEST_FILE" }
            val manifest = RescuePluginManifest.parse(JSONObject(manifestFile.readText()))
            require(manifest.id == RescuePluginContract.requirePluginId(expectedPluginId)) {
                "Plugin id mismatch: expected $expectedPluginId, got ${manifest.id}"
            }
            require(manifest.version == RescuePluginContract.requireVersion(expectedVersion)) {
                "Plugin version mismatch: expected $expectedVersion, got ${manifest.version}"
            }
            manifest.entryWorkflow?.let { requirePluginFile(staging, it) }
            manifest.documents.forEach { requirePluginFile(staging, it.path) }
            return staging to manifest
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun unzip(archive: ByteArray, destination: File) {
        val destinationPath = destination.canonicalFile.toPath()
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Plugin archive has too many entries" }
                val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                require(normalizedName.isNotEmpty()) { "Plugin archive contains an empty path" }
                val output = File(destination, normalizedName).canonicalFile
                require(output.toPath().startsWith(destinationPath)) {
                    "Plugin archive path escapes its installation directory: ${entry.name}"
                }
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory) { "Unable to create ${entry.name}" }
                } else {
                    output.parentFile?.let { parent ->
                        check(parent.mkdirs() || parent.isDirectory) {
                            "Unable to create plugin directory ${parent.name}"
                        }
                    }
                    FileOutputStream(output).use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            totalBytes += read
                            require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                "Plugin archive is larger than the supported limit"
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun requirePluginFile(root: File, relativePath: String) {
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) {
            "Plugin manifest references a missing file: $relativePath"
        }
    }

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAX_ARCHIVE_ENTRIES = 2048
        private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

class RescuePluginStore(
    context: Context,
    private val catalogClient: RescuePluginCatalogClient,
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "rescue-plugins")
    private val installedRoot = File(root, "installed")
    private val stagingRoot = File(root, "staging")
    private val stateFile = File(root, "state.json")
    private val installer = RescuePluginArchiveInstaller(stagingRoot)
    private val stateLock = Any()

    suspend fun ensureBundledFirstInstall(): InstalledRescuePlugin = withContext(Dispatchers.IO) {
        val assetRoot = "rescue-plugins/${RescuePluginContract.FIRST_INSTALL_PLUGIN_ID}"
        val manifest =
            appContext.assets.open("$assetRoot/manifest.json").bufferedReader().use { reader ->
                RescuePluginManifest.parse(JSONObject(reader.readText()))
            }
        synchronized(stateLock) {
            readInstalled(manifest.id)?.let {
                return@synchronized it
            }

            val staging = File(stagingRoot, "bundled-${UUID.randomUUID()}")
            check(staging.mkdirs()) { "Unable to create bundled plugin staging directory" }
            try {
                copyAssetTree(assetRoot, staging)
                val copied =
                    RescuePluginManifest.parse(JSONObject(File(staging, "manifest.json").readText()))
                require(copied == manifest) { "Bundled plugin manifest changed while copying" }
                activate(staging, manifest, bundled = true)
            } catch (failure: Throwable) {
                staging.deleteRecursively()
                throw failure
            }
        }
    }

    suspend fun install(pluginId: String, version: String? = null): InstalledRescuePlugin =
        withContext(Dispatchers.IO) {
            val listing = catalogClient.getPlugin(pluginId, version)
            installListing(listing)
        }

    suspend fun update(pluginId: String): InstalledRescuePlugin =
        withContext(Dispatchers.IO) {
            val listing = catalogClient.getPlugin(pluginId)
            val current = synchronized(stateLock) { readInstalled(listing.id) }
            if (current?.activeVersion == listing.version) current else installListing(listing)
        }

    suspend fun listInstalled(): List<InstalledRescuePlugin> = withContext(Dispatchers.IO) {
        ensureBundledFirstInstall()
        synchronized(stateLock) {
            val state = readState()
            state.keys().asSequence().mapNotNull(::readInstalled).sortedBy { it.manifest.name }.toList()
        }
    }

    suspend fun getInstalled(pluginId: String): InstalledRescuePlugin? = withContext(Dispatchers.IO) {
        ensureBundledFirstInstall()
        synchronized(stateLock) { readInstalled(RescuePluginContract.requirePluginId(pluginId)) }
    }

    suspend fun readFile(pluginId: String, relativePath: String): String = withContext(Dispatchers.IO) {
        val installed = getInstalled(pluginId) ?: error("Plugin $pluginId is not installed")
        val pluginRoot = versionDirectory(installed.manifest.id, installed.activeVersion).canonicalFile
        val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
        require(normalized.isNotEmpty()) { "Plugin file path must not be blank" }
        val file = File(pluginRoot, normalized).canonicalFile
        require(file.toPath().startsWith(pluginRoot.toPath()) && file.isFile) {
            "Plugin file does not exist: $relativePath"
        }
        file.readText()
    }

    private suspend fun installListing(listing: RescuePluginListing): InstalledRescuePlugin {
        val expectedSha = listing.sha256 ?: error("Hub did not provide SHA-256 for ${listing.id}")
        val archive = catalogClient.download(listing)
        val (staging, manifest) =
            installer.extractAndValidate(archive, expectedSha, listing.id, listing.version)
        return synchronized(stateLock) { activate(staging, manifest, bundled = false) }
    }

    private fun activate(
        staging: File,
        manifest: RescuePluginManifest,
        bundled: Boolean,
    ): InstalledRescuePlugin {
        val pluginRoot = File(installedRoot, manifest.id)
        check(pluginRoot.mkdirs() || pluginRoot.isDirectory) { "Unable to create plugin directory" }
        val target = versionDirectory(manifest.id, manifest.version)
        val replacement = File(pluginRoot, ".replace-${UUID.randomUUID()}")
        if (target.exists()) {
            check(target.renameTo(replacement)) { "Unable to replace installed plugin ${manifest.id}" }
        }
        if (!staging.renameTo(target)) {
            replacement.takeIf(File::exists)?.renameTo(target)
            throw IOException("Unable to activate plugin ${manifest.id} ${manifest.version}")
        }
        replacement.deleteRecursively()

        val state = readState()
        val old = state.optJSONObject(manifest.id)
        val oldActive = old?.optionalString("active")
        val previous = oldActive?.takeIf { it != manifest.version } ?: old?.optionalString("previous")
        state.put(
            manifest.id,
            JSONObject()
                .put("active", manifest.version)
                .put("previous", previous ?: JSONObject.NULL)
                .put("bundled", bundled),
        )
        writeState(state)
        return InstalledRescuePlugin(manifest, manifest.version, previous, bundled)
    }

    private fun readInstalled(pluginId: String): InstalledRescuePlugin? {
        val record = readState().optJSONObject(pluginId) ?: return null
        val active = record.optionalString("active") ?: return null
        val manifestFile = File(versionDirectory(pluginId, active), "manifest.json")
        if (!manifestFile.isFile) return null
        val manifest = RescuePluginManifest.parse(JSONObject(manifestFile.readText()))
        return InstalledRescuePlugin(
            manifest = manifest,
            activeVersion = active,
            previousVersion = record.optionalString("previous"),
            bundled = record.optBoolean("bundled", false),
        )
    }

    private fun versionDirectory(pluginId: String, version: String): File =
        File(File(installedRoot, RescuePluginContract.requirePluginId(pluginId)), RescuePluginContract.requireVersion(version))

    private fun readState(): JSONObject =
        if (stateFile.isFile) runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        else JSONObject()

    private fun writeState(state: JSONObject) {
        root.mkdirs()
        val temporary = File(root, "state-${UUID.randomUUID()}.tmp")
        temporary.writeText(state.toString(2))
        if (stateFile.exists() && !stateFile.delete()) {
            temporary.delete()
            throw IOException("Unable to replace rescue plugin state")
        }
        if (!temporary.renameTo(stateFile)) {
            temporary.delete()
            throw IOException("Unable to save rescue plugin state")
        }
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { source ->
                destination.outputStream().use(source::copyTo)
            }
            return
        }
        check(destination.mkdirs() || destination.isDirectory) { "Unable to create asset directory" }
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }
}
