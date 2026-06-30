package com.ai.assistance.operit.host.terminal

data class HostTerminalCommandEvent(
    val sessionId: String,
    val commandId: String,
    val outputChunk: String,
    val isCompleted: Boolean,
    val exitCode: Int,
    val target: HostTerminalTarget = HostTerminalTarget.DEFAULT
)
