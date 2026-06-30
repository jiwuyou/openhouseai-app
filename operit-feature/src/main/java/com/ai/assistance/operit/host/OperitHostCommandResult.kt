package com.ai.assistance.operit.host

data class OperitHostCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val error: String,
    val timedOut: Boolean,
    val durationMs: Long
) {
    val isSuccess: Boolean
        get() = exitCode == 0 && error.isBlank() && !timedOut
}
