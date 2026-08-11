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
import org.json.JSONArray
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
    val bundleSha256: String,
    val bundleSize: Long,
    val resourceSet: JSONObject,
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
            .put("bundleSha256", bundleSha256)
            .put("bundleSize", bundleSize)
            .put("resourceSet", JSONObject(resourceSet.toString()))
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
        val resourceSet = metadata.getJSONObject("resourceSet")
        validateResourceSet(resourceSet, packageVersionCode(packageInfo))
        val bundleAsset = metadata.getString("bundleAsset")
        val bundleSha256 = metadata.getString("bundleSha256").lowercase()
        val bundleSize = metadata.getLong("bundleSize")
        require(bundleAsset == BUNDLE_ASSET && bundleSha256.matches(Regex("[a-f0-9]{64}")) && bundleSize > 0L) {
            "APK install bundle metadata is invalid"
        }
        val versionCode = packageVersionCode(packageInfo)
        val offerId = sha256("${appContext.packageName}:$versionCode:${packageInfo.lastUpdateTime}").take(24)
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
                bundleSha256 = bundleSha256,
                bundleSize = bundleSize,
                resourceSet = resourceSet,
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
                    bundleSha256 = value.getString("bundleSha256"),
                    bundleSize = value.getLong("bundleSize"),
                    resourceSet = value.getJSONObject("resourceSet"),
                    status = ApkResourceOfferStatus.valueOf(value.getString("status").uppercase()),
                    detail = value.optString("detail").takeIf(String::isNotBlank),
                    updatedAt = value.getString("updatedAt"),
                )
            }.getOrNull()
        }

    private fun readBundleMetadata(): JSONObject? = runCatching {
        appContext.assets.open(BUNDLE_METADATA_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }.also { require(it.optInt("schema") == 1) { "Unsupported APK install bundle metadata" } }
    }.getOrNull()

    private fun validateResourceSet(resourceSet: JSONObject, apkVersionCode: Long) {
        require(resourceSet.optInt("schema") == 2) { "Unsupported APK resource set schema" }
        require(resourceSet.optString("id") == "openhouse-core-stack") {
            "Unexpected APK resource set id"
        }
        require(resourceSet.optString("abi") == "arm64-v8a") {
            "APK resource set has an unsupported ABI"
        }
        require(resourceSet.optLong("minApkVersionCode", Long.MAX_VALUE) <= apkVersionCode) {
            "APK resource set requires a newer host"
        }
        val resources = resourceSet.optJSONArray("resources") ?: JSONArray()
        require(resources.length() == ARCHIVES.size) { "APK resource set is incomplete" }
        ARCHIVES.keys.forEach { findResource(resourceSet, it) }
    }

    private fun findResource(resourceSet: JSONObject, resourceId: String): JSONObject {
        val resources = resourceSet.getJSONArray("resources")
        for (index in 0 until resources.length()) {
            val resource = resources.getJSONObject(index)
            if (resource.optString("id") == resourceId) return resource
        }
        error("APK resource set is missing $resourceId")
    }

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
        private const val BUNDLE_METADATA_ASSET = "wuxianpi-install/openhouse-install-bundle.json"
        private val ARCHIVES =
            linkedMapOf(
                "service-manager" to "service-manager.tgz",
                "openhouse-control-plane" to "openhouse-control-plane.tgz",
                "openhouse-runtime" to "runtime-aarch64.tgz",
                "wuyou" to "wuyou.tgz",
                "openhouse-web" to "openhouse-web.tgz",
            )

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
