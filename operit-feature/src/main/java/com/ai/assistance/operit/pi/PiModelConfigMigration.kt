package com.ai.assistance.operit.pi

import android.content.Context
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.LegacyCloudModelBackup
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.model.legacyCloudBackupSnapshot
import com.ai.assistance.operit.data.model.usesAndroidLocalModelEngine
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.rescue.pi.RescueModelConfigStore
import com.ai.assistance.operit.util.AppLogger
import com.wuxianpi.pi.PiDiscoveredModel
import com.wuxianpi.pi.PiModelApi
import com.wuxianpi.pi.PiModelProviderPreset
import com.wuxianpi.pi.PiModelSetupState
import java.security.MessageDigest
import org.json.JSONObject

data class PiModelMigrationReport(
    val migratedConfigIds: List<String>,
    val skippedConfigIds: List<String>,
    val failures: Map<String, String>,
    val complete: Boolean,
)

internal interface PiModelMigrationConfigStore {
    suspend fun allConfigs(): List<ModelConfigData>
    suspend fun rescueSourceConfigId(): String? = null
    suspend fun saveBinding(
        configId: String,
        binding: PiModelBinding,
        backup: LegacyCloudModelBackup,
    )
}

internal class AndroidPiModelMigrationConfigStore(
    private val manager: ModelConfigManager,
) : PiModelMigrationConfigStore {
    override suspend fun allConfigs(): List<ModelConfigData> = manager.getAllModelConfigs()

    override suspend fun rescueSourceConfigId(): String {
        val functionalConfigs = FunctionalConfigManager(manager.appContext)
        functionalConfigs.initializeIfNeeded()
        return functionalConfigs.getConfigIdForFunction(FunctionType.CHAT)
    }

    override suspend fun saveBinding(
        configId: String,
        binding: PiModelBinding,
        backup: LegacyCloudModelBackup,
    ) {
        manager.updatePiModelBinding(configId, binding, backup)
    }
}

internal interface PiModelMigrationRescueStore {
    fun snapshotIfAbsent(config: ModelConfigData)
}

internal object NoOpPiModelMigrationRescueStore : PiModelMigrationRescueStore {
    override fun snapshotIfAbsent(config: ModelConfigData) = Unit
}

internal class AndroidPiModelMigrationRescueStore(context: Context) : PiModelMigrationRescueStore {
    private val store = RescueModelConfigStore(context.applicationContext)

    override fun snapshotIfAbsent(config: ModelConfigData) {
        store.snapshotIfAbsent(config)
    }
}

internal interface PiModelMigrationStateStore {
    fun isComplete(): Boolean
    fun isConfigComplete(configId: String): Boolean
    fun markConfigComplete(configId: String)
    fun recordFailure(configId: String, message: String)
    fun markComplete()
}

internal class AndroidPiModelMigrationStateStore(context: Context) : PiModelMigrationStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isComplete(): Boolean = preferences.getBoolean(KEY_COMPLETE, false)

    override fun isConfigComplete(configId: String): Boolean =
        preferences.getBoolean("$KEY_CONFIG_PREFIX$configId", false)

    override fun markConfigComplete(configId: String) {
        preferences.edit()
            .putBoolean("$KEY_CONFIG_PREFIX$configId", true)
            .remove("$KEY_FAILURE_PREFIX$configId")
            .apply()
    }

    override fun recordFailure(configId: String, message: String) {
        preferences.edit().putString("$KEY_FAILURE_PREFIX$configId", message).apply()
    }

    override fun markComplete() {
        preferences.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "pi_model_config_migration_v1"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_CONFIG_PREFIX = "config_complete:"
        private const val KEY_FAILURE_PREFIX = "failure:"
    }
}

/** One-time, retryable migration from legacy Android cloud fields to Pi Runtime ownership. */
class PiModelConfigMigration internal constructor(
    private val repository: PiModelSetupRepository,
    private val configs: PiModelMigrationConfigStore,
    private val state: PiModelMigrationStateStore,
    private val rescueStore: PiModelMigrationRescueStore = NoOpPiModelMigrationRescueStore,
) {
    suspend fun migrateIfNeeded(): PiModelMigrationReport {
        if (state.isComplete()) {
            return PiModelMigrationReport(emptyList(), emptyList(), emptyMap(), complete = true)
        }
        var setup = repository.setup()
        val migrated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = linkedMapOf<String, String>()
        val allConfigs = configs.allConfigs()
        val rescueSourceConfigId = configs.rescueSourceConfigId()
        val forceCustomProviderIds = conflictingPresetConfigIds(allConfigs, setup.presets)
        allConfigs.forEach { config ->
            when {
                config.usesAndroidLocalModelEngine() -> skipped += config.id
                config.piModelBinding != null -> {
                    state.markConfigComplete(config.id)
                    skipped += config.id
                }
                state.isConfigComplete(config.id) -> skipped += config.id
                !config.hasLegacyCloudConfiguration() -> {
                    state.markConfigComplete(config.id)
                    skipped += config.id
                }
                else -> {
                    try {
                        if (config.id == rescueSourceConfigId) {
                            rescueStore.snapshotIfAbsent(config)
                        }
                        val draft = migrationDraft(
                            config = config,
                            setup = setup,
                            forceCustomProvider = config.id in forceCustomProviderIds,
                        )
                        val outcome = repository.apply(setup, draft, setGlobalDefault = false)
                        configs.saveBinding(config.id, outcome.binding, config.legacyCloudBackupSnapshot())
                        state.markConfigComplete(config.id)
                        setup = outcome.setup
                        migrated += config.id
                    } catch (error: Exception) {
                        val message = error.message ?: error.javaClass.simpleName
                        state.recordFailure(config.id, message)
                        failures[config.id] = message
                        runCatching {
                            AppLogger.e(TAG, "Pi model migration failed for ${config.id}", error)
                        }
                    }
                }
            }
        }
        val complete = failures.isEmpty()
        if (complete) state.markComplete()
        return PiModelMigrationReport(migrated, skipped, failures, complete)
    }

    private fun migrationDraft(
        config: ModelConfigData,
        setup: PiModelSetupState,
        forceCustomProvider: Boolean,
    ): PiModelEditorDraft {
        val preset = findPreset(config, setup.presets)
        val providerId = preset?.id?.takeUnless { forceCustomProvider } ?: stableCustomProviderId(config)
        val api = legacyApi(config.apiProviderType)
        val modelIds = config.modelName.split(',').map(String::trim).filter(String::isNotEmpty)
        val selectedModel = modelIds.firstOrNull()
            ?: preset?.recommendedModel
            ?: preset?.recommendedModels?.firstOrNull()
            ?: throw IllegalArgumentException("旧配置 ${config.name} 没有可迁移的模型 ID")
        return PiModelEditorDraft(
            providerId = providerId,
            presetId = preset?.id,
            baseUrl = config.apiEndpoint.ifBlank { preset?.baseUrl.orEmpty() },
            api = api,
            apiKey = config.apiKey.takeIf(::isRealLegacyApiKey).orEmpty(),
            headers = parseHeaders(config.customHeaders),
            modelId = selectedModel,
            discoveredModels = (modelIds.ifEmpty { listOf(selectedModel) }).map { modelId ->
                PiDiscoveredModel(id = modelId, sources = listOf(api))
            },
        )
    }

    companion object {
        private const val TAG = "PiModelMigration"
    }
}

private fun ModelConfigData.hasLegacyCloudConfiguration(): Boolean =
    apiProviderTypeId != ModelConfigManager.PI_RUNTIME_PROVIDER_TYPE_ID &&
        (modelName.isNotBlank() || apiEndpoint.isNotBlank() || isRealLegacyApiKey(apiKey))

private fun findPreset(
    config: ModelConfigData,
    presets: List<PiModelProviderPreset>,
): PiModelProviderPreset? {
    val legacyType = config.apiProviderTypeId.trim()
    presets.firstOrNull { preset ->
        (preset.compat["operitProviderType"] as? String)?.equals(legacyType, ignoreCase = true) == true
    }?.let { return it }
    val endpoint = config.apiEndpoint.trim().trimEnd('/')
    if (endpoint.isNotEmpty()) {
        presets.firstOrNull { it.baseUrl.trim().trimEnd('/').equals(endpoint, ignoreCase = true) }
            ?.let { return it }
    }
    val aliases = legacyProviderAliases(config.apiProviderTypeId)
    return presets.firstOrNull { preset ->
        normalizeProviderToken(preset.id) in aliases ||
            preset.aliases.any { normalizeProviderToken(it) in aliases } ||
            normalizeProviderToken(preset.label) in aliases
    }
}

private fun conflictingPresetConfigIds(
    configs: List<ModelConfigData>,
    presets: List<PiModelProviderPreset>,
): Set<String> = configs
    .asSequence()
    .filter { !it.usesAndroidLocalModelEngine() && it.piModelBinding == null && it.hasLegacyCloudConfiguration() }
    .mapNotNull { config -> findPreset(config, presets)?.id?.let { it to config } }
    .groupBy({ it.first }, { it.second })
    .values
    .filter { group ->
        group.map { config ->
            listOf(
                config.apiEndpoint.trim().trimEnd('/').lowercase(),
                config.apiKey.trim(),
                legacyApi(config.apiProviderType).wireValue,
                config.customHeaders.trim(),
            ).joinToString("\u0000")
        }.distinct().size > 1
    }
    .flatten()
    .mapTo(linkedSetOf()) { it.id }

private fun legacyProviderAliases(providerTypeId: String): Set<String> {
    val normalized = normalizeProviderToken(providerTypeId)
    val canonical = when (normalized) {
        "openaigeneric", "openairesponses", "openairesponsesgeneric", "openailocal" -> "openai"
        "anthropicgeneric" -> "anthropic"
        "geminigeneric" -> "google"
        "fourrouter" -> "4router"
        else -> normalized
    }
    return setOf(normalized, canonical)
}

private fun normalizeProviderToken(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit)

private fun stableCustomProviderId(config: ModelConfigData): String {
    val label = normalizeProviderToken(config.apiProviderTypeId).ifBlank { "custom" }.take(24)
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest("${config.id}\n${config.apiProviderTypeId}\n${config.apiEndpoint}".toByteArray())
        .take(5)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "operit-$label-$fingerprint"
}

internal fun legacyApi(provider: ApiProviderType): PiModelApi = when (provider) {
    ApiProviderType.ANTHROPIC,
    ApiProviderType.ANTHROPIC_GENERIC -> PiModelApi.ANTHROPIC_MESSAGES
    ApiProviderType.OPENAI_RESPONSES,
    ApiProviderType.OPENAI_RESPONSES_GENERIC -> PiModelApi.OPENAI_RESPONSES
    ApiProviderType.GOOGLE,
    ApiProviderType.GEMINI_GENERIC -> PiModelApi.GOOGLE_GENERATIVE_AI
    else -> PiModelApi.OPENAI_COMPLETIONS
}

private fun parseHeaders(json: String): Map<String, String> = runCatching {
    val objectValue = JSONObject(json)
    objectValue.keys().asSequence().associateWith { key -> objectValue.optString(key) }
}.getOrElse { emptyMap() }

private fun isRealLegacyApiKey(value: String): Boolean {
    val normalized = value.trim()
    return normalized.isNotEmpty() &&
        !normalized.equals("YOUR_API_KEY_HERE", ignoreCase = true)
}
