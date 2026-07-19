package com.wuxianpi.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuxianpi.pi.LeaseResult
import com.wuxianpi.pi.PiConnectionState
import com.wuxianpi.pi.PiGatewayConfig
import com.wuxianpi.pi.PiRpcClient
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
    private val tokenStore = GatewayTokenStore(application)
    private val http = OkHttpClient()
    private val _credentials = MutableStateFlow(initialCredentials())
    private val _conversation = MutableStateFlow(ConversationState())
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _connection = MutableStateFlow<PiConnectionState>(PiConnectionState.Disconnected)
    private var client: PiRpcClient? = null
    private var bridge: AndroidBridgeManager? = null
    private var observerJob: Job? = null
    private var connectJob: Job? = null
    private var sessionId = "new"

    val credentials: StateFlow<GatewayCredentials?> = _credentials.asStateFlow()
    val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    val connection: StateFlow<PiConnectionState> = _connection.asStateFlow()

    init {
        _credentials.value?.let(::connect)
    }

    fun completePairing(value: GatewayCredentials) {
        if (featureConfig.runtimeMode != RuntimeMode.EXTERNAL_TERMUX) return
        runCatching { tokenStore.save(value.adminUrl, value.token, value.clientId) }
            .onFailure { _statusMessage.value = it.message ?: "Could not save gateway token" }
            .onSuccess {
                _credentials.value = value
                connect(value)
            }
    }

    fun retryConnection() {
        _credentials.value?.let(::connect)
    }

    fun takeOver() {
        val active = client ?: return
        viewModelScope.launch {
            when (val result = active.takeOver(sessionId)) {
                is LeaseResult.Failure -> _statusMessage.value = result.message
                is LeaseResult.Conflict -> _statusMessage.value = result.message
                is LeaseResult.Acquired -> _statusMessage.value = null
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val rpc = client ?: return
        if (trimmed.isEmpty() ||
            _connection.value !is PiConnectionState.Connected ||
            _conversation.value.isAgentRunning ||
            rpc.agentActive.value
        ) return
        viewModelScope.launch {
            runCatching {
                rpc.prompt(trimmed) {
                    // Append only after the prompt gate accepted the turn and WebSocket queued it.
                    _conversation.update { ConversationReducer.addUser(it, trimmed) }
                }
            }
                .onFailure { error ->
                    _statusMessage.value = error.message ?: "Message could not be sent"
                }
        }
    }

    fun abort() {
        viewModelScope.launch {
            runCatching { client?.abort() }.onFailure { _statusMessage.value = it.message }
        }
    }

    fun respondToExtension(value: Any?) {
        val request = _conversation.value.extensionRequest ?: return
        _conversation.value = ConversationReducer.clearExtension(_conversation.value)
        viewModelScope.launch {
            runCatching { client?.extensionUiResponse(request.requestId, value) }
                .onFailure { _statusMessage.value = it.message }
        }
    }

    fun forgetRuntime() {
        connectJob?.cancel()
        connectJob = null
        observerJob?.cancel()
        observerJob = null
        client?.close()
        client = null
        bridge?.close()
        bridge = null
        if (featureConfig.runtimeMode == RuntimeMode.EXTERNAL_TERMUX) {
            tokenStore.clear()
            _credentials.value = null
        } else {
            _credentials.value = initialCredentials()
        }
        _conversation.value = ConversationState()
        _connection.value = PiConnectionState.Disconnected
    }

    private fun connect(credentials: GatewayCredentials) {
        connectJob?.cancel()
        connectJob = null
        observerJob?.cancel()
        observerJob = null
        client?.close()
        bridge?.close()
        bridge = null
        val rpc = PiRpcClient(
            config = PiGatewayConfig(
                adminBaseUrl = credentials.adminUrl.toHttpUrl(),
                bearerToken = credentials.token,
                clientId = credentials.clientId,
            ),
            http = http,
            parentScope = viewModelScope,
        )
        client = rpc
        observerJob = viewModelScope.launch {
            launch {
                rpc.events.collect { event ->
                    _conversation.update { ConversationReducer.reduce(it, event) }
                }
            }
            launch {
                rpc.connection.collect { state ->
                    _connection.value = state
                    _statusMessage.value = when (state) {
                        is PiConnectionState.Failed -> state.message
                        is PiConnectionState.LeaseConflict -> state.message
                        else -> null
                    }
                }
            }
            launch {
                rpc.agentActive.collect { active ->
                    _conversation.update { ConversationReducer.setAgentRunning(it, active) }
                }
            }
        }
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            val manager = AndroidBridgeManager(getApplication(), http)
            bridge = manager
            if (!manager.startAndRegister(credentials)) {
                _statusMessage.value = "Android tools could not be registered, so Pi was not started"
                return@launch
            }
            when (val result = rpc.openSession(sessionId)) {
                is LeaseResult.Failure -> _statusMessage.value = result.message
                is LeaseResult.Conflict -> _statusMessage.value = result.message
                is LeaseResult.Acquired -> Unit
            }
        }
    }

    override fun onCleared() {
        connectJob?.cancel()
        observerJob?.cancel()
        client?.close()
        bridge?.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        super.onCleared()
    }

    private fun initialCredentials(): GatewayCredentials? = when (featureConfig.runtimeMode) {
        RuntimeMode.EXTERNAL_TERMUX -> tokenStore.load()
        RuntimeMode.BUNDLED_TERMUX -> featureConfig.bundledRuntime?.let {
            GatewayCredentials(it.adminUrl, it.token, it.clientId)
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
