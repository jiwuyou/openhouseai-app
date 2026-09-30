package com.wuxianpi.openhouse.feature

import android.content.Context

/** Persists only the resumable guide step; the API key is never stored here. */
internal class FirstUseGuideStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadStep(): FirstUseGuideOverlay.Step = runCatching {
        FirstUseGuideOverlay.Step.valueOf(preferences.getString(KEY_STEP, null) ?: "INTRO")
    }.getOrDefault(FirstUseGuideOverlay.Step.INTRO)

    fun saveStep(step: FirstUseGuideOverlay.Step) {
        preferences.edit()
            .putString(KEY_STEP, step.name)
            .putBoolean(KEY_PAUSED, false)
            .apply()
    }

    fun markPaused() {
        preferences.edit().putBoolean(KEY_PAUSED, true).apply()
    }

    companion object {
        private const val PREFERENCES = "openhouse_first_use_guide"
        private const val KEY_STEP = "step"
        private const val KEY_PAUSED = "paused"
    }
}
