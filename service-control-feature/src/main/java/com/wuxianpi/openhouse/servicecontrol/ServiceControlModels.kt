package com.wuxianpi.openhouse.servicecontrol

import java.util.Locale

data class OpenHouseService(
    val id: String,
    val displayName: String = id,
    val provider: String = "",
    val state: String = "unknown",
    val pid: Long? = null,
    val message: String = "",
    val endpoint: String = "",
    val statusAvailable: Boolean = true,
) {
    val normalizedId: String get() = sanitizeServiceId(id)
    val isControlPlane: Boolean get() = isControlPlaneService(normalizedId)
}

data class ServiceLogLine(
    val time: String = "",
    val stream: String = "",
    val message: String = "",
)

data class ServiceControlRequest(
    val title: String = "",
    val componentId: String = "",
    val componentEndpoint: String = "",
    val serviceIds: List<String> = emptyList(),
    val showAllServices: Boolean = true,
)

enum class ControlPlaneState {
    CHECKING,
    ONLINE,
    OFFLINE,
}

data class ServiceControlUiState(
    val request: ServiceControlRequest,
    val controlPlaneEndpoint: String = "",
    val controlPlaneState: ControlPlaneState = ControlPlaneState.CHECKING,
    val busy: Boolean = false,
    val statusMessage: String = "正在读取服务状态...",
    val services: List<OpenHouseService> = emptyList(),
    val logs: Map<String, List<ServiceLogLine>> = emptyMap(),
    val expandedLogs: Set<String> = emptySet(),
    val activeServiceId: String? = null,
) {
    val showRecoveryActions: Boolean get() = controlPlaneState == ControlPlaneState.OFFLINE
}

internal fun sanitizeServiceId(value: String?): String {
    val input = value.orEmpty().trim().lowercase(Locale.US)
    if (input.isEmpty() || input.length > 128) return ""
    return input.takeIf { candidate ->
        candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }.orEmpty()
}

internal fun isControlPlaneService(serviceId: String): Boolean {
    val normalized = sanitizeServiceId(serviceId)
    return normalized == "service-manager" || normalized.contains("service-manager")
}

internal fun normalizeEndpoint(value: String?): String {
    val endpoint = value.orEmpty().trim()
    return endpoint.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
}

internal fun stateLabel(value: String): String = when (value.trim().lowercase(Locale.US)) {
    "running", "active", "up" -> "运行中"
    "healthy", "ready" -> "正常"
    "stopped", "inactive", "down" -> "已停止"
    "starting" -> "启动中"
    "stopping" -> "关闭中"
    "restarting" -> "重启中"
    "repairing" -> "修复中"
    "failed", "error", "unhealthy" -> "异常"
    "missing" -> "缺失"
    "not-installed", "not_installed" -> "未安装"
    "disabled" -> "已禁用"
    "enabled" -> "已启用"
    else -> value.trim().ifEmpty { "未知" }
}
