package com.ai.assistance.operit.host.terminal

data class HostTerminalHiddenResult(
    val state: State,
    val exitCode: Int,
    val output: String,
    val error: String,
    val rawOutputPreview: String,
    val target: HostTerminalTarget = HostTerminalTarget.DEFAULT
) {
    enum class State {
        SUCCESS,
        FAILED,
        TIMEOUT,
        REJECTED
    }

    val isOk: Boolean
        get() = state == State.SUCCESS && exitCode == 0
}
