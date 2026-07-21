package com.ai.assistance.operit.rescue.pi

import android.content.Context
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
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
        require(config.apiEndpoint.isNotBlank()) { "Rescue model API endpoint must not be blank" }
        require(config.modelName.isNotBlank()) { "Rescue model name must not be blank" }
        preferences.edit().putString(KEY_MODEL_CONFIG, json.encodeToString(config)).commit()
    }

    suspend fun load(): ModelConfigData {
        preferences.getString(KEY_MODEL_CONFIG, null)?.let { encoded ->
            return json.decodeFromString(encoded)
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

    companion object {
        private const val PREFERENCES_NAME = "wuxianpi_rescue_model_config"
        private const val KEY_MODEL_CONFIG = "model_config_json"
    }
}
