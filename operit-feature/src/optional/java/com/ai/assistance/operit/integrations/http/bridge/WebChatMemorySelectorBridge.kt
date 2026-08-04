package com.ai.assistance.operit.integrations.http.bridge

import android.content.Context
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.integrations.http.WebMemoryProfileItem
import com.ai.assistance.operit.integrations.http.WebMemorySelectorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal class WebChatMemorySelectorBridge(
    context: Context
) {
    private val appContext = context.applicationContext
    private val userPreferencesManager = UserPreferencesManager.getInstance(appContext)

    suspend fun resolveState(): WebMemorySelectorState {
        val currentProfileId = userPreferencesManager.activeProfileIdFlow.first()
        val profileIds = userPreferencesManager.profileListFlow.first()
        val profiles =
            profileIds.map { profileId ->
                val profile = userPreferencesManager.getUserPreferencesFlow(profileId).first()
                WebMemoryProfileItem(
                    id = profile.id,
                    name = profile.name
                )
            }
        return WebMemorySelectorState(
            currentProfileId = currentProfileId,
            profiles = profiles
        )
    }

    suspend fun selectProfile(profileId: String): WebMemorySelectorState? {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            return null
        }

        val profileIds = userPreferencesManager.profileListFlow.first()
        if (!profileIds.contains(normalizedProfileId)) {
            return null
        }

        userPreferencesManager.setActiveProfile(normalizedProfileId)
        return waitForSelection(normalizedProfileId)
    }

    private suspend fun waitForSelection(profileId: String): WebMemorySelectorState {
        repeat(12) {
            val snapshot = resolveState()
            if (snapshot.currentProfileId == profileId) {
                return snapshot
            }
            delay(30)
        }
        return resolveState()
    }
}
