package com.wuxianpi.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuxianpi.pi.PiConnectionState
import com.wuxianpi.pi.PiEvent
import com.wuxianpi.pi.PiResponse
import com.wuxianpi.pi.PiServiceConfig
import com.wuxianpi.pi.PiSessionRef
import com.wuxianpi.pi.WuxianPiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class WuxianPiViewModel(
    application: Application,
    private val featureConfig: AiFeatureConfig,
) : AndroidViewModel(application) {
    private val serviceStore = PiServiceStore(application)
    private val recentSessions = RecentSessionStore(application)
    private val http = OkHttpClient()
    private val _credentials = MutableStateFlow(initialCredentials())
    private val _conversation = MutableStateFlow(ConversationState())
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _connection = MutableStateFlow<PiConnectionState>(PiConnectionState.Disconnected)
    private val _modelConfig = MutableStateFlow(ModelConfigState())
    private var client: WuxianPiClient? = null
    private var observerJob: Job? = null
    private var connectJob: Job? = null
    private var session: PiSessionRef? = null

    val credentials: StateFlow<PiServiceCredentials?> = _credentials.asStateFlow()
    val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    val connection: StateFlow<PiConnectionState> = _connection.asStateFlow()
    val modelConfig: StateFlow<ModelConfigState> = _modelConfig.asStateFlow()

    init {
        _credentials.value?.let(::connect)
    }

    fun completePairing(value: PiServiceCredentials) {
        if (featureConfig.runtimeMode != RuntimeMode.EXTERNAL_TERMUX) return
        runCatching { serviceStore.save(value.serviceUrl, value.clientId) }
            .onFailure { _statusMessage.value = it.message ?: "Could not save Pi service pairing" }
            .onSuccess {
                _credentials.value = value
                connect(value)
            }
    }

    fun retryConnection() {
        _credentials.value?.let(::connect)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val sdk = client ?: return
        if (!_modelConfig.value.hasLoaded || !_modelConfig.value.hasUsableDefault) {
            openModelConfig()
            return
        }
        if (trimmed.isEmpty() ||
            _connection.value !is PiConnectionState.Connected ||
            _conversation.value.isAgentRunning ||
            sdk.agentActive.value
        ) return
        viewModelScope.launch {
            runCatching {
                sdk.prompt(trimmed) {
                    // Keep the user turn visible even if the provider later rejects the request.
                    _conversation.update { ConversationReducer.addUser(it, trimmed) }
                }
            }.onFailure(::showInlineClientError)
        }
    }

    fun openModelConfig() {
        _modelConfig.update { it.copy(isOpen = true) }
        if (!_modelConfig.value.hasLoaded) refreshModelConfig()
    }

    fun dismissModelConfig() {
        _modelConfig.update { state ->
            if (state.promptRequired || state.isBusy) state else state.copy(isOpen = false)
        }
    }

    fun selectModelProvider(provider: String) {
        if (_modelConfig.value.isBusy) return
        _modelConfig.update { it.selectProvider(provider) }
    }

    fun selectModel(modelId: String) {
        if (_modelConfig.value.isBusy) return
        _modelConfig.update { it.selectModel(modelId) }
    }

    fun updateModelApiKey(value: String) {
        _modelConfig.update { it.copy(apiKey = value, error = null, message = null) }
    }

    fun refreshModelConfig(reload: Boolean = false) {
        val sdk = client ?: return
        if (_modelConfig.value.isBusy) return
        viewModelScope.launch {
            _modelConfig.update { it.begin(if (reload) ModelConfigPhase.RELOADING else ModelConfigPhase.LOADING) }
            runCatching { if (reload) sdk.reloadModels() else sdk.modelStatus() }
                .onSuccess { status -> _modelConfig.update { it.withStatus(status) } }
                .onFailure { error -> _modelConfig.update { it.fail(error.message ?: "Could not load models") } }
        }
    }

    fun loginModelProvider() {
        val sdk = client ?: return
        val state = _modelConfig.value
        val provider = state.selectedProvider ?: return
        val apiKey = state.apiKey
        if (apiKey.isBlank() || state.isBusy) return
        viewModelScope.launch {
            _modelConfig.update { it.begin(ModelConfigPhase.LOGGING_IN) }
            val login = runCatching { sdk.loginModelProvider(provider, apiKey) }
            if (login.isFailure) {
                val error = login.exceptionOrNull()
                _modelConfig.update { it.fail(error?.message ?: "Provider login failed") }
                return@launch
            }
            // The secret has been accepted by the service; never retain it while refreshing status.
            _modelConfig.update { it.complete("Provider connected", clearSecret = true) }
            runCatching { sdk.modelStatus() }
                .onSuccess { status ->
                    _modelConfig.update { it.withStatus(status).complete("Provider connected", clearSecret = true) }
                }
                .onFailure { error ->
                    _modelConfig.update { it.fail(error.message ?: "Provider connected, but status refresh failed") }
                }
        }
    }

    fun testSelectedModel() {
        val sdk = client ?: return
        val state = _modelConfig.value
        val provider = state.selectedProvider ?: return
        val modelId = state.selectedModelId ?: return
        if (state.isBusy) return
        viewModelScope.launch {
            _modelConfig.update { it.begin(ModelConfigPhase.TESTING) }
            runCatching { sdk.testModel(provider, modelId) }
                .onSuccess { result ->
                    _modelConfig.update { it.complete("Model responded in ${result.latencyMs} ms") }
                }
                .onFailure { error -> _modelConfig.update { it.fail(error.message ?: "Model test failed") } }
        }
    }

    fun setDefaultModel() {
        val sdk = client ?: return
        val state = _modelConfig.value
        val provider = state.selectedProvider ?: return
        val modelId = state.selectedModelId ?: return
        if (state.isBusy || _conversation.value.isAgentRunning) return
        viewModelScope.launch {
            _modelConfig.update { it.begin(ModelConfigPhase.SAVING_DEFAULT) }
            runCatching {
                sdk.setDefaultModel(provider, modelId)
                sdk.modelStatus()
            }.onSuccess { status ->
                _modelConfig.update { it.withStatus(status).complete("Default model updated", clearSecret = true) }
            }.onFailure { error -> _modelConfig.update { it.fail(error.message ?: "Could not set default model") } }
        }
    }

    fun logoutModelProvider() {
        val sdk = client ?: return
        val state = _modelConfig.value
        val provider = state.selectedProvider ?: return
        if (state.isBusy || _conversation.value.isAgentRunning) return
        viewModelScope.launch {
            _modelConfig.update { it.begin(ModelConfigPhase.LOGGING_OUT) }
            runCatching {
                sdk.logoutModelProvider(provider)
                sdk.modelStatus()
            }.onSuccess { status ->
                _modelConfig.update { it.withStatus(status, openIfMissing = true).complete("Provider logged out", true) }
            }.onFailure { error -> _modelConfig.update { it.fail(error.message ?: "Provider logout failed") } }
        }
    }

    fun abort() {
        viewModelScope.launch {
            runCatching { client?.abort() }.onFailure(::showInlineClientError)
        }
    }

    fun respondToExtension(value: Any?) {
        val request = _conversation.value.extensionRequest ?: return
        _conversation.value = ConversationReducer.clearExtension(_conversation.value)
        viewModelScope.launch {
            runCatching { client?.extensionUiResponse(request.requestId, value, request.sessionId) }
                .onFailure(::showInlineClientError)
        }
    }

    fun newConversation() {
        val sdk = client ?: return
        val serviceUrl = _credentials.value?.serviceUrl ?: return
        viewModelScope.launch {
            runCatching { sdk.newSession() }
                .onSuccess { opened ->
                    session = opened
                    recentSessions.save(serviceUrl, opened)
                    _conversation.value = ConversationState()
                }
                .onFailure(::showInlineClientError)
        }
    }

    fun openConversation(sessionPath: String) {
        val sdk = client ?: return
        val serviceUrl = _credentials.value?.serviceUrl ?: return
        viewModelScope.launch {
            runCatching {
                val opened = sdk.openSession(sessionPath)
                opened to sdk.history(opened.sessionPath)
            }.onSuccess { (opened, history) ->
                session = opened
                recentSessions.save(serviceUrl, opened)
                _conversation.update { ConversationReducer.restore(history, it) }
            }.onFailure(::showInlineClientError)
        }
    }

    fun closeConversation() {
        val sdk = client ?: return
        val serviceUrl = _credentials.value?.serviceUrl ?: return
        viewModelScope.launch {
            runCatching { sdk.closeSession() }
                .onSuccess { response ->
                    if (response.success) {
                        recentSessions.clear(serviceUrl)
                        session = null
                        _conversation.value = ConversationState()
                    }
                }
                .onFailure(::showInlineClientError)
        }
    }

    fun forgetRuntime() {
        _credentials.value?.serviceUrl?.let(recentSessions::clear)
        connectJob?.cancel()
        connectJob = null
        observerJob?.cancel()
        observerJob = null
        client?.close()
        client = null
        session = null
        if (featureConfig.runtimeMode == RuntimeMode.EXTERNAL_TERMUX) {
            serviceStore.clear()
            _credentials.value = null
        } else {
            _credentials.value = initialCredentials()
        }
        _conversation.value = ConversationState()
        _connection.value = PiConnectionState.Disconnected
        _statusMessage.value = null
        _modelConfig.value = ModelConfigState()
    }

    private fun connect(credentials: PiServiceCredentials) {
        connectJob?.cancel()
        connectJob = null
        observerJob?.cancel()
        observerJob = null
        client?.close()
        val sdk = WuxianPiClient(
            config = PiServiceConfig(credentials.serviceUrl.toHttpUrl()),
            http = http,
            parentScope = viewModelScope,
        )
        client = sdk
        observerJob = viewModelScope.launch {
            launch {
                sdk.events.collect { event ->
                    _conversation.update { ConversationReducer.reduce(it, event) }
                    if (event is PiEvent.SessionRecovered) {
                        val recoveredId = event.sessionId
                        val recoveredPath = event.sessionPath
                        if (recoveredId != null && recoveredPath != null) {
                            session = PiSessionRef(recoveredId, recoveredPath).also {
                                recentSessions.save(credentials.serviceUrl, it)
                            }
                        }
                    }
                }
            }
            launch {
                sdk.connection.collect { state ->
                    _connection.value = state
                    _statusMessage.value = (state as? PiConnectionState.Failed)?.message
                }
            }
            launch {
                sdk.agentActive.collect { active ->
                    _conversation.update { ConversationReducer.setAgentRunning(it, active) }
                }
            }
        }
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                sdk.connect()
                val previous = session ?: recentSessions.load(credentials.serviceUrl)
                val opened = previous?.sessionPath?.let { path ->
                    runCatching { sdk.openSession(path) }.getOrElse {
                        recentSessions.clear(credentials.serviceUrl)
                        sdk.createSession()
                    }
                } ?: sdk.createSession()
                session = opened
                recentSessions.save(credentials.serviceUrl, opened)
                sdk.history(opened.sessionPath).also { history ->
                    _conversation.update { ConversationReducer.restore(history, it) }
                }
                runCatching { sdk.modelStatus() }
                    .onSuccess { status ->
                        _modelConfig.update { it.withStatus(status, openIfMissing = true) }
                    }
                    .onFailure { error ->
                        _modelConfig.update {
                            it.copy(isOpen = true, hasLoaded = true)
                                .fail(error.message ?: "Could not load model configuration")
                        }
                    }
                _statusMessage.value = null
            }.onFailure { error ->
                _statusMessage.value = error.message ?: "WuxianPi service is unavailable"
            }
        }
    }

    private fun showInlineClientError(error: Throwable) {
        val response = PiResponse(
            id = "android-local",
            command = null,
            success = false,
            data = null,
            error = error.message ?: "Pi command failed",
            rawJson = "{}",
        )
        _conversation.update {
            ConversationReducer.reduce(
                it,
                PiEvent.CommandError(
                    response = response,
                    sessionId = session?.sessionId,
                    sessionPath = session?.sessionPath,
                    rawJson = response.rawJson,
                ),
            )
        }
    }

    override fun onCleared() {
        connectJob?.cancel()
        observerJob?.cancel()
        client?.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        super.onCleared()
    }

    private fun initialCredentials(): PiServiceCredentials? = when (featureConfig.runtimeMode) {
        RuntimeMode.EXTERNAL_TERMUX -> serviceStore.load()
        RuntimeMode.BUNDLED_TERMUX -> featureConfig.bundledRuntime?.let {
            PiServiceCredentials(it.serviceUrl, it.clientId)
        }
    }

    companion object {
        fun factory(application: Application, config: AiFeatureConfig): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WuxianPiViewModel::class.java))
                    return WuxianPiViewModel(application, config) as T
                }
            }
    }
}
