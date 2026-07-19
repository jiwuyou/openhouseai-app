package com.wuxianpi.pi

import org.json.JSONArray
import org.json.JSONObject

data class PiProviderStatus(
    val id: String,
    val name: String,
    val authenticated: Boolean,
    val authType: String? = null,
    val authSource: String? = null,
    val authLabel: String? = null,
)

data class PiAvailableModel(
    val provider: String,
    val id: String,
    val name: String,
    val available: Boolean,
    val reasoning: Boolean,
    val input: List<String>,
    val contextWindow: Long?,
    val maxTokens: Long?,
)

data class PiModelRef(val provider: String, val modelId: String)

data class PiModelStatus(
    val providers: List<PiProviderStatus>,
    val models: List<PiAvailableModel>,
    val defaultModel: PiModelRef?,
    val availabilityError: String?,
) {
    val hasUsableDefault: Boolean
        get() {
            val selected = defaultModel ?: return false
            val providerReady = providers.firstOrNull { it.id == selected.provider }?.authenticated == true
            val modelReady = models.any {
                it.provider == selected.provider && it.id == selected.modelId && it.available
            }
            return providerReady && modelReady
        }

    companion object {
        fun from(value: Any?): PiModelStatus {
            val json = value as? JSONObject
                ?: throw IllegalArgumentException("Pi service returned invalid model status")
            return PiModelStatus(
                providers = json.optJSONArray("providers").objects().mapNotNull(::parseProvider),
                models = json.optJSONArray("models").objects().mapNotNull(::parseModel),
                defaultModel = json.optJSONObject("defaultModel")?.let(::parseModelRef),
                availabilityError = json.optString("availabilityError").takeIf(String::isNotBlank),
            )
        }
    }
}

data class PiModelLoginResult(
    val provider: String,
    val authenticated: Boolean,
    val authSource: String?,
    val authLabel: String?,
) {
    companion object {
        fun from(value: Any?): PiModelLoginResult {
            val json = value as? JSONObject ?: throw IllegalArgumentException("Invalid login result")
            return PiModelLoginResult(
                provider = json.getString("provider"),
                authenticated = json.optBoolean("authenticated", false),
                authSource = json.optString("authSource").takeIf(String::isNotBlank),
                authLabel = json.optString("authLabel").takeIf(String::isNotBlank),
            )
        }
    }
}

data class PiModelTestResult(
    val ok: Boolean,
    val provider: String,
    val modelId: String,
    val latencyMs: Long,
    val status: String?,
    val text: String,
) {
    companion object {
        fun from(value: Any?): PiModelTestResult {
            val json = value as? JSONObject ?: throw IllegalArgumentException("Invalid model test result")
            return PiModelTestResult(
                ok = json.optBoolean("ok", false),
                provider = json.getString("provider"),
                modelId = json.getString("modelId"),
                latencyMs = json.optLong("latencyMs", 0),
                status = json.optString("status").takeIf(String::isNotBlank),
                text = json.optString("text"),
            )
        }
    }
}

data class PiSetDefaultResult(
    val provider: String,
    val modelId: String,
    val appliedSessionIds: List<String>,
) {
    companion object {
        fun from(value: Any?): PiSetDefaultResult {
            val json = value as? JSONObject ?: throw IllegalArgumentException("Invalid default model result")
            return PiSetDefaultResult(
                provider = json.getString("provider"),
                modelId = json.getString("modelId"),
                appliedSessionIds = json.optJSONArray("appliedSessionIds").strings(),
            )
        }
    }
}

private fun parseProvider(json: JSONObject): PiProviderStatus? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    return PiProviderStatus(
        id = id,
        name = json.optString("name").ifBlank { id },
        authenticated = json.optBoolean("authenticated", false),
        authType = json.optString("authType").takeIf(String::isNotBlank),
        authSource = json.optString("authSource").takeIf(String::isNotBlank),
        authLabel = json.optString("authLabel").takeIf(String::isNotBlank),
    )
}

private fun parseModel(json: JSONObject): PiAvailableModel? {
    val provider = json.optString("provider")
    val id = json.optString("id")
    if (provider.isBlank() || id.isBlank()) return null
    return PiAvailableModel(
        provider = provider,
        id = id,
        name = json.optString("name").ifBlank { id },
        available = json.optBoolean("available", false),
        reasoning = json.optBoolean("reasoning", false),
        input = when (val input = json.opt("input")) {
            is JSONArray -> input.strings()
            is String -> listOf(input)
            else -> emptyList()
        },
        contextWindow = json.optLongOrNull("contextWindow"),
        maxTokens = json.optLongOrNull("maxTokens"),
    )
}

private fun parseModelRef(json: JSONObject): PiModelRef? {
    val provider = json.optString("provider")
    val modelId = json.optString("modelId")
    return if (provider.isBlank() || modelId.isBlank()) null else PiModelRef(provider, modelId)
}

private fun JSONArray?.objects(): List<JSONObject> = buildList {
    val source = this@objects ?: return@buildList
    for (index in 0 until source.length()) source.optJSONObject(index)?.let(::add)
}

private fun JSONArray?.strings(): List<String> = buildList {
    val source = this@strings ?: return@buildList
    for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else runCatching { getLong(key) }.getOrNull()
