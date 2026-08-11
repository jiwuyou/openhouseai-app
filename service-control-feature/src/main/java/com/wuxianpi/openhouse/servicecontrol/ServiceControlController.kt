package com.wuxianpi.openhouse.servicecontrol

import com.wuxianpi.openhouse.core.ControlPlaneStartCoordinator
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServiceControlController(
    request: ServiceControlRequest,
    private val dependencies: ServiceControlDependencies,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(
        ServiceControlUiState(
            request = request,
            controlPlaneEndpoint = normalizeEndpoint(
                dependencies.httpClient.runtimeConnection().serviceManagerBaseUrl,
            ),
        ),
    )
    val state: StateFlow<ServiceControlUiState> = _state.asStateFlow()

    private var operation: Job? = null

    fun refresh() = runExclusive {
        refreshInternal()
    }

    fun startControlPlane() = runExclusive {
        _state.update {
            it.copy(
                controlPlaneState = ControlPlaneState.CHECKING,
                statusMessage = "正在启动运行中枢...",
            )
        }
        val result = runCatching {
            onIo {
                ControlPlaneStartCoordinator.start(
                    dependencies.openHouseHost.controlPlaneBridge(),
                    "manual",
                ) { stream, line ->
                    _state.update { current ->
                        val prefix = if (stream == "stderr") "[stderr] " else ""
                        current.copy(statusMessage = appendBoundedLog(current.statusMessage, prefix + line))
                    }
                }
            }
        }.getOrElse {
            com.wuxianpi.openhouse.core.ControlPlaneCommandResult(1, "", readableError(it))
        }
        val transcript = ControlPlaneStartCoordinator.latestTranscript()
        if (result.isSuccess) {
            runCatching {
                onIo { dependencies.httpClient.healthCheck() }.requireSuccess()
            }.getOrElse {
                _state.update { current ->
                    current.copy(
                        controlPlaneState = ControlPlaneState.OFFLINE,
                        statusMessage = "启动命令已完成，但 health 验证失败：${readableError(it)}\n$transcript",
                    )
                }
                return@runExclusive
            }
            refreshInternal()
            if (state.value.controlPlaneState != ControlPlaneState.ONLINE) {
                _state.update { current ->
                    current.copy(
                        statusMessage = "启动命令已完成，但带 token 的服务列表验证失败。\n${current.statusMessage}",
                    )
                }
                return@runExclusive
            }
            _state.update { current ->
                current.copy(
                    statusMessage = "运行中枢启动成功；health 和带 token 的服务列表均已通过。\n$transcript",
                )
            }
            return@runExclusive
        }
        _state.update {
            it.copy(
                controlPlaneState = ControlPlaneState.OFFLINE,
                statusMessage = buildString {
                    append("运行中枢启动命令失败")
                    append("，exitCode=").append(result.exitCode)
                    if (transcript.isNotBlank()) append('\n').append(transcript)
                },
            )
        }
    }

    private fun appendBoundedLog(current: String, line: String): String =
        (current.lineSequence() + sequenceOf(line)).toList().takeLast(200).joinToString("\n")

    fun openMaintenance() = runExclusive {
        val result = runCatching { onIo { dependencies.openHouseHost.openHostMaintenance() } }
            .getOrElse {
                com.wuxianpi.openhouse.core.HostActionResult(
                    com.wuxianpi.openhouse.core.HostActionResult.Status.FAILED,
                    readableError(it),
                )
            }
        _state.update {
            it.copy(statusMessage = result.message.ifBlank {
                if (result.isSuccess) "已打开维护与修复。" else "无法打开维护与修复。"
            })
        }
    }

    fun runAction(serviceId: String, action: ServiceAction) = runExclusive(serviceId) {
        val id = sanitizeServiceId(serviceId)
        if (id.isEmpty()) {
            _state.update { it.copy(statusMessage = "服务 ID 无效。") }
            return@runExclusive
        }
        if (isControlPlaneService(id) && action.blocksControlPlane()) {
            _state.update { it.copy(statusMessage = "不能在服务卡片中${action.label()}控制中枢，请使用控制中枢操作。") }
            return@runExclusive
        }
        _state.update { it.copy(statusMessage = "正在${action.label()} $id...") }
        val actionResult = runCatching {
            onIo { dependencies.httpClient.runAction(id, action) }.requireSuccess()
        }.getOrElse {
            markOffline("$id ${action.label()}失败。${readableError(it)}")
            return@runExclusive
        }
        val refreshed = runCatching { loadStatus(id) }
            .getOrElse { statusError ->
                _state.update { current ->
                    current.copy(statusMessage = "$id ${action.label()}已提交，但状态刷新失败：${readableError(statusError)}")
                }
                return@runExclusive
            }
        replaceService(refreshed)
        _state.update {
            it.copy(
                controlPlaneState = ControlPlaneState.ONLINE,
                statusMessage = actionResult.message.ifBlank { "$id ${action.label()}已提交。" },
            )
        }
    }

    fun runBulkAction(action: ServiceAction) = runExclusive {
        val targets = state.value.services.filterNot {
            it.isControlPlane && (action == ServiceAction.STOP || action == ServiceAction.RESTART)
        }
        if (targets.isEmpty()) {
            _state.update { it.copy(statusMessage = "没有可操作的服务。") }
            return@runExclusive
        }
        var succeeded = 0
        val failures = mutableListOf<String>()
        targets.forEach { service ->
            val result = runCatching {
                onIo { dependencies.httpClient.runAction(service.normalizedId, action) }.requireSuccess()
            }.getOrElse {
                failures += "${service.normalizedId}: ${readableError(it)}"
                return@forEach
            }
            if (result.success) succeeded++ else failures += "${service.normalizedId}: ${result.message.ifBlank { "失败" }}"
        }
        val skipped = state.value.services.size - targets.size
        _state.update {
            it.copy(
                statusMessage = buildString {
                    append("全部${action.label()}完成：成功 $succeeded，失败 ${failures.size}，跳过 $skipped。")
                    if (failures.isNotEmpty()) append("\n").append(failures.joinToString("\n"))
                }
            )
        }
        refreshInternal(preserveMessage = true)
    }

    fun fetchLogs(serviceId: String) = runExclusive(serviceId) {
        val id = sanitizeServiceId(serviceId)
        if (id.isEmpty()) return@runExclusive
        _state.update { it.copy(statusMessage = "正在读取 $id 日志...") }
        val logs = runCatching {
            onIo { dependencies.httpClient.getLogs(id, LOG_LIMIT) }
                .requireSuccess()
                .logLines
                .map { ServiceLogLine(it.time, it.stream, it.message) }
        }
            .getOrElse {
                markOffline("$id 日志读取失败：${readableError(it)}")
                return@runExclusive
            }
        _state.update {
            it.copy(
                logs = it.logs + (id to logs.takeLast(LOG_LIMIT)),
                expandedLogs = it.expandedLogs + id,
                statusMessage = "$id 日志已读取。",
            )
        }
    }

    fun toggleLogs(serviceId: String) {
        val id = sanitizeServiceId(serviceId)
        if (id.isEmpty()) return
        if (state.value.logs[id] == null) {
            fetchLogs(id)
            return
        }
        _state.update {
            it.copy(expandedLogs = if (id in it.expandedLogs) it.expandedLogs - id else it.expandedLogs + id)
        }
    }

    fun openService(service: OpenHouseService) {
        val endpoint = normalizeEndpoint(service.endpoint)
        val result = if (service.opensAdvancedUi()) {
            dependencies.featureLauncher.openAdvancedUi(endpoint)
        } else {
            dependencies.featureLauncher.openServiceEndpoint(service.normalizedId, endpoint)
        }
        if (!result.isSuccess || result.message.isNotBlank()) {
            _state.update {
                it.copy(statusMessage = result.message.ifBlank { "这个服务没有可打开的页面。" })
            }
        }
    }

    fun returnToProduct() {
        dependencies.featureLauncher.returnToProduct()
    }

    fun cancel() {
        operation?.cancel()
    }

    private suspend fun refreshInternal(preserveMessage: Boolean = false) {
        _state.update {
            it.copy(
                controlPlaneState = ControlPlaneState.CHECKING,
                statusMessage = if (preserveMessage) it.statusMessage else "正在读取 service-manager 服务列表...",
            )
        }
        val listed = runCatching {
            onIo { dependencies.httpClient.listServices() }
                .requireSuccess()
                .services
                .map { service ->
                    OpenHouseService(
                        id = service.id,
                        displayName = service.displayName(),
                        provider = service.provider,
                        state = service.state.ifBlank { "unknown" },
                        pid = service.pid?.toLong(),
                        message = service.message,
                        endpoint = service.url,
                    )
                }
        }
            .getOrElse {
                markOffline("控制中枢异常：无法读取 service-manager 服务列表。\n${readableError(it)}")
                return
            }
        val requested = state.value.request.serviceIds.map(::sanitizeServiceId).filter(String::isNotEmpty).distinct()
        val selected = if (state.value.request.showAllServices || requested.isEmpty()) {
            listed
        } else {
            val byId = listed.associateBy { it.normalizedId }
            requested.map { id -> byId[id] ?: OpenHouseService(id = id, statusAvailable = false, message = "服务未出现在当前列表中") }
        }
        val refreshed = selected.map { listedService ->
            if (!listedService.statusAvailable) return@map listedService
            runCatching { loadStatus(listedService.normalizedId) }
                .getOrElse {
                    listedService.copy(statusAvailable = false, message = readableError(it))
                }
                .mergeListMetadata(listedService)
        }
        _state.update {
            it.copy(
                controlPlaneState = ControlPlaneState.ONLINE,
                services = refreshed.filter { service -> service.normalizedId.isNotEmpty() },
                statusMessage = if (preserveMessage) it.statusMessage else when {
                    refreshed.isEmpty() -> "service-manager 没有返回可控制服务。"
                    refreshed.any { service -> !service.statusAvailable } -> "已连接控制中枢，部分服务状态不可用。"
                    else -> "状态已刷新，共 ${refreshed.size} 个服务。"
                },
            )
        }
    }

    private fun replaceService(service: OpenHouseService) {
        val id = service.normalizedId
        _state.update { current ->
            val existing = current.services.firstOrNull { it.normalizedId == id }
            val updated = service.mergeListMetadata(existing ?: service)
            if (existing == null) {
                current.copy(services = current.services + updated)
            } else {
                current.copy(services = current.services.map { if (it.normalizedId == id) updated else it })
            }
        }
    }

    private fun markOffline(message: String) {
        val startupLog = ControlPlaneStartCoordinator.latestTranscript().takeIf {
            ControlPlaneStartCoordinator.latestResult().exitCode != -1 && it.isNotBlank()
        }.orEmpty()
        _state.update {
            it.copy(
                controlPlaneState = ControlPlaneState.OFFLINE,
                statusMessage = if (startupLog.isEmpty()) message else "$message\n最近一次启动日志：\n$startupLog",
            )
        }
    }

    private fun runExclusive(activeServiceId: String? = null, block: suspend () -> Unit) {
        val previous = operation
        operation = scope.launch {
            if (previous?.isActive == true) {
                previous.join()
            }
            _state.update { it.copy(busy = true, activeServiceId = activeServiceId) }
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false, activeServiceId = null) }
            }
        }
    }

    private fun OpenHouseService.mergeListMetadata(listed: OpenHouseService): OpenHouseService = copy(
        displayName = displayName.ifBlank { listed.displayName.ifBlank { normalizedId } },
        provider = provider.ifBlank { listed.provider },
        endpoint = normalizeEndpoint(endpoint).ifBlank { normalizeEndpoint(listed.endpoint) },
    )

    private fun OpenHouseService.opensAdvancedUi(): Boolean = when (normalizedId) {
        "aionui-web" -> true
        else -> false
    }

    private fun ServiceAction.label(): String = when (this) {
        ServiceAction.START -> "启动"
        ServiceAction.STOP -> "关闭"
        ServiceAction.RESTART -> "重启"
        ServiceAction.REPAIR -> "修复"
    }

    private fun ServiceAction.blocksControlPlane(): Boolean =
        this == ServiceAction.STOP || this == ServiceAction.RESTART

    private fun readableError(error: Throwable): String =
        error.message?.trim().takeUnless { it.isNullOrEmpty() } ?: error.javaClass.simpleName

    private suspend fun loadStatus(serviceId: String): OpenHouseService {
        val result = onIo { dependencies.httpClient.getStatus(serviceId) }.requireSuccess()
        return OpenHouseService(
            id = serviceId,
            provider = result.provider,
            state = result.state.ifBlank { "unknown" },
            pid = result.pid?.toLong(),
            message = result.message,
            endpoint = result.url,
        )
    }

    private fun ServiceManagerResult.requireSuccess(): ServiceManagerResult {
        if (!success) throw ServiceManagerRequestException(message.ifBlank { "service-manager request failed" })
        return this
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    companion object {
        private const val LOG_LIMIT = 80
    }
}

private class ServiceManagerRequestException(message: String) : IllegalStateException(message)
