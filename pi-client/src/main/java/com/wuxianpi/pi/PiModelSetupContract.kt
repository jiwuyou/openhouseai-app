package com.wuxianpi.pi

import org.json.JSONArray
import org.json.JSONObject

@JvmInline
value class PiModelApi(val wireValue: String) {
    init {
        require(wireValue.isNotBlank()) { "Model API must not be blank" }
    }

    val isSupported: Boolean
        get() = wireValue in SUPPORTED_VALUES

    override fun toString(): String = wireValue

    companion object {
        val AUTO = PiModelApi("auto")
        val ANTHROPIC_MESSAGES = PiModelApi("anthropic-messages")
        val OPENAI_RESPONSES = PiModelApi("openai-responses")
        val OPENAI_COMPLETIONS = PiModelApi("openai-completions")
        val GOOGLE_GENERATIVE_AI = PiModelApi("google-generative-ai")

        val supported: List<PiModelApi> = listOf(
            AUTO,
            ANTHROPIC_MESSAGES,
            OPENAI_RESPONSES,
            OPENAI_COMPLETIONS,
            GOOGLE_GENERATIVE_AI,
        )

        private val SUPPORTED_VALUES = supported.mapTo(mutableSetOf()) { it.wireValue }

        fun from(value: String?): PiModelApi? = value?.trim()?.takeIf(String::isNotEmpty)?.let(::PiModelApi)
    }
}

class PiModelApiKey private constructor(private val value: String) {
    internal fun requestValue(): String = value

    override fun equals(other: Any?): Boolean = other is PiModelApiKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "[REDACTED]"

    companion object {
        fun of(value: String): PiModelApiKey {
            require(value.isNotBlank()) { "API key must not be blank" }
            return PiModelApiKey(value)
        }
    }
}

data class PiModelProviderPreset(
    val id: String,
    val aliases: List<String>,
    val label: String,
    val api: PiModelApi,
    val baseUrl: String,
    val recommendedModel: String?,
    val recommendedModels: List<String>,
    val requiresApiKey: Boolean,
    val category: String?,
    val endpointCandidates: List<String>,
    val sourceTags: List<String>,
    val compat: Map<String, Any?>,
)

class PiModelHeaders private constructor(
    private val backingValues: Map<String, String>,
) : Map<String, String> by backingValues {
    internal fun requestValues(): Map<String, String> = backingValues

    override fun equals(other: Any?): Boolean = other is PiModelHeaders && backingValues == other.backingValues

    override fun hashCode(): Int = backingValues.hashCode()

    override fun toString(): String = backingValues.entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
        "$name=${if (isSensitiveModelHeaderName(name)) REDACTED_HEADER_VALUE else value}"
    }

    companion object {
        private const val REDACTED_HEADER_VALUE = "[REDACTED]"
        private val EMPTY = PiModelHeaders(emptyMap())

        fun of(values: Map<String, String>): PiModelHeaders = if (values.isEmpty()) {
            EMPTY
        } else {
            PiModelHeaders(values.toMap())
        }

        fun empty(): PiModelHeaders = EMPTY
    }
}

data class PiConfiguredModel(
    val id: String,
    val name: String? = null,
    val api: PiModelApi? = null,
    val reasoning: Boolean? = null,
    val input: List<String> = emptyList(),
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val additionalProperties: Map<String, Any?> = emptyMap(),
) {
    init {
        require(!containsApiKeyProperty(additionalProperties)) {
            "Configured model properties must not contain API keys"
        }
    }
}

data class PiModelProviderConfig(
    val baseUrl: String? = null,
    val api: PiModelApi? = null,
    val headers: PiModelHeaders = PiModelHeaders.empty(),
    val models: List<PiConfiguredModel> = emptyList(),
    val additionalProperties: Map<String, Any?> = emptyMap(),
) {
    init {
        require(!containsApiKeyProperty(additionalProperties)) {
            "Provider config properties must not contain API keys"
        }
    }
}

data class PiModelSetupConfig(
    val providers: Map<String, PiModelProviderConfig>,
)

data class PiModelSetupState(
    val revision: String,
    val presets: List<PiModelProviderPreset>,
    val config: PiModelSetupConfig,
    val providers: List<PiProviderStatus>,
    val models: List<PiAvailableModel>,
    val defaultModel: PiModelRef?,
    val availabilityError: String? = null,
)

data class PiModelProviderDraft(
    val providerId: String,
    val presetId: String? = null,
    val baseUrl: String? = null,
    val api: PiModelApi? = null,
    val headers: PiModelHeaders = PiModelHeaders.empty(),
    val apiKey: PiModelApiKey? = null,
    val models: List<PiConfiguredModel> = emptyList(),
    val timeoutMs: Long? = null,
) {
    init {
        require(providerId.isNotBlank()) { "Provider ID must not be blank" }
        require(api == null || api.isSupported) { "Unsupported model API: $api" }
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive" }
    }
}

data class PiDiscoveredModel(
    val id: String,
    val name: String? = null,
    val ownedBy: String? = null,
    val sources: List<PiModelApi> = emptyList(),
)

data class PiModelDiscoveryModeResult(
    val api: PiModelApi,
    val label: String,
    val ok: Boolean,
    val modelCount: Int,
    val models: List<PiDiscoveredModel>,
    val candidates: List<String>,
    val latencyMs: Long?,
    val status: Int?,
    val error: String?,
    val hint: String?,
    val endpoint: String? = null,
)

data class PiModelDraftResult(
    val ok: Boolean,
    val models: List<PiDiscoveredModel>,
    val recommendedModel: String?,
    val resolvedApi: PiModelApi?,
    val modeResults: List<PiModelDiscoveryModeResult>,
    val candidates: List<String>,
    val provider: String?,
    val modelId: String?,
    val latencyMs: Long?,
    val status: Int?,
    val responseText: String?,
    val message: String?,
    val hint: String?,
)

sealed interface PiModelCredentialMutation {
    data object Keep : PiModelCredentialMutation
    data class Set(val apiKey: PiModelApiKey) : PiModelCredentialMutation
    data object Remove : PiModelCredentialMutation
}

sealed interface PiModelProviderChange {
    val providerId: String
    val credential: PiModelCredentialMutation

    data class Upsert(
        override val providerId: String,
        val provider: PiModelProviderConfig,
        override val credential: PiModelCredentialMutation = PiModelCredentialMutation.Keep,
    ) : PiModelProviderChange

    data class Remove(
        override val providerId: String,
        override val credential: PiModelCredentialMutation = PiModelCredentialMutation.Remove,
    ) : PiModelProviderChange
}

data class PiModelApplyRequest(
    val revision: String,
    val config: PiModelSetupConfig,
    val changes: List<PiModelProviderChange>,
    val defaultModel: PiModelRef? = null,
    val setGlobalDefault: Boolean = false,
) {
    init {
        require(revision.isNotBlank()) { "Revision must not be blank" }
        require(defaultModel != null || !setGlobalDefault) {
            "defaultModel is required when setGlobalDefault is true"
        }
        require(changes.all { it.providerId.isNotBlank() }) { "Provider change IDs must not be blank" }
    }
}

internal object PiModelSetupJson {
    fun parseSetup(json: JSONObject): PiModelSetupState {
        val configJson = json.optJSONObject("config") ?: JSONObject()
        val providerConfigs = configJson.optJSONObject("providers") ?: JSONObject()
        return PiModelSetupState(
            revision = json.optText("revision").orEmpty(),
            presets = json.optJSONArray("presets").objects().mapNotNull(::parsePreset),
            config = PiModelSetupConfig(
                providers = providerConfigs.keys().asSequence().associateWith { providerId ->
                    parseProviderConfig(providerConfigs.optJSONObject(providerId) ?: JSONObject())
                },
            ),
            providers = json.optJSONArray("providers").objects().mapNotNull(::parseProviderStatus),
            models = json.optJSONArray("models").objects().mapNotNull(::parseAvailableModel),
            defaultModel = json.optJSONObject("defaultModel")?.let(::parseModelRef),
            availabilityError = json.optText("availabilityError"),
        )
    }

    fun parseDraftResult(json: JSONObject): PiModelDraftResult {
        val modeResults = json.optJSONArray("modeResults").objects().mapNotNull(::parseModeResult)
        val merged = linkedMapOf<String, PiDiscoveredModel>()
        json.optJSONArray("models").values().mapNotNull(::parseDiscoveredModel).forEach { model ->
            merged[model.id] = model
        }
        modeResults.forEach { mode ->
            mode.models.forEach { model ->
                val previous = merged[model.id]
                merged[model.id] = PiDiscoveredModel(
                    id = model.id,
                    name = previous?.name ?: model.name,
                    ownedBy = previous?.ownedBy ?: model.ownedBy,
                    sources = ((previous?.sources ?: emptyList()) + model.sources + mode.api).distinct(),
                )
            }
        }
        return PiModelDraftResult(
            ok = json.optBoolean("ok", true),
            models = merged.values.toList(),
            recommendedModel = json.optText("recommendedModel"),
            resolvedApi = PiModelApi.from(json.optText("resolvedApi") ?: json.optText("api")),
            modeResults = modeResults,
            candidates = json.optJSONArray("candidates").strings(),
            provider = json.optText("provider"),
            modelId = json.optText("modelId"),
            latencyMs = json.optLongOrNull("latencyMs"),
            status = json.optIntOrNull("status"),
            responseText = json.optText("responseText") ?: json.optText("text"),
            message = json.optText("message"),
            hint = json.optText("hint"),
        )
    }

    fun draftRequest(draft: PiModelProviderDraft, modelId: String? = null): JSONObject = JSONObject()
        .put("draft", JSONObject().apply {
            put("providerId", draft.providerId)
            draft.presetId?.let { put("presetId", it) }
            draft.baseUrl?.let { put("baseUrl", it) }
            draft.api?.let { put("api", it.wireValue) }
            if (draft.headers.isNotEmpty()) put("headers", JSONObject(draft.headers.requestValues()))
            draft.apiKey?.let { put("apiKey", it.requestValue()) }
            if (draft.models.isNotEmpty()) put("models", JSONArray(draft.models.map(::configuredModelJson)))
            modelId?.let { put("model", JSONObject().put("id", it)) }
        })
        .apply { draft.timeoutMs?.let { put("timeoutMs", it) } }

    fun applyRequest(request: PiModelApplyRequest): JSONObject = JSONObject()
        .put("revision", request.revision)
        .put("config", setupConfigJson(request.config))
        .put("changes", JSONArray(request.changes.map(::providerChangeJson)))
        .put("setGlobalDefault", request.setGlobalDefault)
        .apply {
            request.defaultModel?.let {
                put("defaultModel", JSONObject().put("provider", it.provider).put("modelId", it.modelId))
            }
        }

    fun parseErrorModeResults(details: Any?): List<PiModelDiscoveryModeResult> {
        val objectDetails = details as? Map<*, *> ?: return emptyList()
        val modes = objectDetails["modeResults"] as? List<*> ?: return emptyList()
        return modes.mapNotNull { value ->
            (value as? Map<*, *>)?.let(::mapToJsonObject)?.let(::parseModeResult)
        }
    }

    fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonValue(value.opt(key)) }
        is JSONArray -> (0 until value.length()).map { index -> jsonValue(value.opt(index)) }
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    fun sensitiveValues(request: JSONObject?): Set<String> {
        if (request == null) return emptySet()
        val values = linkedSetOf<String>()

        fun addSensitive(value: Any?) {
            val text = (value as? String)?.trim().orEmpty()
            if (text.isEmpty()) return
            values += text
            val authValue = AUTH_SCHEME.matchEntire(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (authValue.length >= MIN_SECRET_FRAGMENT_LENGTH) values += authValue
        }

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().asSequence().forEach { key ->
                    val child = value.opt(key)
                    when {
                        isApiKeyName(key) -> addSensitive(child)
                        key.equals("headers", ignoreCase = true) && child is JSONObject ->
                            child.keys().asSequence().forEach { headerName ->
                                if (isSensitiveModelHeaderName(headerName)) addSensitive(child.opt(headerName))
                            }
                    }
                    visit(child)
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }

        visit(request)
        return values
    }

    fun redactSensitive(value: Any?, sensitiveValues: Set<String>): Any? {
        if (sensitiveValues.isEmpty()) return value
        return when (value) {
            is String -> redactSensitiveText(value, sensitiveValues)
            is Map<*, *> -> value.entries.associate { (key, item) ->
                key.toString() to redactSensitive(item, sensitiveValues)
            }
            is Iterable<*> -> value.map { item -> redactSensitive(item, sensitiveValues) }
            is Array<*> -> value.map { item -> redactSensitive(item, sensitiveValues) }
            else -> value
        }
    }

    fun redactSensitiveText(value: String, sensitiveValues: Set<String>): String =
        sensitiveValues
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)
            .fold(value) { redacted, secret -> redacted.replace(secret, REDACTED_VALUE) }

    private fun parsePreset(json: JSONObject): PiModelProviderPreset? {
        val id = json.optText("id") ?: return null
        val api = PiModelApi.from(json.optText("api") ?: json.optText("apiType")) ?: return null
        return PiModelProviderPreset(
            id = id,
            aliases = json.optJSONArray("aliases").strings(),
            label = json.optText("label") ?: json.optText("name") ?: id,
            api = api,
            baseUrl = json.optText("baseUrl").orEmpty(),
            recommendedModel = json.optText("recommendedModel"),
            recommendedModels = json.optJSONArray("recommendedModels").strings(),
            requiresApiKey = json.optBoolean("requiresApiKey", true),
            category = json.optText("category"),
            endpointCandidates = json.optJSONArray("endpointCandidates").strings(),
            sourceTags = json.optJSONArray("sourceTags").strings(),
            compat = (jsonValue(json.optJSONObject("compat")) as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to it.value }
                .orEmpty(),
        )
    }

    private fun parseProviderStatus(json: JSONObject): PiProviderStatus? {
        val id = json.optText("id") ?: json.optText("provider") ?: return null
        return PiProviderStatus(
            id = id,
            name = json.optText("name") ?: json.optText("label") ?: id,
            authenticated = json.optBoolean("authenticated", false),
            authType = json.optText("authType"),
            authSource = json.optText("authSource"),
            authLabel = json.optText("authLabel"),
        )
    }

    private fun parseAvailableModel(json: JSONObject): PiAvailableModel? {
        val provider = json.optText("provider") ?: return null
        val id = json.optText("id") ?: return null
        return PiAvailableModel(
            provider = provider,
            id = id,
            name = json.optText("name") ?: id,
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
        val provider = json.optText("provider") ?: return null
        val modelId = json.optText("modelId") ?: json.optText("id") ?: return null
        return PiModelRef(provider, modelId)
    }

    private fun parseProviderConfig(json: JSONObject): PiModelProviderConfig = PiModelProviderConfig(
        baseUrl = json.optText("baseUrl"),
        api = PiModelApi.from(json.optText("api") ?: json.optText("apiType")),
        headers = PiModelHeaders.of(json.optJSONObject("headers")?.let(::stringMap).orEmpty()),
        models = json.optJSONArray("models").objects().mapNotNull(::parseConfiguredModel),
        additionalProperties = sanitizedProperties(json, PROVIDER_CONFIG_KEYS),
    )

    private fun parseConfiguredModel(json: JSONObject): PiConfiguredModel? {
        val id = json.optText("id") ?: return null
        return PiConfiguredModel(
            id = id,
            name = json.optText("name"),
            api = PiModelApi.from(json.optText("api")),
            reasoning = json.optBooleanOrNull("reasoning"),
            input = json.optJSONArray("input").strings(),
            contextWindow = json.optLongOrNull("contextWindow"),
            maxTokens = json.optLongOrNull("maxTokens"),
            additionalProperties = sanitizedProperties(json, CONFIGURED_MODEL_KEYS),
        )
    }

    private fun parseModeResult(json: JSONObject): PiModelDiscoveryModeResult? {
        val api = PiModelApi.from(json.optText("api") ?: json.optText("mode") ?: json.optText("protocol"))
            ?: return null
        val models = json.optJSONArray("models").values().mapNotNull(::parseDiscoveredModel)
        return PiModelDiscoveryModeResult(
            api = api,
            label = json.optText("label") ?: api.wireValue,
            ok = json.optBoolean("ok", false),
            modelCount = json.optIntOrNull("modelCount") ?: models.size,
            models = models.map { model ->
                if (model.sources.isEmpty()) model.copy(sources = listOf(api)) else model
            },
            candidates = json.optJSONArray("candidates").strings(),
            latencyMs = json.optLongOrNull("latencyMs"),
            status = json.optIntOrNull("status"),
            error = json.optText("error") ?: json.optJSONObject("error")?.optText("message"),
            hint = json.optText("hint"),
            endpoint = json.optText("endpoint"),
        )
    }

    private fun parseDiscoveredModel(value: Any?): PiDiscoveredModel? {
        if (value is String) return value.trim().takeIf(String::isNotEmpty)?.let(::PiDiscoveredModel)
        val json = value as? JSONObject ?: return null
        val id = json.optText("id") ?: json.optText("model") ?: return null
        val sources = (json.optJSONArray("sources") ?: json.optJSONArray("sourceApis"))
            .strings()
            .mapNotNull(PiModelApi::from)
            .distinct()
        return PiDiscoveredModel(
            id = id,
            name = json.optText("name"),
            ownedBy = json.optText("ownedBy") ?: json.optText("owned_by"),
            sources = sources,
        )
    }

    private fun setupConfigJson(config: PiModelSetupConfig): JSONObject = JSONObject().put(
        "providers",
        JSONObject().apply {
            config.providers.forEach { (providerId, provider) -> put(providerId, providerConfigJson(provider)) }
        },
    )

    private fun providerChangeJson(change: PiModelProviderChange): JSONObject = JSONObject()
        .put("providerId", change.providerId)
        .put("action", if (change is PiModelProviderChange.Upsert) "upsert" else "remove")
        .put("credential", credentialJson(change.credential))
        .apply {
            if (change is PiModelProviderChange.Upsert) put("provider", providerConfigJson(change.provider))
        }

    private fun credentialJson(credential: PiModelCredentialMutation): JSONObject = when (credential) {
        PiModelCredentialMutation.Keep -> JSONObject().put("action", "keep")
        is PiModelCredentialMutation.Set -> JSONObject()
            .put("action", "set")
            .put("apiKey", credential.apiKey.requestValue())
        PiModelCredentialMutation.Remove -> JSONObject().put("action", "remove")
    }

    private fun providerConfigJson(config: PiModelProviderConfig): JSONObject = JSONObject().apply {
        putSanitizedProperties(config.additionalProperties, PROVIDER_CONFIG_KEYS)
        config.baseUrl?.let { put("baseUrl", it) }
        config.api?.let { put("api", it.wireValue) }
        if (config.headers.isNotEmpty()) put("headers", JSONObject(config.headers.requestValues()))
        if (config.models.isNotEmpty()) put("models", JSONArray(config.models.map(::configuredModelJson)))
    }

    private fun configuredModelJson(model: PiConfiguredModel): JSONObject = JSONObject().apply {
        putSanitizedProperties(model.additionalProperties, CONFIGURED_MODEL_KEYS)
        put("id", model.id)
        model.name?.let { put("name", it) }
        model.api?.let { put("api", it.wireValue) }
        model.reasoning?.let { put("reasoning", it) }
        if (model.input.isNotEmpty()) put("input", JSONArray(model.input))
        model.contextWindow?.let { put("contextWindow", it) }
        model.maxTokens?.let { put("maxTokens", it) }
    }

    private fun JSONObject.putSanitizedProperties(
        properties: Map<String, Any?>,
        reservedKeys: Set<String>,
    ) {
        properties.forEach { (key, value) ->
            if (reservedKeys.none { it.equals(key, ignoreCase = true) } && !isApiKeyName(key)) {
                put(key, kotlinValueToJson(value))
            }
        }
    }

    private fun sanitizedProperties(json: JSONObject, knownKeys: Set<String>): Map<String, Any?> =
        json.keys().asSequence()
            .filterNot { it in knownKeys || isApiKeyName(it) }
            .associateWith { key -> sanitizedJsonValue(json.opt(key)) }

    private fun sanitizedJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence()
            .filterNot(::isApiKeyName)
            .associateWith { key -> sanitizedJsonValue(value.opt(key)) }
        is JSONArray -> (0 until value.length()).map { index -> sanitizedJsonValue(value.opt(index)) }
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    private fun stringMap(json: JSONObject): Map<String, String> = json.keys().asSequence().mapNotNull { key ->
        json.optText(key)?.let { key to it }
    }.toMap()

    private fun mapToJsonObject(value: Map<*, *>): JSONObject = JSONObject().apply {
        value.forEach { (key, item) -> if (key != null) put(key.toString(), kotlinValueToJson(item)) }
    }

    private fun kotlinValueToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> mapToJsonObject(value)
        is Iterable<*> -> JSONArray(value.map(::kotlinValueToJson))
        is Array<*> -> JSONArray(value.map(::kotlinValueToJson))
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    private fun isApiKeyName(key: String): Boolean = key.replace("_", "").replace("-", "")
        .equals("apikey", ignoreCase = true)

    private val PROVIDER_CONFIG_KEYS = setOf("baseUrl", "api", "apiType", "headers", "models")
    private val CONFIGURED_MODEL_KEYS = setOf("id", "name", "api", "reasoning", "input", "contextWindow", "maxTokens")
    private val AUTH_SCHEME = Regex("(?i)^(?:bearer|basic|token)\\s+(.+)$")
    private const val MIN_SECRET_FRAGMENT_LENGTH = 4
    private const val REDACTED_VALUE = "[REDACTED]"
}

private fun isSensitiveModelHeaderName(key: String): Boolean {
    val normalized = key.lowercase().replace("_", "-")
    return normalized in SENSITIVE_MODEL_HEADER_NAMES ||
        normalized.contains("authorization") ||
        normalized.contains("api-key") ||
        normalized.contains("token") ||
        normalized.contains("secret")
}

private val SENSITIVE_MODEL_HEADER_NAMES = setOf(
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "api-key",
    "x-auth-token",
    "cookie",
    "set-cookie",
)

private fun JSONArray?.objects(): List<JSONObject> = buildList {
    val source = this@objects ?: return@buildList
    for (index in 0 until source.length()) source.optJSONObject(index)?.let(::add)
}

private fun JSONArray?.values(): List<Any?> = buildList {
    val source = this@values ?: return@buildList
    for (index in 0 until source.length()) add(source.opt(index))
}

private fun JSONArray?.strings(): List<String> = buildList {
    val source = this@strings ?: return@buildList
    for (index in 0 until source.length()) source.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
}

private fun JSONObject.optText(key: String): String? = optString(key).trim().takeIf(String::isNotEmpty)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else runCatching { getLong(key) }.getOrNull()

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else runCatching { getInt(key) }.getOrNull()

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else runCatching { getBoolean(key) }.getOrNull()

private fun containsApiKeyProperty(value: Any?): Boolean = when (value) {
    is Map<*, *> -> value.any { (key, item) ->
        key is String && key.replace("_", "").replace("-", "").equals("apikey", ignoreCase = true) ||
            containsApiKeyProperty(item)
    }
    is Iterable<*> -> value.any(::containsApiKeyProperty)
    is Array<*> -> value.any(::containsApiKeyProperty)
    else -> false
}
