package com.wuxianpi.ai

import com.wuxianpi.pi.PiAvailableModel
import com.wuxianpi.pi.PiModelRef
import com.wuxianpi.pi.PiModelStatus
import com.wuxianpi.pi.PiProviderStatus

enum class ModelConfigPhase {
    IDLE,
    LOADING,
    LOGGING_IN,
    TESTING,
    SAVING_DEFAULT,
    LOGGING_OUT,
    RELOADING,
    CONFIGURED,
    ERROR,
}

data class ModelConfigState(
    val isOpen: Boolean = false,
    val phase: ModelConfigPhase = ModelConfigPhase.IDLE,
    val providers: List<PiProviderStatus> = emptyList(),
    val models: List<PiAvailableModel> = emptyList(),
    val defaultModel: PiModelRef? = null,
    val selectedProvider: String? = null,
    val selectedModelId: String? = null,
    val apiKey: String = "",
    val availabilityError: String? = null,
    val message: String? = null,
    val error: String? = null,
    val hasLoaded: Boolean = false,
) {
    val selectedProviderStatus: PiProviderStatus?
        get() = providers.firstOrNull { it.id == selectedProvider }

    val selectedModels: List<PiAvailableModel>
        get() = models.filter { it.provider == selectedProvider }

    val selectedModel: PiAvailableModel?
        get() = selectedModels.firstOrNull { it.id == selectedModelId }

    val hasUsableDefault: Boolean
        get() = PiModelStatus(providers, models, defaultModel, availabilityError).hasUsableDefault

    val promptRequired: Boolean get() = hasLoaded && !hasUsableDefault
    val isBusy: Boolean get() = phase in BUSY_PHASES

    fun withStatus(status: PiModelStatus, openIfMissing: Boolean = false): ModelConfigState {
        val preferredProvider = selectedProvider
            ?.takeIf { selected -> status.providers.any { it.id == selected } }
            ?: status.defaultModel?.provider
            ?: status.providers.firstOrNull()?.id
        val providerModels = status.models.filter { it.provider == preferredProvider }
        val preferredModel = selectedModelId
            ?.takeIf { selected -> providerModels.any { it.id == selected } }
            ?: status.defaultModel?.takeIf { it.provider == preferredProvider }?.modelId
            ?: providerModels.firstOrNull { it.available }?.id
            ?: providerModels.firstOrNull()?.id
        val usable = status.hasUsableDefault
        return copy(
            isOpen = isOpen || (openIfMissing && !usable),
            phase = if (usable) ModelConfigPhase.CONFIGURED else ModelConfigPhase.IDLE,
            providers = status.providers,
            models = status.models,
            defaultModel = status.defaultModel,
            selectedProvider = preferredProvider,
            selectedModelId = preferredModel,
            availabilityError = status.availabilityError,
            message = null,
            error = null,
            hasLoaded = true,
        )
    }

    fun selectProvider(provider: String): ModelConfigState {
        val candidates = models.filter { it.provider == provider }
        return copy(
            selectedProvider = provider,
            selectedModelId = candidates.firstOrNull { it.available }?.id ?: candidates.firstOrNull()?.id,
            apiKey = "",
            message = null,
            error = null,
        )
    }

    fun selectModel(modelId: String): ModelConfigState = copy(
        selectedModelId = modelId,
        message = null,
        error = null,
    )

    fun begin(phase: ModelConfigPhase): ModelConfigState = copy(phase = phase, message = null, error = null)

    fun fail(message: String): ModelConfigState = copy(phase = ModelConfigPhase.ERROR, error = message)

    fun complete(message: String, clearSecret: Boolean = false): ModelConfigState = copy(
        phase = ModelConfigPhase.CONFIGURED,
        apiKey = if (clearSecret) "" else apiKey,
        message = message,
        error = null,
    )

    companion object {
        private val BUSY_PHASES = setOf(
            ModelConfigPhase.LOADING,
            ModelConfigPhase.LOGGING_IN,
            ModelConfigPhase.TESTING,
            ModelConfigPhase.SAVING_DEFAULT,
            ModelConfigPhase.LOGGING_OUT,
            ModelConfigPhase.RELOADING,
        )
    }
}
