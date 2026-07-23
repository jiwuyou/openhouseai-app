package com.ai.assistance.operit.host.terminal.tmux

/** Result of executing one program inside the Termux user space. */
data class TermuxTransportResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val errCode: Int = -1,
    val errorMessage: String = "",
    val timedOut: Boolean = false,
)

/**
 * Minimal transport adapters must implement to host the shared tmux session engine.
 *
 * [program] is relative to `$PREFIX/bin`; native hosts normally dispatch it with RUN_COMMAND,
 * while the embedded Termux host executes it directly.
 */
interface TermuxSessionTransport {
    val termuxPrefix: String

    suspend fun executeProgram(
        program: String,
        arguments: List<String>,
        stdin: String? = null,
        timeoutMs: Long = DEFAULT_PROGRAM_TIMEOUT_MS,
    ): TermuxTransportResult

    companion object {
        const val DEFAULT_PROGRAM_TIMEOUT_MS = 15_000L
    }
}
