package com.openhouse.host.nativeapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.ai.assistance.operit.data.updates.PatchUpdateInstaller
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal const val WUXIANPI_ALL_IN_ONE_PACKAGE = "com.termux"
internal const val WUXIANPI_HOST_ACTION = "com.termux.SMALLPHONE_HOST"
internal const val WUXIANPI_PREPARE_HOST_ACTION = "com.termux.WUXIANPI_PREPARE_HOST"
internal const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
internal const val TERMUX_DOCUMENTS_AUTHORITY = "com.termux.documents"
internal const val TERMUX_HOME_TREE_ID = "termux-home:"

internal enum class NativeExternalHostState {
    ALL_IN_ONE,
    EXTERNAL_TERMUX,
    ABSENT,
}

internal data class NativeExternalHostProbe(
    val state: NativeExternalHostState,
    val hostComponent: ComponentName? = null,
    val preparationComponent: ComponentName? = null,
    val runCommandComponent: ComponentName? = null,
    val documentsProviderAvailable: Boolean = false,
) {
    val runCommandAvailable: Boolean
        get() = runCommandComponent != null

    val message: String
        get() = when (state) {
            NativeExternalHostState.ALL_IN_ONE -> "WuxianPi All-in-One host is available"
            NativeExternalHostState.EXTERNAL_TERMUX ->
                "External Termux is installed; SAF and RUN_COMMAND can be configured"
            NativeExternalHostState.ABSENT -> "WuxianPi All-in-One host is not installed"
        }
}

internal fun classifyNativeExternalHost(
    packageInstalled: Boolean,
    hostActionResolved: Boolean,
): NativeExternalHostState = when {
    hostActionResolved -> NativeExternalHostState.ALL_IN_ONE
    packageInstalled -> NativeExternalHostState.EXTERNAL_TERMUX
    else -> NativeExternalHostState.ABSENT
}

internal fun canRequestTermuxHomeAccess(probe: NativeExternalHostProbe): Boolean =
    probe.documentsProviderAvailable

internal fun canUseTermuxRunCommand(probe: NativeExternalHostProbe): Boolean =
    probe.runCommandAvailable

internal object NativeExternalHostInspector {
    fun inspect(context: Context): NativeExternalHostProbe {
        val packageManager = context.packageManager
        val packageInstalled = packageManager.hasPackage(WUXIANPI_ALL_IN_ONE_PACKAGE)
        val hostComponent = packageManager.resolveActivityComponent(WUXIANPI_HOST_ACTION)
        val preparationComponent = packageManager.resolveActivityComponent(WUXIANPI_PREPARE_HOST_ACTION)
        val runCommandComponent = packageManager.resolveServiceComponent(TERMUX_RUN_COMMAND_ACTION)
        return NativeExternalHostProbe(
            state = classifyNativeExternalHost(
                packageInstalled = packageInstalled,
                hostActionResolved = hostComponent != null,
            ),
            hostComponent = hostComponent,
            preparationComponent = preparationComponent,
            runCommandComponent = runCommandComponent,
            documentsProviderAvailable = packageManager.hasDocumentsProvider(TERMUX_DOCUMENTS_AUTHORITY),
        )
    }

    fun launchPreparation(context: Context, probe: NativeExternalHostProbe): Boolean {
        val component = probe.preparationComponent ?: return false
        context.startActivity(Intent(WUXIANPI_PREPARE_HOST_ACTION).apply {
            this.component = component
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.hasPackage(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.resolveActivityComponent(action: String): ComponentName? {
        val intent = Intent(action).setPackage(WUXIANPI_ALL_IN_ONE_PACKAGE)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            resolveActivity(intent, 0)
        }
        val activity = info?.activityInfo ?: return null
        return ComponentName(activity.packageName, activity.name)
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.resolveServiceComponent(action: String): ComponentName? {
        val intent = Intent(action).setPackage(WUXIANPI_ALL_IN_ONE_PACKAGE)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveService(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            resolveService(intent, 0)
        }
        val service = info?.serviceInfo ?: return null
        return ComponentName(service.packageName, service.name)
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.hasDocumentsProvider(authority: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0L)) != null
        } else {
            resolveContentProvider(authority, 0) != null
        }
}

internal fun isValidatedTermuxHomeTree(uri: Uri?): Boolean {
    if (uri == null || uri.scheme != "content" || uri.authority != TERMUX_DOCUMENTS_AUTHORITY) return false
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: extractTreeDocumentId(uri.toString())
    return treeId == TERMUX_HOME_TREE_ID
}

internal fun extractTreeDocumentId(uri: String): String? {
    val prefix = "content://$TERMUX_DOCUMENTS_AUTHORITY/tree/"
    if (!uri.startsWith(prefix)) return null
    val encoded = uri.removePrefix(prefix).substringBefore('/')
    return runCatching {
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }.getOrNull()
}

internal data class NativeAllInOneReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

internal object NativeAllInOneReleaseAssetSelector {
    private val android7Variant = Regex("(^|[+_-])apt-android-7([+_-]|$)")

    fun select(releasesJson: String): NativeAllInOneReleaseAsset? {
        val releases = JSONArray(releasesJson)
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.optJSONObject(releaseIndex) ?: continue
            if (release.optBoolean("draft", false)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name").trim()
                val normalized = name.lowercase()
                if (!normalized.startsWith("termux-app_")) continue
                if (!android7Variant.containsMatchIn(normalized)) continue
                if (!normalized.endsWith("_arm64-v8a.apk")) continue
                val url = asset.optString("browser_download_url").trim()
                if (url.isEmpty()) continue
                return NativeAllInOneReleaseAsset(name, url, asset.optLong("size", -1L))
            }
        }
        return null
    }
}

internal class NativeAllInOneReleaseDownloader(
    private val releaseApiUrl: String =
        "https://api.github.com/repos/jiwuyou/openhouseai-app/releases?per_page=10",
) {
    fun fetchLatestArm64Asset(): NativeAllInOneReleaseAsset {
        val connection = openConnection(releaseApiUrl)
        val body = try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Release lookup failed: HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        return NativeAllInOneReleaseAssetSelector.select(body)
            ?: throw IllegalStateException("No ARM64 WuxianPi All-in-One APK exists in recent releases")
    }

    fun download(
        context: Context,
        asset: NativeAllInOneReleaseAsset,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val directory = File(context.cacheDir, "wuxianpi-host-download").apply { mkdirs() }
        val output = File(directory, asset.name)
        val partial = File(directory, "${asset.name}.part")
        val connection = openConnection(asset.downloadUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("APK download failed: HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.size
            connection.inputStream.use { input ->
                FileOutputStream(partial, false).use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        downloaded += read
                        progress(downloaded, total)
                    }
                }
            }
            if (output.exists() && !output.delete()) throw IllegalStateException("Cannot replace old host APK")
            if (!partial.renameTo(output)) throw IllegalStateException("Cannot finalize host APK download")
            return output
        } finally {
            connection.disconnect()
        }
    }

    fun openInstaller(context: Context, apk: File) {
        PatchUpdateInstaller.installApk(context, apk)
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WuxianPi-Native")
        }
}

internal fun JSONObject.putHostProbe(probe: NativeExternalHostProbe): JSONObject = apply {
    put("hostState", probe.state.name.lowercase())
    put("hostPackage", WUXIANPI_ALL_IN_ONE_PACKAGE)
    put("hostAction", WUXIANPI_HOST_ACTION)
    put("preparationAction", WUXIANPI_PREPARE_HOST_ACTION)
    put("allInOneHostAvailable", probe.hostComponent != null)
    put("documentsProviderAvailable", probe.documentsProviderAvailable)
    put("runCommandAvailable", probe.runCommandAvailable)
    put("hostMessage", probe.message)
}
