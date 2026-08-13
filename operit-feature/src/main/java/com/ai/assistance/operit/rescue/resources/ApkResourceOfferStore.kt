package com.ai.assistance.operit.rescue.resources

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class ApkResourceOfferStatus {
    PENDING,
    SATISFIED,
    SUPERSEDED,
    FAILED,
    DISMISSED,
}

private const val MAX_APK_RESOURCE_OFFER_DETAIL_CHARS = 2048

/** Validates that success-like states retain concrete verification evidence. */
internal fun normalizedApkResourceOfferCompletionDetail(
    status: ApkResourceOfferStatus,
    detail: String,
): String {
    require(status != ApkResourceOfferStatus.PENDING) { "PENDING is not a completion state" }
    val normalized = detail.trim()
    if (status == ApkResourceOfferStatus.SATISFIED || status == ApkResourceOfferStatus.SUPERSEDED) {
        require(normalized.isNotEmpty()) {
            "A verified update result is required for ${status.name.lowercase()}"
        }
    }
    return normalized.take(MAX_APK_RESOURCE_OFFER_DETAIL_CHARS)
}

data class ApkResourceOffer(
    val offerId: String,
    val apkVersionName: String,
    val apkVersionCode: Long,
    val packageLastUpdateTime: Long,
    val reason: String,
    val bundleAsset: String,
    val bundleSize: Long,
    val bundleId: String,
    val resourceSetVersion: String,
    val resourceSetSequence: Long,
    val status: ApkResourceOfferStatus,
    val detail: String?,
    val updatedAt: String,
) {
    val requiresReminder: Boolean
        get() = status == ApkResourceOfferStatus.PENDING || status == ApkResourceOfferStatus.FAILED

    fun toJson(includeAssetRoot: Boolean = false): JSONObject =
        JSONObject()
            .put("offerId", offerId)
            .put("apkVersionName", apkVersionName)
            .put("apkVersionCode", apkVersionCode)
            .put("packageLastUpdateTime", packageLastUpdateTime)
            .put("reason", reason)
            .put("bundleSize", bundleSize)
            .put("bundleId", bundleId)
            .put("resourceSetVersion", resourceSetVersion)
            .put("resourceSetSequence", resourceSetSequence)
            .put("status", status.name.lowercase())
            .put("detail", detail ?: JSONObject.NULL)
            .put("updatedAt", updatedAt)
            .apply { if (includeAssetRoot) put("bundleAsset", bundleAsset) }
}

/** Records the APK's canonical install bundle as a private offer until a tool stages it. */
class ApkResourceOfferStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "rescue-resource-offers")
    private val activeFile = File(root, "active.json")
    private val lock = Any()
    private val _activeOffer = MutableStateFlow(readActive())
    val activeOffer: StateFlow<ApkResourceOffer?> = _activeOffer.asStateFlow()

    fun recordCurrentApk(): ApkResourceOffer? = synchronized(lock) {
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull() ?: return null
        val metadata = readBundleMetadata() ?: return null
        val versionCode = packageVersionCode(packageInfo)
        require(metadata.optInt("schema") == 2) { "Unsupported APK install bundle index" }
        val bundleAsset = metadata.getString("bundleAsset")
        val bundleSize = metadata.getLong("bundleSize")
        val bundleId = metadata.getString("bundleId")
        val resourceSetVersion = metadata.getString("resourceSetVersion")
        val resourceSetSequence = metadata.getLong("resourceSetSequence")
        require(bundleAsset == BUNDLE_ASSET && bundleSize > 0L && bundleId.isNotBlank() && resourceSetSequence > 0L) {
            "APK install bundle index is invalid"
        }
        val offerId = "${appContext.packageName}-$versionCode-$resourceSetSequence"
        val existing = readActive()
        if (existing?.offerId == offerId) {
            _activeOffer.value = existing
            return existing
        }
        val preferences =
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val previousVersion = preferences.getLong(KEY_OBSERVED_VERSION, 0L)
        val offer =
            ApkResourceOffer(
                offerId = offerId,
                apkVersionName = packageInfo.versionName.orEmpty(),
                apkVersionCode = versionCode,
                packageLastUpdateTime = packageInfo.lastUpdateTime,
                reason = if (previousVersion <= 0L) "first-install" else "apk-update",
                bundleAsset = bundleAsset,
                bundleSize = bundleSize,
                bundleId = bundleId,
                resourceSetVersion = resourceSetVersion,
                resourceSetSequence = resourceSetSequence,
                status = ApkResourceOfferStatus.PENDING,
                detail = null,
                updatedAt = Instant.now().toString(),
            )
        write(offer)
        preferences.edit().putLong(KEY_OBSERVED_VERSION, versionCode).commit()
        offer
    }

    fun current(): ApkResourceOffer? = synchronized(lock) {
        (readActive() ?: recordCurrentApk()).also { _activeOffer.value = it }
    }

    fun dismissCurrent(): ApkResourceOffer? =
        complete(ApkResourceOfferStatus.DISMISSED, "User ended this APK update reminder")

    fun complete(status: ApkResourceOfferStatus, detail: String): ApkResourceOffer? =
        synchronized(lock) {
            val current = readActive() ?: return null
            val normalizedDetail = normalizedApkResourceOfferCompletionDetail(status, detail)
            current.copy(
                status = status,
                detail = normalizedDetail,
                updatedAt = Instant.now().toString(),
            ).also(::write)
        }

    private fun write(offer: ApkResourceOffer) {
        root.mkdirs()
        val payload = offer.toJson(includeAssetRoot = true).toString(2)
        val temporary = File(root, ".active-${UUID.randomUUID()}.tmp")
        temporary.writeText(payload)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    activeFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), activeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        _activeOffer.value = offer
    }

    private fun readActive(): ApkResourceOffer? =
        activeFile.takeIf(File::isFile)?.let { file ->
            runCatching {
                val value = JSONObject(file.readText())
                ApkResourceOffer(
                    offerId = value.getString("offerId"),
                    apkVersionName = value.getString("apkVersionName"),
                    apkVersionCode = value.getLong("apkVersionCode"),
                    packageLastUpdateTime = value.getLong("packageLastUpdateTime"),
                    reason = value.getString("reason"),
                    bundleAsset = value.getString("bundleAsset"),
                    bundleSize = value.getLong("bundleSize"),
                    bundleId = value.getString("bundleId"),
                    resourceSetVersion = value.getString("resourceSetVersion"),
                    resourceSetSequence = value.getLong("resourceSetSequence"),
                    status = ApkResourceOfferStatus.valueOf(value.getString("status").uppercase()),
                    detail = value.optString("detail").takeIf(String::isNotBlank),
                    updatedAt = value.getString("updatedAt"),
                )
            }.getOrNull()
        }

    private fun readBundleMetadata(): JSONObject? = runCatching {
        appContext.assets.open(BUNDLE_METADATA_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }.also { require(it.optInt("schema") == 2) { "Unsupported APK install bundle index" } }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFERENCES_NAME = "rescue_resource_offers"
        private const val KEY_OBSERVED_VERSION = "observed_apk_version"
        private const val BUNDLE_ASSET = "wuxianpi-install/openhouse-install-bundle.tar"
        private const val BUNDLE_METADATA_ASSET = "wuxianpi-install/bundle-index.json"
        @Volatile private var instance: ApkResourceOfferStore? = null

        @JvmStatic
        fun get(context: Context): ApkResourceOfferStore =
            instance ?: synchronized(this) {
                instance ?: ApkResourceOfferStore(context.applicationContext).also { instance = it }
            }

        @JvmStatic
        fun recordCurrentApk(context: Context): ApkResourceOffer? = get(context).recordCurrentApk()
    }
}
