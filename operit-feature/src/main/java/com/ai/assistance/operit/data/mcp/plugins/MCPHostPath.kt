package com.ai.assistance.operit.data.mcp.plugins

import com.ai.assistance.operit.host.terminal.HostTerminalPolicy

internal object MCPHostPath {
    fun shellQuote(path: String): String {
        return if (path.startsWith("~/")) {
            "~/${HostTerminalPolicy.shellQuote(path.removePrefix("~/"))}"
        } else {
            HostTerminalPolicy.shellQuote(path)
        }
    }
}
