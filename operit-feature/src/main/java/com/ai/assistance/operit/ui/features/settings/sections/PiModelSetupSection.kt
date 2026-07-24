package com.ai.assistance.operit.ui.features.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.pi.PiModelEditorDraft
import com.ai.assistance.operit.pi.PiModelRevisionConflictException
import com.ai.assistance.operit.pi.PiModelSettingsAdapter
import com.ai.assistance.operit.util.AppLogger
import com.wuxianpi.pi.PiDiscoveredModel
import com.wuxianpi.pi.PiModelApi
import com.wuxianpi.pi.PiModelDraftResult
import com.wuxianpi.pi.PiModelSetupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private val PI_MODEL_API_OPTIONS = listOf(
    PiModelApi.AUTO to "Auto（多协议探测）",
    PiModelApi.ANTHROPIC_MESSAGES to "Claude / Anthropic",
    PiModelApi.OPENAI_RESPONSES to "GPT / Responses",
    PiModelApi.OPENAI_COMPLETIONS to "OpenAI / Completions",
    PiModelApi.GOOGLE_GENERATIVE_AI to "Gemini",
)

@Composable
internal fun PiModelSetupSection(
    config: ModelConfigData,
    configManager: ModelConfigManager,
    showNotification: (String) -> Unit,
) {
    val adapter = remember { PiModelSettingsAdapter.instance }
    val scope = rememberCoroutineScope()
    var setup by remember(config.id) { mutableStateOf<PiModelSetupState?>(null) }
    var loadingSetup by remember(config.id) { mutableStateOf(true) }
    var busyAction by remember(config.id) { mutableStateOf<String?>(null) }
    var errorText by remember(config.id) { mutableStateOf<String?>(null) }
    var providerId by remember(config.id) { mutableStateOf("") }
    var customProvider by remember(config.id) { mutableStateOf(false) }
    var baseUrl by remember(config.id) { mutableStateOf("") }
    var apiSelection by remember(config.id) { mutableStateOf(PiModelApi.AUTO) }
    var selectedModelSource by remember(config.id) { mutableStateOf<PiModelApi?>(null) }
    var apiKey by remember(config.id) { mutableStateOf("") }
    var modelId by remember(config.id) { mutableStateOf("") }
    var headersJson by remember(config.id) { mutableStateOf("{}") }
    var advanced by remember(config.id) { mutableStateOf(false) }
    var setGlobalDefault by remember(config.id) { mutableStateOf(false) }
    var discovery by remember(config.id) { mutableStateOf<PiModelDraftResult?>(null) }
    var providerMenuExpanded by remember(config.id) { mutableStateOf(false) }
    var apiMenuExpanded by remember(config.id) { mutableStateOf(false) }
    var modelDialogVisible by remember(config.id) { mutableStateOf(false) }

    fun hydrate(next: PiModelSetupState, binding: PiModelBinding?) {
        setup = next
        val selectedProvider = binding?.provider
            ?: next.defaultModel?.provider
            ?: next.config.providers.keys.firstOrNull()
            ?: next.presets.firstOrNull()?.id
            .orEmpty()
        providerId = selectedProvider
        customProvider = selectedProvider.isNotEmpty() && next.presets.none { it.id == selectedProvider }
        val provider = next.config.providers[selectedProvider]
        val preset = next.presets.firstOrNull { it.id == selectedProvider }
        baseUrl = provider?.baseUrl ?: preset?.baseUrl.orEmpty()
        apiSelection = provider?.api ?: preset?.api ?: PiModelApi.AUTO
        modelId = binding?.modelId
            ?: next.defaultModel?.takeIf { it.provider == selectedProvider }?.modelId
            ?: provider?.models?.firstOrNull()?.id
            ?: preset?.recommendedModel
            ?: preset?.recommendedModels?.firstOrNull()
            .orEmpty()
        headersJson = JSONObject(provider?.headers.orEmpty()).toString()
        apiKey = ""
        discovery = null
        selectedModelSource = null
    }

    suspend fun refresh(showMessage: Boolean) {
        loadingSetup = true
        errorText = null
        try {
            hydrate(adapter.setup(), config.piModelBinding)
            if (showMessage) showNotification("已刷新 Pi 模型配置")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w("PiModelSetupSection", "Unable to refresh Pi model setup", error)
            errorText = error.message ?: "无法读取 Pi 模型配置"
        } finally {
            loadingSetup = false
        }
    }

    fun buildDraft(): PiModelEditorDraft {
        val currentSetup = requireNotNull(setup) { "Pi 模型配置尚未加载" }
        val normalizedProvider = providerId.trim()
        require(normalizedProvider.isNotEmpty()) { "请选择或填写 Provider ID" }
        val preset = currentSetup.presets.firstOrNull { it.id == normalizedProvider }
        val status = currentSetup.providers.firstOrNull { it.id == normalizedProvider }
        if (preset?.requiresApiKey == true && status?.authenticated != true) {
            require(apiKey.isNotBlank()) { "该 Provider 需要 API Key" }
        }
        return PiModelEditorDraft(
            providerId = normalizedProvider,
            presetId = preset?.id,
            baseUrl = baseUrl,
            api = apiSelection,
            apiKey = apiKey,
            headers = parsePiHeaders(headersJson),
            modelId = modelId,
            discoveredModels = discovery?.models.orEmpty(),
        )
    }

    LaunchedEffect(config.id, config.piModelBinding) {
        refresh(showMessage = false)
    }

    val currentSetup = setup
    val providerOptions = currentSetup?.let { state ->
        (state.presets.map { it.id } + state.config.providers.keys + state.providers.map { it.id })
            .distinct()
    }.orEmpty()
    val selectedProviderStatus = currentSetup?.providers?.firstOrNull { it.id == providerId }
    val selectedPreset = currentSetup?.presets?.firstOrNull { it.id == providerId }
    val selectableModels = remember(discovery, currentSetup, providerId) {
        val discovered = discovery?.models.orEmpty()
        if (discovered.isNotEmpty()) discovered
        else currentSetup?.models.orEmpty()
            .filter { it.provider == providerId }
            .map { PiDiscoveredModel(id = it.id, name = it.name) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader(icon = Icons.Default.Api, title = "Pi 模型配置")

        if (loadingSetup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在读取 Runtime 模型配置", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        errorText?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column {
            OutlinedTextField(
                value = selectedPreset?.label ?: providerId,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = {
                    IconButton(onClick = { providerMenuExpanded = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "选择 Provider")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            DropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false },
            ) {
                providerOptions.forEach { option ->
                    val label = currentSetup?.presets?.firstOrNull { it.id == option }?.label
                        ?: currentSetup?.providers?.firstOrNull { it.id == option }?.name
                        ?: option
                    DropdownMenuItem(
                        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            val provider = currentSetup?.config?.providers?.get(option)
                            val preset = currentSetup?.presets?.firstOrNull { it.id == option }
                            providerId = option
                            customProvider = preset == null
                            baseUrl = provider?.baseUrl ?: preset?.baseUrl.orEmpty()
                            apiSelection = provider?.api ?: preset?.api ?: PiModelApi.AUTO
                            headersJson = JSONObject(provider?.headers.orEmpty()).toString()
                            modelId = provider?.models?.firstOrNull()?.id
                                ?: preset?.recommendedModel
                                ?: preset?.recommendedModels?.firstOrNull()
                                .orEmpty()
                            discovery = null
                            selectedModelSource = null
                            apiKey = ""
                            providerMenuExpanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("自定义 Provider") },
                    onClick = {
                        customProvider = true
                        providerId = ""
                        baseUrl = ""
                        apiSelection = PiModelApi.AUTO
                        modelId = ""
                        discovery = null
                        selectedModelSource = null
                        providerMenuExpanded = false
                    },
                )
            }
        }

        if (customProvider) {
            OutlinedTextField(
                value = providerId,
                onValueChange = { providerId = it.filterNot(Char::isWhitespace) },
                label = { Text("Provider ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        selectedProviderStatus?.let { status ->
            Text(
                text = if (status.authenticated) {
                    "认证状态：已配置${status.authLabel?.let { "（$it）" }.orEmpty()}"
                } else {
                    "认证状态：未配置"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it.trimStart() },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        Column {
            OutlinedTextField(
                value = PI_MODEL_API_OPTIONS.firstOrNull { it.first == apiSelection }?.second
                    ?: apiSelection.wireValue,
                onValueChange = {},
                readOnly = true,
                label = { Text("API 类型") },
                trailingIcon = {
                    IconButton(onClick = { apiMenuExpanded = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "选择 API 类型")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            DropdownMenu(
                expanded = apiMenuExpanded,
                onDismissRequest = { apiMenuExpanded = false },
            ) {
                PI_MODEL_API_OPTIONS.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            apiSelection = value
                            apiMenuExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.replace("\n", "").replace("\r", "") },
            label = { Text("API Key") },
            placeholder = {
                Text(if (selectedProviderStatus?.authenticated == true) "留空则保留已保存凭据" else "输入 API Key")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        if (apiSelection == PiModelApi.AUTO) {
            Text(
                "需要探测多种模式，请耐心等待",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busyAction = "fetch"
                        errorText = null
                        try {
                            val result = adapter.fetch(buildDraft())
                            discovery = result
                            val preferred = modelId.takeIf { id -> result.models.any { it.id == id } }
                                ?: result.recommendedModel
                                ?: result.models.firstOrNull()?.id
                            if (!preferred.isNullOrBlank()) {
                                modelId = preferred
                                selectedModelSource = resolvedSourceForSelectedModel(
                                    apiSelection,
                                    result.models.firstOrNull { it.id == preferred },
                                )
                            }
                            showNotification("已获取 ${result.models.size} 个模型")
                        } catch (error: Exception) {
                            val apiError = error as? com.wuxianpi.pi.WuxianPiModelApiException
                            discovery = apiError?.modeResults?.takeIf(List<*>::isNotEmpty)?.let { modes ->
                                PiModelDraftResult(
                                    ok = false,
                                    models = modes.flatMap { it.models }.distinctBy { it.id },
                                    recommendedModel = null,
                                    resolvedApi = null,
                                    modeResults = modes,
                                    candidates = emptyList(),
                                    provider = providerId,
                                    modelId = null,
                                    latencyMs = null,
                                    status = apiError.statusCode,
                                    responseText = null,
                                    message = apiError.message,
                                    hint = null,
                                )
                            }
                            errorText = error.message ?: "获取模型列表失败"
                        } finally {
                            busyAction = null
                        }
                    }
                },
                enabled = busyAction == null && setup != null,
                modifier = Modifier.weight(1f),
            ) {
                if (busyAction == "fetch") CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("获取模型")
            }
            IconButton(
                onClick = { scope.launch { refresh(showMessage = true) } },
                enabled = busyAction == null,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新 Runtime 配置")
            }
        }

        val modeResults = discovery?.modeResults?.takeIf(List<*>::isNotEmpty)
        if (modeResults != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                modeResults.forEach { mode ->
                    Text(
                        text = buildString {
                            append(if (mode.ok) "成功" else "失败")
                            append(" · ").append(mode.label)
                            append(" · ").append(mode.modelCount).append(" 个模型")
                            mode.error?.let { append(" · ").append(it) }
                        },
                        color = if (mode.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else if (busyAction == "fetch" && apiSelection == PiModelApi.AUTO) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PI_MODEL_API_OPTIONS.drop(1).forEach { (_, label) ->
                    Text(
                        text = "探测中 · $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedTextField(
            value = modelId,
            onValueChange = {
                modelId = it.replace("\n", "").replace("\r", "")
                selectedModelSource = null
            },
            label = { Text("模型 ID") },
            supportingText = { Text("可从返回列表选择，也可以手动填写") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = { modelDialogVisible = true },
                    enabled = selectableModels.isNotEmpty(),
                ) { Text("选择") }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("高级模式", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = advanced, onCheckedChange = { advanced = it })
        }

        if (advanced) {
            OutlinedTextField(
                value = headersJson,
                onValueChange = { headersJson = it },
                label = { Text("Headers（JSON）") },
                supportingText = { Text("例如 {\"X-Client\":\"Operit\"}") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                minLines = 4,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("保存时设为全局默认模型", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = setGlobalDefault, onCheckedChange = { setGlobalDefault = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busyAction = "test"
                        errorText = null
                        try {
                            val draft = buildDraft()
                            val resolved = effectiveApiForSelectedModel(apiSelection, selectedModelSource)
                            val result = adapter.test(draft.copy(api = resolved))
                            showNotification("模型测试通过${result.latencyMs?.let { "，${it}ms" }.orEmpty()}")
                        } catch (error: Exception) {
                            errorText = error.message ?: "模型测试失败"
                        } finally {
                            busyAction = null
                        }
                    }
                },
                enabled = busyAction == null && modelId.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (busyAction == "test") CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Science, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("测试")
            }
            Button(
                onClick = {
                    scope.launch {
                        val originalSetup = setup ?: return@launch
                        busyAction = "apply"
                        errorText = null
                        try {
                            val draft = buildDraft()
                            val resolved = effectiveApiForSelectedModel(apiSelection, selectedModelSource)
                            val outcome = adapter.apply(
                                originalSetup,
                                draft.copy(api = resolved),
                                setGlobalDefault,
                            )
                            configManager.updatePiModelBinding(config.id, outcome.binding)
                            hydrate(outcome.setup, outcome.binding)
                            showNotification("模型配置已保存并启用")
                        } catch (conflict: PiModelRevisionConflictException) {
                            hydrate(conflict.refreshedSetup, config.piModelBinding)
                            errorText = conflict.message
                        } catch (error: Exception) {
                            errorText = error.message ?: "保存模型配置失败"
                        } finally {
                            busyAction = null
                        }
                    }
                },
                enabled = busyAction == null && setup != null && providerId.isNotBlank() && modelId.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (busyAction == "apply") CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("保存并启用")
            }
        }

        HorizontalDivider()
        Text("Android 本地引擎", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope.launch { configManager.switchToLocalModelEngine(config.id, ApiProviderType.MNN) } }) {
                Text("切换到 MNN")
            }
            TextButton(onClick = { scope.launch { configManager.switchToLocalModelEngine(config.id, ApiProviderType.LLAMA_CPP) } }) {
                Text("切换到 llama.cpp")
            }
        }
    }

    if (modelDialogVisible) {
        AlertDialog(
            onDismissRequest = { modelDialogVisible = false },
            title = { Text("选择模型") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(selectableModels, key = { it.id }) { model ->
                        TextButton(
                            onClick = {
                                modelId = model.id
                                selectedModelSource = resolvedSourceForSelectedModel(apiSelection, model)
                                modelDialogVisible = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(model.name ?: model.id, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (model.name != null && model.name != model.id) {
                                    Text(
                                        model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (model.sources.isNotEmpty()) {
                                    Text(
                                        model.sources.joinToString(" / ") { source ->
                                            PI_MODEL_API_OPTIONS.firstOrNull { it.first == source }?.second
                                                ?: source.wireValue
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modelDialogVisible = false }) { Text("关闭") } },
        )
    }
}

internal fun parsePiHeaders(value: String): Map<String, String> {
    if (value.isBlank()) return emptyMap()
    val json = try {
        JSONObject(value)
    } catch (error: Exception) {
        throw IllegalArgumentException("Headers 必须是 JSON 对象", error)
    }
    return json.keys().asSequence().associate { key ->
        key.trim().takeIf(String::isNotEmpty)
            ?.let { it to json.optString(key) }
            ?: throw IllegalArgumentException("Header 名称不能为空")
    }
}

internal fun resolvedSourceForSelectedModel(
    apiSelection: PiModelApi,
    model: PiDiscoveredModel?,
): PiModelApi? = if (apiSelection == PiModelApi.AUTO) {
    model?.sources?.firstOrNull { it != PiModelApi.AUTO }
} else {
    null
}

internal fun effectiveApiForSelectedModel(
    apiSelection: PiModelApi,
    selectedModelSource: PiModelApi?,
): PiModelApi {
    if (apiSelection != PiModelApi.AUTO) return apiSelection
    return selectedModelSource?.takeUnless { it == PiModelApi.AUTO }
        ?: throw IllegalArgumentException("手动填写模型 ID 时不能使用 Auto，请选择具体 API 类型")
}
