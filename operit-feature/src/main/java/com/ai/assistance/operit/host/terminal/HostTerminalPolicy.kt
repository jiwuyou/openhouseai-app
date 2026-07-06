package com.ai.assistance.operit.host.terminal

object HostTerminalPolicy {
    private val backgroundPatterns =
        listOf(
            Regex("""(^|[;&|]\s*)nohup(\s|$)""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)setsid(\s|$)""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)daemon(\s|$)""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)disown(\s|$)""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)supervisord(\s|$)""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)pm2\s+(start|restart|resurrect)\b""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)systemctl\s+(start|restart|enable|daemon-reload)\b""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)service\s+\S+\s+(start|restart)\b""", RegexOption.IGNORE_CASE),
            Regex("""(^|[;&|]\s*)/?service-manager\b""", RegexOption.IGNORE_CASE),
            Regex("""(^|[^\S\r\n])&\s*($|[;|])""")
        )

    fun rejectionReason(command: String): String? {
        val normalized = command.trim()
        if (normalized.isBlank()) return "Command is empty"
        if (backgroundPatterns.any { it.containsMatchIn(normalized) }) {
            return "Long-running/background terminal commands must be managed by SmallPhoneAI service-manager."
        }
        return null
    }

    fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
