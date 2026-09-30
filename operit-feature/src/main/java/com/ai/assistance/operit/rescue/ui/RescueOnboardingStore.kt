package com.ai.assistance.operit.rescue.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small process-local bridge for the explicit first-install handoff from OpenHouse. */
object RescueOnboardingStore {
    private const val PREFERENCES = "rescue_onboarding"
    private const val KEY_PENDING = "first_install_pending"
    private val pendingState = MutableStateFlow(false)

    val pending: StateFlow<Boolean> = pendingState

    fun initialize(context: Context) {
        pendingState.value = preferences(context).getBoolean(KEY_PENDING, false)
    }

    fun markFirstInstallPending(context: Context) {
        preferences(context).edit().putBoolean(KEY_PENDING, true).apply()
        pendingState.value = true
    }

    fun markFirstInstallStarted(context: Context) {
        preferences(context).edit().putBoolean(KEY_PENDING, false).apply()
        pendingState.value = false
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
