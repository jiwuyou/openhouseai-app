package com.ai.assistance.operit.host.terminal

enum class HostTerminalTarget(
    val wireName: String,
    val displayName: String,
    val sessionPrefix: String
) {
    AUTO("auto", "Auto", "auto"),
    UBUNTU("ubuntu", "Ubuntu", "ubuntu"),
    TERMUX("termux", "Termux", "termux");

    companion object {
        val DEFAULT: HostTerminalTarget = AUTO

        fun fromWireName(value: String?): HostTerminalTarget {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return values().firstOrNull {
                it.wireName == normalized || it.name.lowercase() == normalized
            } ?: DEFAULT
        }
    }
}
