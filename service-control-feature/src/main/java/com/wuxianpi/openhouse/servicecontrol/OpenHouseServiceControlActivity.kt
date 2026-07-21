@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wuxianpi.openhouse.servicecontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wuxianpi.openhouse.core.service.ServiceAction

class OpenHouseServiceControlActivity : ComponentActivity() {
    private var controller: ServiceControlController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent.toServiceControlRequest()
        if (!ServiceControlFeature.isInstalled()) {
            setContent {
                MaterialTheme {
                    MissingCoreAdapterScreen(onClose = ::finish)
                }
            }
            return
        }
        val createdController = ServiceControlController(
            request = request,
            dependencies = ServiceControlFeature.dependencies(this),
            scope = lifecycleScope,
        )
        controller = createdController
        setContent {
            MaterialTheme {
                ServiceControlRoute(createdController)
            }
        }
        createdController.refresh()
    }

    override fun onDestroy() {
        controller?.cancel()
        controller = null
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_SHOW_ALL = "com.wuxianpi.openhouse.servicecontrol.SHOW_ALL"
        internal const val EXTRA_COMPONENT_ID = "com.wuxianpi.openhouse.servicecontrol.COMPONENT_ID"
        internal const val EXTRA_TITLE = "com.wuxianpi.openhouse.servicecontrol.TITLE"
        internal const val EXTRA_ENDPOINT = "com.wuxianpi.openhouse.servicecontrol.ENDPOINT"
        internal const val EXTRA_SERVICE_IDS = "com.wuxianpi.openhouse.servicecontrol.SERVICE_IDS"

        @JvmStatic
        fun createIntent(context: Context, request: ServiceControlRequest): Intent =
            Intent(context, OpenHouseServiceControlActivity::class.java).apply {
                putExtra(EXTRA_SHOW_ALL, request.showAllServices)
                putExtra(EXTRA_COMPONENT_ID, request.componentId)
                putExtra(EXTRA_TITLE, request.title)
                putExtra(EXTRA_ENDPOINT, request.componentEndpoint)
                putStringArrayListExtra(EXTRA_SERVICE_IDS, ArrayList(request.serviceIds))
            }
    }
}

private fun Intent?.toServiceControlRequest(): ServiceControlRequest {
    if (this == null) return ServiceControlRequest()
    val ids = getStringArrayListExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_IDS)
        .orEmpty()
        .map(::sanitizeServiceId)
        .filter(String::isNotEmpty)
        .distinct()
    return ServiceControlRequest(
        title = getStringExtra(OpenHouseServiceControlActivity.EXTRA_TITLE).orEmpty().trim(),
        componentId = sanitizeServiceId(getStringExtra(OpenHouseServiceControlActivity.EXTRA_COMPONENT_ID)),
        componentEndpoint = normalizeEndpoint(getStringExtra(OpenHouseServiceControlActivity.EXTRA_ENDPOINT)),
        serviceIds = ids,
        showAllServices = getBooleanExtra(OpenHouseServiceControlActivity.EXTRA_SHOW_ALL, ids.isEmpty()),
    )
}

@Composable
private fun ServiceControlRoute(controller: ServiceControlController) {
    val state by controller.state.collectAsState()
    ServiceControlScreen(
        state = state,
        onBack = controller::returnToProduct,
        onRefresh = controller::refresh,
        onStartControlPlane = controller::startControlPlane,
        onMaintenance = controller::openMaintenance,
        onAction = controller::runAction,
        onBulkAction = controller::runBulkAction,
        onLogs = controller::toggleLogs,
        onOpen = controller::openService,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceControlScreen(
    state: ServiceControlUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStartControlPlane: () -> Unit,
    onMaintenance: () -> Unit,
    onAction: (String, ServiceAction) -> Unit,
    onBulkAction: (ServiceAction) -> Unit,
    onLogs: (String) -> Unit,
    onOpen: (OpenHouseService) -> Unit,
) {
    val title = state.request.title.ifBlank {
        if (state.request.showAllServices) "全部服务控制" else "${state.request.componentId.ifBlank { "组件" }} 控制"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ControlPlanePanel(
                    state = state,
                    onStartControlPlane = onStartControlPlane,
                    onMaintenance = onMaintenance,
                )
            }
            if (state.request.showAllServices && state.services.isNotEmpty()) {
                item {
                    BulkControls(enabled = !state.busy, onBulkAction = onBulkAction)
                }
            }
            items(state.services, key = { it.normalizedId }) { service ->
                ServiceCard(
                    service = service,
                    logs = state.logs[service.normalizedId].orEmpty(),
                    logsExpanded = service.normalizedId in state.expandedLogs,
                    enabled = !state.busy,
                    active = state.activeServiceId == service.normalizedId,
                    onAction = onAction,
                    onLogs = onLogs,
                    onOpen = onOpen,
                )
            }
            if (!state.busy && state.services.isEmpty()) {
                item {
                    Text(
                        text = "service-manager 没有返回可控制服务。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun ControlPlanePanel(
    state: ServiceControlUiState,
    onStartControlPlane: () -> Unit,
    onMaintenance: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("控制中枢", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (state.busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Text(
                when (state.controlPlaneState) {
                    ControlPlaneState.CHECKING -> "正在检查 service-manager"
                    ControlPlaneState.ONLINE -> "已连接 service-manager"
                    ControlPlaneState.OFFLINE -> "service-manager 不可达"
                },
                color = if (state.controlPlaneState == ControlPlaneState.OFFLINE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
            if (state.showRecoveryActions) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartControlPlane, enabled = !state.busy) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("启动运行中枢")
                    }
                    Button(onClick = onMaintenance, enabled = !state.busy) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Text("维护与修复")
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkControls(enabled: Boolean, onBulkAction: (ServiceAction) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("批量控制", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onBulkAction(ServiceAction.START) }, enabled = enabled) { Text("全部启动") }
                TextButton(onClick = { onBulkAction(ServiceAction.STOP) }, enabled = enabled) { Text("全部关闭") }
                TextButton(onClick = { onBulkAction(ServiceAction.RESTART) }, enabled = enabled) { Text("全部重启") }
                TextButton(onClick = { onBulkAction(ServiceAction.REPAIR) }, enabled = enabled) { Text("全部修复") }
            }
        }
    }
}

@Composable
private fun ServiceCard(
    service: OpenHouseService,
    logs: List<ServiceLogLine>,
    logsExpanded: Boolean,
    enabled: Boolean,
    active: Boolean,
    onAction: (String, ServiceAction) -> Unit,
    onLogs: (String) -> Unit,
    onOpen: (OpenHouseService) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(service.displayName.ifBlank { service.normalizedId }, fontWeight = FontWeight.Bold)
                    Text(service.normalizedId, style = MaterialTheme.typography.labelSmall)
                }
                if (active) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                "状态：${stateLabel(service.state)}\n" +
                    "provider：${service.provider.ifBlank { "-" }}\n" +
                    "pid：${service.pid?.takeIf { it > 0 } ?: "-"}\n" +
                    "endpoint：${normalizeEndpoint(service.endpoint).ifBlank { "-" }}\n" +
                    "消息：${service.message.ifBlank { "-" }}",
                style = MaterialTheme.typography.bodySmall,
                color = if (service.statusAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { onOpen(service) },
                    enabled = enabled && (service.normalizedId in FEATURE_SERVICE_IDS || normalizeEndpoint(service.endpoint).isNotEmpty()),
                ) { Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "打开 ${service.displayName}") }
                IconButton(
                    onClick = { onAction(service.normalizedId, ServiceAction.START) },
                    enabled = enabled && service.statusAvailable,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "启动 ${service.displayName}")
                }
                IconButton(
                    onClick = { onAction(service.normalizedId, ServiceAction.STOP) },
                    enabled = enabled && service.statusAvailable && !service.isControlPlane,
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "关闭 ${service.displayName}")
                }
                IconButton(
                    onClick = { onAction(service.normalizedId, ServiceAction.RESTART) },
                    enabled = enabled && service.statusAvailable && !service.isControlPlane,
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "重启 ${service.displayName}")
                }
                IconButton(
                    onClick = { onAction(service.normalizedId, ServiceAction.REPAIR) },
                    enabled = enabled && service.statusAvailable,
                ) {
                    Icon(Icons.Default.Build, contentDescription = "修复 ${service.displayName}")
                }
                IconButton(onClick = { onLogs(service.normalizedId) }, enabled = enabled) {
                    Icon(Icons.Default.Description, contentDescription = "日志 ${service.displayName}")
                }
            }
            if (logsExpanded) {
                HorizontalDivider()
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        logs.ifEmpty { listOf(ServiceLogLine(message = "暂无日志。")) }.joinToString("\n") {
                            listOf(it.time, it.stream, it.message).filter(String::isNotBlank).joinToString(" | ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingCoreAdapterScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("服务控制尚未连接 OpenHouse Core", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("请检查宿主 Application 是否已安装 service-control 适配器。")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose) { Text("返回") }
    }
}

private val FEATURE_SERVICE_IDS = setOf("openhouse-web", "aionui-web")
