package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.host.OperitHostProvider

object OperitTerminalManager {
    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    fun isInstalled(context: Context): Boolean {
        return OperitHostProvider.currentOrNull() != null
    }

    fun getInstalledVersion(context: Context): String? {
        return if (isInstalled(context)) "SmallPhoneAI host" else null
    }

    suspend fun fetchLatestReleaseInfo(context: Context): ReleaseInfo? {
        return null
    }
}
