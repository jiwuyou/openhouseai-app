package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal object TermuxSessionProtocol {
    private val sessionNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val sessionIdPattern = Regex("^operit_[vh]_u_[0-9a-f]{24}$")

    const val DEFAULT_TIMEOUT_MS = 1_800_000L
    const val DEFAULT_HIDDEN_TIMEOUT_MS = 120_000L
    const val MAX_TIMEOUT_MS = 86_400_000L
    const val TMUX_TIMEOUT_MS = 15_000L
    const val POLL_INTERVAL_MS = 120L
    const val HISTORY_LIMIT = "200000"

    fun requireSessionName(value: String): String {
        require(sessionNamePattern.matches(value)) {
            "session_name must start with a letter or digit, contain only letters, digits, '.', '_', ':', or '-', and be at most 128 characters"
        }
        return value
    }

    fun requireSessionId(value: String): String {
        require(sessionIdPattern.matches(value)) { "Invalid terminal session_id" }
        return value
    }

    fun sessionIdForName(sessionName: String): String {
        val name = requireSessionName(sessionName)
        return "operit_v_u_${digest("ubuntu:$name")}"
    }

    fun hiddenSessionIdForKey(executorKey: String): String {
        val key = requireSessionName(executorKey)
        return "operit_h_u_${digest("ubuntu:$key")}"
    }

    fun targetForSessionId(sessionId: String): HostTerminalTarget {
        requireSessionId(sessionId)
        return HostTerminalTarget.UBUNTU
    }

    fun newCommandToken(): String = UUID.randomUUID().toString().replace("-", "")

    fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun tmuxTarget(sessionId: String): String = "=$sessionId"

    fun ubuntuShellCommand(termuxPrefix: String): String {
        val bootstrap =
            "stty -echo; export PS1='' PROMPT_COMMAND=''; printf '%s\\n' '__OPERIT_READY__'; " +
                "exec bash --noprofile --norc -i"
        val arguments =
            listOf(
                "$termuxPrefix/bin/proot-distro",
                "login",
                "ubuntu",
                "--",
                "bash",
                "-lc",
                bootstrap,
            )
        return arguments.joinToString(" ", transform = ::shellQuote)
    }

    fun pipeCommand(termuxPrefix: String, outputPath: String): String =
        "${shellQuote("$termuxPrefix/bin/cat")} > ${shellQuote(outputPath)}"

    fun commandPayload(command: String, token: String): String {
        require(command.isNotBlank()) { "command must not be blank" }
        require(token.matches(Regex("^[A-Za-z0-9]+$"))) { "Invalid command token" }
        val encoded = base64(command)
        return buildString {
            append("printf '%s\\n' ")
            append(shellQuote(beginMarker(token)))
            append("; eval \"$(printf '%s' ")
            append(shellQuote(encoded))
            append(" | base64 -d)\"; __operit_status=\$?; printf '\\n%s:%d\\n' ")
            append(shellQuote(endMarker(token)))
            append(" \"\$__operit_status\"")
        }
    }

    fun beginMarker(token: String): String = "__OPERIT_BEGIN_${token}__"

    fun endMarker(token: String): String = "__OPERIT_END_${token}__"

    fun parseCapture(raw: String, token: String): CaptureParse {
        val normalized = raw.replace("\r\n", "\n")
        val begin = beginMarker(token)
        val beginIndex = normalized.lastIndexOf(begin)
        if (beginIndex < 0) return CaptureParse("", null, false)

        val payloadStart = normalized.indexOf('\n', beginIndex + begin.length).let { index ->
            if (index < 0) normalized.length else index + 1
        }
        val endRegex =
            Regex("(?:^|\\n)${Regex.escape(endMarker(token))}:(-?[0-9]+)(?:\\r?\\n|$)")
        val endMatch = endRegex.find(normalized, payloadStart)
        val payloadEnd = endMatch?.range?.first ?: normalized.length
        return CaptureParse(
            output = normalized.substring(payloadStart, payloadEnd),
            exitCode = endMatch?.groupValues?.get(1)?.toIntOrNull(),
            complete = endMatch != null,
        )
    }

    fun normalizeControl(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (value) {
            "return" -> "enter"
            "escape" -> "esc"
            "arrowup" -> "up"
            "arrowdown" -> "down"
            "arrowleft" -> "left"
            "arrowright" -> "right"
            "pgup", "page_up" -> "pageup"
            "pgdn", "page_down" -> "pagedown"
            "del" -> "delete"
            else -> value
        }
    }

    fun controlKey(control: String): String? =
        when (control) {
            "enter" -> "Enter"
            "tab" -> "Tab"
            "esc" -> "Escape"
            "up" -> "Up"
            "down" -> "Down"
            "left" -> "Left"
            "right" -> "Right"
            "home" -> "Home"
            "end" -> "End"
            "pageup" -> "PageUp"
            "pagedown" -> "PageDown"
            "backspace" -> "BSpace"
            "delete" -> "DC"
            else -> null
        }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(24)
}

internal data class CaptureParse(
    val output: String,
    val exitCode: Int?,
    val complete: Boolean,
)
