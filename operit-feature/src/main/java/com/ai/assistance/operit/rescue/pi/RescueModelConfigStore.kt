package com.ai.assistance.operit.rescue.pi

import android.content.Context
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.model.usesAndroidLocalModelEngine
import com.ai.assistance.operit.data.model.usesPiRuntime
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigStorageScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RescueModelSelection(
    val configId: String,
    val modelIndex: Int,
)

data class ResolvedRescueModelConfig(
    val selection: RescueModelSelection,
    val config: ModelConfigData,
) {
    val selectedModelName: String =
        getModelByIndex(config.modelName, selection.modelIndex)

    val selectedConfig: ModelConfigData = config.copy(modelName = selectedModelName)

    fun configWithSelectedModelName(modelName: String): ModelConfigData {
        val models = getModelList(config.modelName).toMutableList()
        val replacement = modelName.trim()
        if (models.isEmpty()) {
            return config.copy(modelName = replacement)
        }
        if (replacement.isNotEmpty()) {
            models[getValidModelIndex(config.modelName, selection.modelIndex)] = replacement
        }
        return config.copy(modelName = models.joinToString(","))
    }
}

/** Active model selection for the Android-private Rescue model registry. */
class RescueModelConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val modelConfigManager =
        ModelConfigManager(appContext, ModelConfigStorageScope.RESCUE)
    private val activeConfigMutex = Mutex()

    suspend fun getActiveSelection(): RescueModelSelection = activeConfigMutex.withLock {
        getActiveSelectionLocked()
    }

    private suspend fun getActiveSelectionLocked(): RescueModelSelection {
        modelConfigManager.initializeIfNeeded()
        val configIds = modelConfigManager.configListFlow.first()
        check(configIds.isNotEmpty()) { "Rescue model registry is empty" }

        val storedId = preferences.getString(KEY_ACTIVE_CONFIG_ID, null)
        val configId =
            storedId?.takeIf { it in configIds }
                ?: configIds.firstOrNull { it == ModelConfigManager.DEFAULT_CONFIG_ID }
                ?: configIds.first()
        val config = requireNotNull(modelConfigManager.getModelConfig(configId)) {
            "Unknown Rescue model configuration: $configId"
        }
        val storedModelIndex = preferences.getInt(KEY_ACTIVE_MODEL_INDEX, 0)
        val modelIndex = getValidModelIndex(config.modelName, storedModelIndex)
        if (storedId != configId || storedModelIndex != modelIndex) {
            persistSelection(configId, modelIndex)
        }
        return RescueModelSelection(configId, modelIndex)
    }

    suspend fun getActiveConfigId(): String = getActiveSelection().configId

    fun activeSelectionFlow(): Flow<RescueModelSelection> =
        callbackFlow {
            val listener =
                android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == KEY_ACTIVE_CONFIG_ID || key == KEY_ACTIVE_MODEL_INDEX) {
                        trySend(Unit)
                    }
                }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.map { getActiveSelection() }.distinctUntilChanged()

    suspend fun setActiveSelection(configId: String, modelIndex: Int) =
        activeConfigMutex.withLock {
        modelConfigManager.initializeIfNeeded()
        require(configId in modelConfigManager.configListFlow.first()) {
            "Unknown Rescue model configuration: $configId"
        }
        val config = requireNotNull(modelConfigManager.getModelConfig(configId)) {
            "Unknown Rescue model configuration: $configId"
        }
        persistSelection(configId, getValidModelIndex(config.modelName, modelIndex))
    }

    suspend fun setActiveConfigId(configId: String) = activeConfigMutex.withLock {
        val currentSelection = getActiveSelectionLocked()
        modelConfigManager.initializeIfNeeded()
        require(configId in modelConfigManager.configListFlow.first()) {
            "Unknown Rescue model configuration: $configId"
        }
        val config = requireNotNull(modelConfigManager.getModelConfig(configId)) {
            "Unknown Rescue model configuration: $configId"
        }
        val modelIndex =
            if (currentSelection.configId == configId) currentSelection.modelIndex else 0
        persistSelection(configId, getValidModelIndex(config.modelName, modelIndex))
    }

    suspend fun save(config: ModelConfigData) {
        validateDirectConfig(config)
        modelConfigManager.saveModelConfig(
            config.copy(piModelBinding = null, legacyCloudBackup = null)
        )
    }

    suspend fun loadActiveRegistryConfig(): ResolvedRescueModelConfig {
        val selection = getActiveSelection()
        val config =
            requireNotNull(modelConfigManager.getModelConfig(selection.configId)) {
                "Configure a model before starting Rescue AI"
            }
        return ResolvedRescueModelConfig(selection, config)
    }

    suspend fun loadResolved(): ResolvedRescueModelConfig {
        return loadActiveRegistryConfig().also {
            validateDirectConfig(it.config)
            require(it.selectedModelName.isNotBlank()) {
                "Rescue model name must not be blank"
            }
        }
    }

    suspend fun load(): ModelConfigData = loadResolved().selectedConfig

    private fun persistSelection(configId: String, modelIndex: Int) {
        check(
            preferences
                .edit()
                .putString(KEY_ACTIVE_CONFIG_ID, configId)
                .putInt(KEY_ACTIVE_MODEL_INDEX, modelIndex)
                .commit()
        ) {
            "Failed to persist active Rescue model selection"
        }
    }

    private fun validateDirectConfig(config: ModelConfigData) {
        require(!config.usesPiRuntime()) {
            "Rescue model must use an Android-owned direct configuration"
        }
        require(!config.usesAndroidLocalModelEngine()) {
            "Rescue model does not use Android local model engines"
        }
        require(config.apiEndpoint.isNotBlank()) { "Rescue model API endpoint must not be blank" }
        require(config.modelName.isNotBlank()) { "Rescue model name must not be blank" }
    }

    companion object {
        private const val PREFERENCES_NAME = "wuxianpi_rescue_model_registry"
        private const val KEY_ACTIVE_CONFIG_ID = "active_config_id"
        private const val KEY_ACTIVE_MODEL_INDEX = "active_model_index"
    }
}
