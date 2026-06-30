package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelConfigDefaults
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatContextSettings(
    val configId: String,
    val baseContextLength: Float,
    val maxContextLength: Float,
    val enableMaxContextMode: Boolean,
    val effectiveContextLength: Float,
    val summaryTokenThreshold: Float,
    val enableSummary: Boolean,
    val enableSummaryByMessageCount: Boolean,
    val summaryMessageCountThreshold: Int
)

/** 委托类，负责管理用户偏好配置和API密钥 */
class ApiConfigDelegate(
        private val context: Context,
        private val coroutineScope: CoroutineScope,
        private val onConfigChanged: (EnhancedAIService) -> Unit
) {
    companion object {
        private const val TAG = "ApiConfigDelegate"
    }

    // Preferences
    private val apiPreferences = ApiPreferences.getInstance(context)
    private val modelConfigManager = ModelConfigManager(context)
    private val functionalConfigManager = FunctionalConfigManager(context)
    private val characterCardManager = CharacterCardManager.getInstance(context)
    private val activePromptManager = ActivePromptManager.getInstance(context)

    // State flows
    private val _isConfigured = MutableStateFlow(true) // 默认已配置
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _featureToggles = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val featureToggles: StateFlow<Map<String, Boolean>> = _featureToggles.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(ApiPreferences.DEFAULT_KEEP_SCREEN_ON)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _enableThinkingMode = MutableStateFlow(ApiPreferences.DEFAULT_ENABLE_THINKING_MODE)
    val enableThinkingMode: StateFlow<Boolean> = _enableThinkingMode.asStateFlow()

    private val _thinkingQualityLevel =
            MutableStateFlow(ApiPreferences.DEFAULT_THINKING_QUALITY_LEVEL)
    val thinkingQualityLevel: StateFlow<Int> = _thinkingQualityLevel.asStateFlow()

    private val _enableMemoryAutoUpdate =
            MutableStateFlow(ApiPreferences.DEFAULT_ENABLE_MEMORY_AUTO_UPDATE)
    val enableMemoryAutoUpdate: StateFlow<Boolean> = _enableMemoryAutoUpdate.asStateFlow()

    private val _enableAutoRead =
            MutableStateFlow(ApiPreferences.DEFAULT_ENABLE_AUTO_READ)
    val enableAutoRead: StateFlow<Boolean> = _enableAutoRead.asStateFlow()

    private val _contextLength = MutableStateFlow(ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH)
    val baseContextLength: StateFlow<Float> = _contextLength.asStateFlow()
    private val _maxContextLength =
            MutableStateFlow(ModelConfigDefaults.DEFAULT_MAX_CONTEXT_LENGTH)
    val maxContextLengthSetting: StateFlow<Float> = _maxContextLength.asStateFlow()
    private val _enableMaxContextMode =
            MutableStateFlow(ModelConfigDefaults.DEFAULT_ENABLE_MAX_CONTEXT_MODE)
    val enableMaxContextMode: StateFlow<Boolean> = _enableMaxContextMode.asStateFlow()

    val contextLength: StateFlow<Float> = combine(
        _enableMaxContextMode,
        _contextLength,
        _maxContextLength
    ) { isMaxMode, normalLength, maxLength ->
        if (isMaxMode) maxLength else normalLength
    }.stateIn(
            coroutineScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH
    )

    private val _summaryTokenThreshold =
            MutableStateFlow(ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD)
    val summaryTokenThreshold: StateFlow<Float> = _summaryTokenThreshold.asStateFlow()

    private val _enableSummary = MutableStateFlow(ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY)
    val enableSummary: StateFlow<Boolean> = _enableSummary.asStateFlow()

    private val _enableSummaryByMessageCount =
            MutableStateFlow(ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY_BY_MESSAGE_COUNT)
    val enableSummaryByMessageCount: StateFlow<Boolean> = _enableSummaryByMessageCount.asStateFlow()

    private val _summaryMessageCountThreshold =
            MutableStateFlow(ModelConfigDefaults.DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD)
    val summaryMessageCountThreshold: StateFlow<Int> = _summaryMessageCountThreshold.asStateFlow()

    private val _enableTools = MutableStateFlow(ApiPreferences.DEFAULT_ENABLE_TOOLS)
    val enableTools: StateFlow<Boolean> = _enableTools.asStateFlow()

    private val _toolPromptVisibility = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolPromptVisibility: StateFlow<Map<String, Boolean>> = _toolPromptVisibility.asStateFlow()

    private val _disableStreamOutput = MutableStateFlow(ApiPreferences.DEFAULT_DISABLE_STREAM_OUTPUT)
    val disableStreamOutput: StateFlow<Boolean> = _disableStreamOutput.asStateFlow()

    private val _disableUserPreferenceDescription =
            MutableStateFlow(ApiPreferences.DEFAULT_DISABLE_USER_PREFERENCE_DESCRIPTION)
    val disableUserPreferenceDescription: StateFlow<Boolean> =
            _disableUserPreferenceDescription.asStateFlow()

    // 为了兼容现有代码，添加API密钥状态流
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _apiEndpoint = MutableStateFlow("")
    val apiEndpoint: StateFlow<String> = _apiEndpoint.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _apiProviderType = MutableStateFlow(ApiProviderType.DEEPSEEK)
    val apiProviderType: StateFlow<ApiProviderType> = _apiProviderType.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _activeConfigId =
            MutableStateFlow(FunctionalConfigManager.DEFAULT_CONFIG_ID)
    val activeConfigId: StateFlow<String> = _activeConfigId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val effectiveChatConfigId: StateFlow<String> =
            activePromptManager.activePromptFlow
                .flatMapLatest { prompt ->
                    when (prompt) {
                        is ActivePrompt.CharacterCard ->
                            combine(
                                characterCardManager.getCharacterCardFlow(prompt.id),
                                activeConfigId
                            ) { card, globalConfigId ->
                                val lockedConfigId =
                                    card?.takeIf {
                                        CharacterCardChatModelBindingMode.normalize(
                                            it.chatModelBindingMode
                                        ) == CharacterCardChatModelBindingMode.FIXED_CONFIG
                                    }
                                        ?.chatModelConfigId
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                lockedConfigId ?: globalConfigId
                            }

                        is ActivePrompt.CharacterGroup -> activeConfigId
                    }
                }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    FunctionalConfigManager.DEFAULT_CONFIG_ID
                )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val effectiveChatConfig: StateFlow<ModelConfigData> =
            effectiveChatConfigId
                .flatMapLatest { configId -> modelConfigManager.getModelConfigFlow(configId) }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigData(
                        id = FunctionalConfigManager.DEFAULT_CONFIG_ID,
                        name = FunctionalConfigManager.DEFAULT_CONFIG_ID
                    )
                )

    val effectiveBaseContextLength: StateFlow<Float> =
            effectiveChatConfig
                .map { config -> config.contextLength }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH
                )

    val effectiveMaxContextLengthSetting: StateFlow<Float> =
            effectiveChatConfig
                .map { config -> config.maxContextLength }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_MAX_CONTEXT_LENGTH
                )

    val effectiveEnableMaxContextMode: StateFlow<Boolean> =
            effectiveChatConfig
                .map { config -> config.enableMaxContextMode }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_ENABLE_MAX_CONTEXT_MODE
                )

    val effectiveContextLength: StateFlow<Float> =
            combine(
                effectiveEnableMaxContextMode,
                effectiveBaseContextLength,
                effectiveMaxContextLengthSetting
            ) { isMaxMode, normalLength, maxLength ->
                if (isMaxMode) maxLength else normalLength
            }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH
                )

    val effectiveSummaryTokenThreshold: StateFlow<Float> =
            effectiveChatConfig
                .map { config -> config.summaryTokenThreshold }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD
                )

    val effectiveEnableSummary: StateFlow<Boolean> =
            effectiveChatConfig
                .map { config -> config.enableSummary }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY
                )

    val effectiveEnableSummaryByMessageCount: StateFlow<Boolean> =
            effectiveChatConfig
                .map { config -> config.enableSummaryByMessageCount }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY_BY_MESSAGE_COUNT
                )

    val effectiveSummaryMessageCountThreshold: StateFlow<Int> =
            effectiveChatConfig
                .map { config -> config.summaryMessageCountThreshold }
                .stateIn(
                    coroutineScope,
                    kotlinx.coroutines.flow.SharingStarted.Eagerly,
                    ModelConfigDefaults.DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD
                )

    init {
        coroutineScope.launch {
            try {
                modelConfigManager.initializeIfNeeded()
                functionalConfigManager.initializeIfNeeded()

                functionalConfigManager.functionConfigMappingFlow.collect { mapping ->
                    val chatConfigId =
                            mapping[FunctionType.CHAT] ?: FunctionalConfigManager.DEFAULT_CONFIG_ID
                    _activeConfigId.value = chatConfigId
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.d(TAG, "初始化功能配置映射监听已取消")
            } catch (e: Exception) {
                AppLogger.e(TAG, "初始化功能配置映射时出错", e)
            }
        }

        coroutineScope.launch {
            try {
                modelConfigManager.initializeIfNeeded()

                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                _activeConfigId
                        .flatMapLatest { configId ->
                            modelConfigManager.getModelConfigFlow(configId)
                        }
                        .collect { config ->
                            updateStateFromConfig(config)
                            _isInitialized.value = true
                        }
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.d(TAG, "模型配置收集监听已取消")
                _isInitialized.value = true
            } catch (e: Exception) {
                AppLogger.e(TAG, "收集模型配置时出错", e)
                _isInitialized.value = true
            }
        }

        // 加载用户偏好设置
        initializeSettingsCollection()

        // 异步创建AI服务实例，避免在主线程上执行阻塞操作
        coroutineScope.launch(Dispatchers.IO) {
            AppLogger.d(TAG, "开始在后台线程创建EnhancedAIService")
            val enhancedAiService = EnhancedAIService.getInstance(context)
            AppLogger.d(TAG, "EnhancedAIService创建完成")
            withContext(Dispatchers.Main) {
                onConfigChanged(enhancedAiService)
            }
        }
    }

    private fun updateStateFromConfig(config: ModelConfigData) {
        _apiKey.value = config.apiKey
        _apiEndpoint.value = config.apiEndpoint
        _modelName.value = config.modelName
        _apiProviderType.value = config.apiProviderType
        _contextLength.value = config.contextLength
        _maxContextLength.value = config.maxContextLength
        _enableMaxContextMode.value = config.enableMaxContextMode
        _summaryTokenThreshold.value = config.summaryTokenThreshold
        _enableSummary.value = config.enableSummary
        _enableSummaryByMessageCount.value = config.enableSummaryByMessageCount
        _summaryMessageCountThreshold.value = config.summaryMessageCountThreshold
    }

    private fun buildChatContextSettings(configId: String, config: ModelConfigData): ChatContextSettings {
        val effectiveContextLength =
            if (config.enableMaxContextMode) config.maxContextLength else config.contextLength
        return ChatContextSettings(
            configId = configId,
            baseContextLength = config.contextLength,
            maxContextLength = config.maxContextLength,
            enableMaxContextMode = config.enableMaxContextMode,
            effectiveContextLength = effectiveContextLength,
            summaryTokenThreshold = config.summaryTokenThreshold,
            enableSummary = config.enableSummary,
            enableSummaryByMessageCount = config.enableSummaryByMessageCount,
            summaryMessageCountThreshold = config.summaryMessageCountThreshold
        )
    }

    suspend fun resolveChatContextSettings(configIdOverride: String? = null): ChatContextSettings {
        modelConfigManager.initializeIfNeeded()
        val configId =
            configIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: effectiveChatConfigId.value
        val config = requireNotNull(modelConfigManager.getModelConfig(configId)) {
            "Model config not found: $configId"
        }
        return buildChatContextSettings(configId, config)
    }

    private suspend fun resolveEditableChatConfigId(): String {
        modelConfigManager.initializeIfNeeded()
        return effectiveChatConfigId.value
    }

    private fun initializeSettingsCollection() {
        // Collect feature toggle settings
        coroutineScope.launch {
            apiPreferences.featureTogglesFlow.collect { toggles ->
                _featureToggles.value = toggles
            }
        }

        // Collect thinking mode setting
        coroutineScope.launch {
            apiPreferences.enableThinkingModeFlow.collect { enabled ->
                _enableThinkingMode.value = enabled
            }
        }

        coroutineScope.launch {
            apiPreferences.thinkingQualityLevelFlow.collect { level ->
                _thinkingQualityLevel.value = level
            }
        }

        // Collect memory auto update setting
        coroutineScope.launch {
            apiPreferences.enableMemoryAutoUpdateFlow.collect { enabled ->
                _enableMemoryAutoUpdate.value = enabled
            }
        }

        // Collect auto read setting
        coroutineScope.launch {
            apiPreferences.enableAutoReadFlow.collect { enabled ->
                _enableAutoRead.value = enabled
            }
        }

        // Collect keep screen on setting
        coroutineScope.launch {
            apiPreferences.keepScreenOnFlow.collect { enabled ->
                _keepScreenOn.value = enabled
            }
        }

        // Collect enable tools setting
        coroutineScope.launch {
            apiPreferences.enableToolsFlow.collect { enabled ->
                _enableTools.value = enabled
            }
        }

        // Collect tool prompt visibility setting
        coroutineScope.launch {
            apiPreferences.toolPromptVisibilityFlow.collect { visibility ->
                _toolPromptVisibility.value = visibility
            }
        }

        // Collect disable stream output setting
        coroutineScope.launch {
            apiPreferences.disableStreamOutputFlow.collect { disabled ->
                _disableStreamOutput.value = disabled
            }
        }

        // Collect disable user preference description setting
        coroutineScope.launch {
            apiPreferences.disableUserPreferenceDescriptionFlow.collect { disabled ->
                _disableUserPreferenceDescription.value = disabled
            }
        }

    }

    /**
     * 使用默认配置继续
     * @return 总是返回true，因为无需特定配置
     */
    fun useDefaultConfig(): Boolean {
        // 异步创建服务，避免阻塞
        coroutineScope.launch(Dispatchers.IO) {
            AppLogger.d(TAG, "使用默认配置初始化服务")
            val enhancedAiService = EnhancedAIService.getInstance(context)
            withContext(Dispatchers.Main) {
                // 通知ViewModel配置已更改
                onConfigChanged(enhancedAiService)
            }
        }
        return true
    }

    /** 更新API密钥 */
    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    /** 更新API端点 */
    fun updateApiEndpoint(endpoint: String) {
        _apiEndpoint.value = endpoint
    }

    /** 更新模型名称 */
    fun updateModelName(modelName: String) {
        _modelName.value = modelName
    }

    /** 更新API提供商类型 */
    fun updateApiProviderType(providerType: ApiProviderType) {
        _apiProviderType.value = providerType
    }

    /** 保存API设置 */
    fun saveApiSettings() {
        coroutineScope.launch {
            try {
                val configId = _activeConfigId.value

                // 更新所有API相关配置
                modelConfigManager.updateModelConfig(
                        configId,
                        _apiKey.value,
                        _apiEndpoint.value,
                        _modelName.value,
                        _apiProviderType.value
                )

                AppLogger.d(TAG, "API配置已保存到ModelConfigManager")

                // 在IO线程上创建服务，避免阻塞
                val enhancedAiService = withContext(Dispatchers.IO) {
                    EnhancedAIService.getInstance(context)
                }

                // 通知ViewModel配置已更改
                onConfigChanged(enhancedAiService)

                // 更新已配置状态
                _isConfigured.value = true
            } catch (e: Exception) {
                AppLogger.e(TAG, "保存API密钥失败: ${e.message}", e)
            }
        }
    }

    fun toggleFeature(featureKey: String) {
        coroutineScope.launch {
            val normalizedKey = featureKey.trim()
            if (normalizedKey.isEmpty()) {
                return@launch
            }
            val currentValue =
                _featureToggles.value[normalizedKey] ?: ApiPreferences.DEFAULT_FEATURE_TOGGLE_STATE
            val newValue = !currentValue
            apiPreferences.saveFeatureToggle(normalizedKey, newValue)
            _featureToggles.value = _featureToggles.value + (normalizedKey to newValue)
        }
    }

    /** 切换思考模式 */
    fun toggleThinkingMode() {
        coroutineScope.launch {
            val newValue = !_enableThinkingMode.value
            apiPreferences.updateThinkingSettings(enableThinkingMode = newValue)
        }
    }

    fun updateThinkingQualityLevel(level: Int) {
        coroutineScope.launch {
            val clampedLevel = level.coerceIn(1, 4)
            apiPreferences.saveThinkingQualityLevel(clampedLevel)
            _thinkingQualityLevel.value = clampedLevel
        }
    }

    /** 切换记忆自动更新 */
    fun toggleMemoryAutoUpdate() {
        coroutineScope.launch {
            val newValue = !_enableMemoryAutoUpdate.value
            apiPreferences.saveEnableMemoryAutoUpdate(newValue)
            _enableMemoryAutoUpdate.value = newValue
        }
    }

    /** 切换自动朗读 */
    fun toggleAutoRead() {
        coroutineScope.launch {
            val newValue = !_enableAutoRead.value
            apiPreferences.saveEnableAutoRead(newValue)
            _enableAutoRead.value = newValue
        }
    }

    /** 切换禁用流式输出 */
    fun toggleDisableStreamOutput() {
        coroutineScope.launch {
            val newValue = !_disableStreamOutput.value
            apiPreferences.saveDisableStreamOutput(newValue)
            _disableStreamOutput.value = newValue
        }
    }

    /** 切换禁用用户偏好描述 */
    fun toggleDisableUserPreferenceDescription() {
        coroutineScope.launch {
            val newValue = !_disableUserPreferenceDescription.value
            apiPreferences.saveDisableUserPreferenceDescription(newValue)
            _disableUserPreferenceDescription.value = newValue
        }
    }

    /** 更新上下文长度 */
    fun updateContextLength(length: Float) {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            modelConfigManager.updateContextSettings(
                    configId = configId,
                    contextLength = length,
                    maxContextLength = current.maxContextLength,
                    enableMaxContextMode = current.enableMaxContextMode
            )
        }
    }
    fun updateSummaryTokenThreshold(threshold: Float) {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            modelConfigManager.updateSummarySettings(
                    configId = configId,
                    enableSummary = current.enableSummary,
                    summaryTokenThreshold = threshold,
                    enableSummaryByMessageCount = current.enableSummaryByMessageCount,
                    summaryMessageCountThreshold = current.summaryMessageCountThreshold
            )
        }
    }

    fun updateMaxContextLength(length: Float) {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            modelConfigManager.updateContextSettings(
                    configId = configId,
                    contextLength = current.contextLength,
                    maxContextLength = length,
                    enableMaxContextMode = current.enableMaxContextMode
            )
        }
    }

    fun toggleEnableMaxContextMode() {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            val newValue = !current.enableMaxContextMode
            modelConfigManager.updateContextSettings(
                    configId = configId,
                    contextLength = current.contextLength,
                    maxContextLength = current.maxContextLength,
                    enableMaxContextMode = newValue
            )
        }
    }
    /** 切换启用总结功能 */
    fun toggleEnableSummary() {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            val newValue = !current.enableSummary
            modelConfigManager.updateSummarySettings(
                    configId = configId,
                    enableSummary = newValue,
                    summaryTokenThreshold = current.summaryTokenThreshold,
                    enableSummaryByMessageCount = current.enableSummaryByMessageCount,
                    summaryMessageCountThreshold = current.summaryMessageCountThreshold
            )
        }
    }

    /** 切换按消息数量启用总结 */
    fun toggleEnableSummaryByMessageCount() {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            val newValue = !current.enableSummaryByMessageCount
            modelConfigManager.updateSummarySettings(
                    configId = configId,
                    enableSummary = current.enableSummary,
                    summaryTokenThreshold = current.summaryTokenThreshold,
                    enableSummaryByMessageCount = newValue,
                    summaryMessageCountThreshold = current.summaryMessageCountThreshold
            )
        }
    }

    /** 更新总结消息数量阈值 */
    fun updateSummaryMessageCountThreshold(threshold: Int) {
        coroutineScope.launch {
            val configId = resolveEditableChatConfigId()
            val current = modelConfigManager.getModelConfig(configId) ?: return@launch
            modelConfigManager.updateSummarySettings(
                    configId = configId,
                    enableSummary = current.enableSummary,
                    summaryTokenThreshold = current.summaryTokenThreshold,
                    enableSummaryByMessageCount = current.enableSummaryByMessageCount,
                    summaryMessageCountThreshold = threshold
            )
        }
    }

    /** 切换工具启用/禁用 */
    fun toggleTools() {
        coroutineScope.launch {
            val newValue = !_enableTools.value
            apiPreferences.saveEnableTools(newValue)
            _enableTools.value = newValue
        }
    }

    fun saveToolPromptVisibility(toolName: String, isVisible: Boolean) {
        coroutineScope.launch {
            apiPreferences.saveToolPromptVisibility(toolName, isVisible)
            _toolPromptVisibility.value = _toolPromptVisibility.value + (toolName to isVisible)
        }
    }

    fun saveToolPromptVisibilityMap(visibilityMap: Map<String, Boolean>) {
        coroutineScope.launch {
            apiPreferences.saveToolPromptVisibilityMap(visibilityMap)
            _toolPromptVisibility.value = visibilityMap
        }
    }
}
