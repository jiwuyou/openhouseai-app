package com.ai.assistance.operit.rescue.pi

import android.content.Context
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.model.usesAndroidLocalModelEngine
import com.ai.assistance.operit.data.model.usesPiRuntime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Android-private model configuration used by Rescue Pi even when Termux is unavailable. */
class RescueModelConfigStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val modelConfigManager = ModelConfigManager(context.applicationContext)
    private val functionalConfigManager = FunctionalConfigManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(config: ModelConfigData) {
        validateDirectConfig(config)
        preferences.edit().putString(KEY_MODEL_CONFIG, json.encodeToString(config)).commit()
    }

    fun snapshotIfAbsent(config: ModelConfigData): Boolean {
        validateDirectConfig(config)
        synchronized(preferences) {
            if (preferences.contains(KEY_MODEL_CONFIG)) return false
            check(preferences.edit().putString(KEY_MODEL_CONFIG, json.encodeToString(config)).commit()) {
                "Failed to persist Rescue model snapshot"
            }
            return true
        }
    }

    suspend fun load(): ModelConfigData {
        preferences.getString(KEY_MODEL_CONFIG, null)?.let { encoded ->
            return json.decodeFromString<ModelConfigData>(encoded).also(::validateDirectConfig)
        }

        // The first rescue launch snapshots the Android-local Operit setting. Subsequent launches
        // read only this private copy, so Termux, Node, and a damaged main configuration do not
        // prevent Rescue AI from starting. Explicit sync is exposed separately below.
        return syncFromOperit()
    }

    suspend fun syncFromOperit(): ModelConfigData {
        modelConfigManager.initializeIfNeeded()
        functionalConfigManager.initializeIfNeeded()
        val configId = functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
        val configured = requireNotNull(modelConfigManager.getModelConfig(configId)) {
            "Configure a model before starting Rescue AI"
        }
        save(configured)
        return configured
    }

    private fun validateDirectConfig(config: ModelConfigData) {
        require(!config.usesPiRuntime()) { "Rescue model must use an Android-owned direct configuration" }
        require(!config.usesAndroidLocalModelEngine()) { "Rescue model does not use Android local model engines" }
        require(config.apiEndpoint.isNotBlank()) { "Rescue model API endpoint must not be blank" }
        require(config.modelName.isNotBlank()) { "Rescue model name must not be blank" }
    }

    companion object {
        private const val PREFERENCES_NAME = "wuxianpi_rescue_model_config"
        private const val KEY_MODEL_CONFIG = "model_config_json"
    }
}
