package com.ai.assistance.operit.host.terminal

data class HostTerminalState(
    val sessions: List<HostTerminalSession> = emptyList()
)

data class HostTerminalSession(
    val id: String,
    val title: String,
    val target: HostTerminalTarget = HostTerminalTarget.DEFAULT,
    val currentDirectory: String? = null,
    val lastOutput: String = "",
    val currentExecutingCommand: HostTerminalExecutingCommand = HostTerminalExecutingCommand()
) {
    val ansiParser: HostTerminalScreen
        get() = HostTerminalScreen(lastOutput)
}

data class HostTerminalExecutingCommand(
    val isExecuting: Boolean = false
)

class HostTerminalScreen(private val content: String) {
    fun getScreenContent(): Array<Array<HostTerminalChar>> {
        val lines = content.lines().ifEmpty { listOf("") }.takeLast(80)
        val width = lines.maxOfOrNull { it.length } ?: 0
        if (width == 0) {
            return arrayOf(arrayOf<HostTerminalChar>())
        }
        return Array(lines.size) { row ->
            Array(width) { col ->
                HostTerminalChar(lines[row].getOrNull(col) ?: ' ')
            }
        }
    }
}

data class HostTerminalChar(val char: Char)
