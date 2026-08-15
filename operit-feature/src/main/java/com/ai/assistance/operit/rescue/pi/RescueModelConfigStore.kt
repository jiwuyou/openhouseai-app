package com.ai.assistance.operit.rescue.pi

import android.content.Context
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.model.usesAndroidLocalModelEngine
import com.ai.assistance.operit.data.model.usesPiRuntime
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigStorageScope
import com.ai.assistance.operit.data.preferences.ApiPreferences
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

enum class RescueModelConfigurationIssue {
    EMPTY_REGISTRY,
    MISSING_CONFIGURATION,
    PI_RUNTIME_NOT_SUPPORTED,
    LOCAL_ENGINE_NOT_SUPPORTED,
    MISSING_API_ENDPOINT,
    MISSING_MODEL,
}

class RescueModelConfigurationException(
    val issue: RescueModelConfigurationIssue,
    message: String,
) : IllegalStateException(message)

data class ResolvedRescueModelConfig(
    val selection: RescueModelSelection,
    val config: ModelConfigData,
) {
    val selectedModelName: String =
        getModelByIndex(config.modelName, selection.modelIndex)

    val selectedConfig: ModelConfigData = config.copy(modelName = selectedModelName)

    fun requireRunnable(): ResolvedRescueModelConfig {
        if (config.usesPiRuntime()) {
            throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.PI_RUNTIME_NOT_SUPPORTED,
                "Rescue model must use an Android-owned direct configuration",
            )
        }
        if (config.usesAndroidLocalModelEngine()) {
            throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.LOCAL_ENGINE_NOT_SUPPORTED,
                "Rescue model does not use Android local model engines",
            )
        }
        if (config.apiEndpoint.isBlank()) {
            throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.MISSING_API_ENDPOINT,
                "Rescue model API endpoint must not be blank",
            )
        }
        if (selectedModelName.isBlank()) {
            throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.MISSING_MODEL,
                "Rescue model name must not be blank",
            )
        }
        return this
    }

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

internal fun ModelConfigData.withRescueDeepSeekApiKey(apiKey: String): ModelConfigData {
    val normalizedKey = apiKey.trim()
    require(normalizedKey.isNotEmpty()) { "DeepSeek API key must not be blank" }
    val alreadyDeepSeek =
        apiProviderType == ApiProviderType.DEEPSEEK ||
            apiProviderTypeId.equals(ApiProviderType.DEEPSEEK.name, ignoreCase = true)
    return copy(
        apiKey = normalizedKey,
        apiEndpoint = if (alreadyDeepSeek) {
            apiEndpoint.ifBlank { ApiPreferences.DEFAULT_API_ENDPOINT }
        } else {
            ApiPreferences.DEFAULT_API_ENDPOINT
        },
        modelName = if (alreadyDeepSeek) {
            modelName.ifBlank { ApiPreferences.DEFAULT_MODEL_NAME }
        } else {
            ApiPreferences.DEFAULT_MODEL_NAME
        },
        apiProviderType = ApiProviderType.DEEPSEEK,
        apiProviderTypeId = ApiProviderType.DEEPSEEK.name,
        piModelBinding = null,
        legacyCloudBackup = null,
        useMultipleApiKeys = false,
        apiKeyPool = emptyList(),
        currentKeyIndex = 0,
    )
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
        if (configIds.isEmpty()) {
            throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.EMPTY_REGISTRY,
                "Rescue model registry is empty",
            )
        }

        val storedId = preferences.getString(KEY_ACTIVE_CONFIG_ID, null)
        val configId =
            storedId?.takeIf { it in configIds }
                ?: configIds.firstOrNull { it == ModelConfigManager.DEFAULT_CONFIG_ID }
                ?: configIds.first()
        val config = modelConfigManager.getModelConfig(configId)
            ?: throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.MISSING_CONFIGURATION,
                "Unknown Rescue model configuration: $configId",
            )
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

    /** Saves a user-provided DeepSeek key into the active Android-private Rescue config. */
    suspend fun saveDeepSeekApiKey(apiKey: String): ModelConfigData = activeConfigMutex.withLock {
        val normalizedKey = apiKey.trim()
        require(normalizedKey.isNotEmpty()) { "DeepSeek API key must not be blank" }

        val selection = getActiveSelectionLocked()
        val current = modelConfigManager.getModelConfig(selection.configId)
            ?: throw RescueModelConfigurationException(
                RescueModelConfigurationIssue.MISSING_CONFIGURATION,
                "Configure a model before starting Rescue AI",
            )
        val updated = current.withRescueDeepSeekApiKey(normalizedKey)
        validateDirectConfig(updated)
        modelConfigManager.saveModelConfig(updated)
        updated
    }

    suspend fun loadActiveRegistryConfig(): ResolvedRescueModelConfig {
        val selection = getActiveSelection()
        val config =
            modelConfigManager.getModelConfig(selection.configId)
                ?: throw RescueModelConfigurationException(
                    RescueModelConfigurationIssue.MISSING_CONFIGURATION,
                    "Configure a model before starting Rescue AI",
                )
        return ResolvedRescueModelConfig(selection, config)
    }

    suspend fun loadResolved(): ResolvedRescueModelConfig =
        loadActiveRegistryConfig().requireRunnable()

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
        ResolvedRescueModelConfig(
            selection = RescueModelSelection(config.id, 0),
            config = config,
        ).requireRunnable()
    }

    companion object {
        private const val PREFERENCES_NAME = "wuxianpi_rescue_model_registry"
        private const val KEY_ACTIVE_CONFIG_ID = "active_config_id"
        private const val KEY_ACTIVE_MODEL_INDEX = "active_model_index"
    }
}
