package com.ai.assistance.operit.data.updates

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ai.assistance.operit.util.AppLogger

// 更新状态 - 移除下载相关状态
sealed class UpdateStatus {
    object Initial : UpdateStatus()
    object Checking : UpdateStatus()
    data class Available(
            val newVersion: String,
            val updateUrl: String,
            val releaseNotes: String,
            val downloadUrl: String = "" // 保留下载URL字段用于浏览器打开
    ) : UpdateStatus()
    data class PatchAvailable(
            val newVersion: String,
            val updateUrl: String,
            val releaseNotes: String,
            val patchUrl: String,
            val metaUrl: String
    ) : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

/** UpdateManager - 处理应用更新的核心类 负责检查更新 */
class UpdateManager private constructor(private val context: Context) {
    private val TAG = "UpdateManager"

    // 更新状态LiveData，可从UI中观察
    private val _updateStatus = MutableLiveData<UpdateStatus>(UpdateStatus.Initial)
    val updateStatus: LiveData<UpdateStatus> = _updateStatus

    init {
        AppLogger.d(TAG, "UpdateManager initialized")
    }

    companion object {
        @Volatile private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE
                    ?: synchronized(this) {
                        val instance = UpdateManager(context.applicationContext)
                        INSTANCE = instance
                        instance
                    }
        }

        /**
         * 比较两个版本号
         * @return -1 如果v1 < v2, 0 如果 v1 == v2, 1 如果 v1 > v2
         */
        private data class ParsedVersion(val major: Int, val minor: Int, val patch: Int, val patchIndex: Int)

        private fun parseVersion(v: String): ParsedVersion {
            val s = v.trim().removePrefix("v")
            val plusIdx = s.indexOf('+')
            val base = if (plusIdx >= 0) s.substring(0, plusIdx) else s
            val patchIndex =
                if (plusIdx >= 0) s.substring(plusIdx + 1).toIntOrNull() ?: 0 else 0

            val parts = base.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

            return ParsedVersion(major = major, minor = minor, patch = patch, patchIndex = patchIndex)
        }

        fun compareVersions(v1: String, v2: String): Int {
            val p1 = parseVersion(v1)
            val p2 = parseVersion(v2)

            if (p1.major != p2.major) return p1.major.compareTo(p2.major)
            if (p1.minor != p2.minor) return p1.minor.compareTo(p2.minor)
            if (p1.patch != p2.patch) return p1.patch.compareTo(p2.patch)
            return p1.patchIndex.compareTo(p2.patchIndex)
        }

        /** 保留兼容入口；WuxianPi 不使用上游 Operit 的应用更新通道。 */
        suspend fun checkForUpdates(context: Context, currentVersion: String): UpdateStatus {
            val manager = getInstance(context)
            return manager.checkForUpdatesInternal(currentVersion)
        }
    }

    suspend fun checkForUpdatesSilently(currentVersion: String) {
        AppLogger.d(TAG, "Application update checks are disabled: currentVersion=$currentVersion")
    }

    /** 开始更新检查流程 */
    suspend fun checkForUpdates(currentVersion: String) {
        _updateStatus.postValue(checkForUpdatesInternal(currentVersion))
    }

    /**
     * WuxianPi owns its release lifecycle outside the embedded Operit feature. Keeping this method
     * local-only prevents compatibility callers from querying or installing upstream Operit APKs.
     */
    private suspend fun checkForUpdatesInternal(currentVersion: String): UpdateStatus {
        AppLogger.d(TAG, "Application update source is not configured: currentVersion=$currentVersion")
        return UpdateStatus.UpToDate
    }
}
