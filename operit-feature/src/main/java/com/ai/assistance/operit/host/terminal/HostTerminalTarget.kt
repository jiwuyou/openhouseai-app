package com.ai.assistance.operit.host.terminal

enum class HostTerminalTarget(
    val wireName: String,
    val displayName: String,
    val sessionPrefix: String
) {
    ANDROID("android", "Android", "android"),
    TERMUX("termux", "Termux", "termux"),
    UBUNTU("ubuntu", "Ubuntu", "ubuntu");

    companion object {
        val DEFAULT: HostTerminalTarget = UBUNTU

        fun fromWireName(value: String?): HostTerminalTarget {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return values().firstOrNull {
                it.wireName == normalized || it.name.lowercase() == normalized
            } ?: DEFAULT
        }
    }
}
