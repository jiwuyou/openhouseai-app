package com.ai.assistance.operit.pi

import com.wuxianpi.pi.PiAvailableModel
import com.wuxianpi.pi.PiConnectionState
import com.wuxianpi.pi.PiModelStatus
import com.wuxianpi.pi.PiModelTestResult
import com.wuxianpi.pi.PiServiceConfig
import com.wuxianpi.pi.WuxianPiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Typed model-settings bridge. No Operit provider request class is used by these actions. */
class PiModelSettingsAdapter private constructor() {
    private val client =
        WuxianPiClient(PiServiceConfig("http://127.0.0.1:8765/".toHttpUrl()))
    private val connectionMutex = Mutex()

    companion object {
        val instance: PiModelSettingsAdapter by lazy { PiModelSettingsAdapter() }
    }

    suspend fun status(reload: Boolean = false): PiModelStatus {
        ensureConnected()
        return if (reload) client.reloadModels() else client.modelStatus()
    }

    suspend fun models(operitProviderId: String, reload: Boolean = false): List<PiAvailableModel> {
        val status = status(reload)
        val provider = resolveNodeProviderId(operitProviderId, status)
        return status.models.filter { it.provider == provider && it.available }
    }

    suspend fun defaultModelDisplay(): Pair<String, String> {
        val status = status()
        val selected = status.defaultModel
            ?: throw IllegalStateException(status.availabilityError ?: "Pi 尚未设置默认模型")
        val model = status.models.firstOrNull {
            it.provider == selected.provider && it.id == selected.modelId
        }
        return selected.provider to (model?.name ?: selected.modelId)
    }

    suspend fun applySettings(
        operitProviderId: String,
        apiKey: String?,
        modelId: String,
    ) {
        var status = status()
        val provider = resolveNodeProviderId(operitProviderId, status)
        val providerStatus = status.providers.first { it.id == provider }
        val normalizedKey = apiKey?.trim().orEmpty()
        if (normalizedKey.isNotEmpty()) {
            client.loginModelProvider(provider, normalizedKey)
            status = client.reloadModels()
        } else if (!providerStatus.authenticated) {
            throw IllegalStateException("Pi provider ${providerStatus.name} 尚未登录")
        }
        val selectedModel = modelId.substringBefore(',').trim()
        require(selectedModel.isNotEmpty()) { "请选择 Pi 模型" }
        check(status.models.any { it.provider == provider && it.id == selectedModel && it.available }) {
            "Pi provider ${providerStatus.name} 中没有可用模型 $selectedModel"
        }
        client.setDefaultModel(provider, selectedModel)
    }

    suspend fun test(
        operitProviderId: String,
        modelId: String,
        apiKey: String?,
    ): PiModelTestResult {
        var status = status()
        val provider = resolveNodeProviderId(operitProviderId, status)
        val normalizedKey = apiKey?.trim().orEmpty()
        if (normalizedKey.isNotEmpty()) {
            client.loginModelProvider(provider, normalizedKey)
            status = client.reloadModels()
        }
        val selectedModel = modelId.substringBefore(',').trim()
        check(status.models.any { it.provider == provider && it.id == selectedModel }) {
            "Pi 中未找到模型 $selectedModel"
        }
        return client.testModel(provider, selectedModel)
    }

    suspend fun logout(operitProviderId: String) {
        val status = status()
        client.logoutModelProvider(resolveNodeProviderId(operitProviderId, status))
    }

    private suspend fun ensureConnected() {
        connectionMutex.withLock {
            if (client.connection.value !is PiConnectionState.Connected) client.connect()
        }
    }
}

internal fun resolveNodeProviderId(operitProviderId: String, status: PiModelStatus): String {
    val candidates = providerCandidates(operitProviderId)
    return status.providers.firstOrNull { provider ->
        normalizeProviderId(provider.id) in candidates
    }?.id ?: throw IllegalArgumentException(
        "Pi 当前没有提供商 $operitProviderId；可用提供商：${status.providers.joinToString { it.id }}"
    )
}

internal fun providerCandidates(operitProviderId: String): Set<String> {
    val normalized = normalizeProviderId(operitProviderId)
    val aliases = when (normalized) {
        "openairesponses", "openairesponsesgeneric", "openaigeneric", "openailocal" -> setOf("openai")
        "anthropicgeneric" -> setOf("anthropic")
        "geminigeneric" -> setOf("google", "gemini")
        "fourrouter" -> setOf("4router", "fourrouter")
        "llamacpp", "mnn" -> emptySet()
        else -> emptySet()
    }
    return aliases + normalized
}

private fun normalizeProviderId(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit)
