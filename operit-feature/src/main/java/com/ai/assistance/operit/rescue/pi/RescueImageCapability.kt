package com.ai.assistance.operit.rescue.pi

import android.content.Context
import com.ai.assistance.operit.data.model.ModelOption
import com.wuxianpi.pi.PiAvailableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Capability observed from a provider's model metadata. */
enum class RescueImageCapability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

internal fun rescueImageCapabilityForInput(input: List<String>): RescueImageCapability {
    val modalities = input.map { it.trim().lowercase() }.filter(String::isNotEmpty)
    return when {
        modalities.contains("image") -> RescueImageCapability.SUPPORTED
        modalities.isNotEmpty() -> RescueImageCapability.UNSUPPORTED
        else -> RescueImageCapability.UNKNOWN
    }
}

/**
 * Small Android-private cache for provider-declared model input modalities.
 * An empty modality list is deliberately treated as unknown; we never infer vision from a model
 * name.
 */
class RescueImageCapabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun rememberModels(configId: String, providerId: String, models: List<ModelOption>) {
        val editor = preferences.edit()
        models.forEach { model ->
            val capability = rescueImageCapabilityForInput(model.input)
            editor.putString(key(configId, providerId, model.id), capability.name)
        }
        editor.apply()
    }

    fun rememberPiModels(configId: String, models: List<PiAvailableModel>) {
        val editor = preferences.edit()
        models.forEach { model ->
            editor.putString(
                key(configId, model.provider, model.id),
                rescueImageCapabilityForInput(model.input).name,
            )
        }
        editor.apply()
    }

    fun resolve(configId: String, providerId: String, modelId: String): RescueImageCapability {
        val stored = preferences.getString(key(configId, providerId, modelId), null)
        return stored?.let { value ->
            runCatching { RescueImageCapability.valueOf(value) }.getOrNull()
        } ?: RescueImageCapability.UNKNOWN
    }

    private fun key(configId: String, providerId: String, modelId: String): String =
        "${configId.trim()}|${providerId.trim().lowercase()}|${modelId.trim()}"

    companion object {
        private const val PREFERENCES_NAME = "wuxianpi_rescue_image_capabilities"
    }
}

/** Resolves the active Rescue model without making a network request. */
suspend fun resolveRescueImageCapability(context: Context): RescueImageCapability =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolved = RescueModelConfigStore(context).loadResolved()
            RescueImageCapabilityStore(context).resolve(
                configId = resolved.selection.configId,
                providerId = resolved.selectedConfig.apiProviderTypeId,
                modelId = resolved.selectedModelName,
            )
        }.getOrDefault(RescueImageCapability.UNKNOWN)
    }
