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
    val assetRoot: String,
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
            .put("resourceSet", JSONObject(resourceSet.toString()))
            .put("status", status.name.lowercase())
            .put("detail", detail ?: JSONObject.NULL)
            .put("updatedAt", updatedAt)
            .apply { if (includeAssetRoot) put("assetRoot", assetRoot) }
}

/** Records APK resources as a private offer; archives remain in APK assets until a tool requests one. */
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
        val assetRoot = resolveAssetRoot() ?: return null
        val resourceSet = readResourceSet(assetRoot)
        validateResourceSet(resourceSet, packageVersionCode(packageInfo))
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
                assetRoot = assetRoot,
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

    fun readResource(resourceId: String): Pair<String, ByteArray> = synchronized(lock) {
        val offer = readActive() ?: recordCurrentApk() ?: error("APK has no bundled resource offer")
        val archiveName = ARCHIVES[resourceId] ?: error("Unsupported APK resource id: $resourceId")
        val resource = findResource(offer.resourceSet, resourceId)
        val expectedSha = resource.getString("sha256").lowercase()
        val bytes = appContext.assets.open("${offer.assetRoot}/$archiveName").use { it.readBytes() }
        val actualSha = sha256(bytes)
        require(actualSha == expectedSha) {
            "APK resource checksum mismatch for $resourceId: expected $expectedSha, got $actualSha"
        }
        archiveName to bytes
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
                    assetRoot = value.getString("assetRoot"),
                    resourceSet = value.getJSONObject("resourceSet"),
                    status = ApkResourceOfferStatus.valueOf(value.getString("status").uppercase()),
                    detail = value.optString("detail").takeIf(String::isNotBlank),
                    updatedAt = value.getString("updatedAt"),
                )
            }.getOrNull()
        }

    private fun resolveAssetRoot(): String? =
        ASSET_ROOTS.firstOrNull { assetRoot ->
            runCatching { appContext.assets.open("$assetRoot/resource-set.json").close() }.isSuccess
        }

    private fun readResourceSet(assetRoot: String): JSONObject =
        appContext.assets.open("$assetRoot/resource-set.json").bufferedReader().use {
            JSONObject(it.readText())
        }

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
        private val ASSET_ROOTS =
            listOf("openhouse-resources-v2", "openhouse/product-payloads")
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
