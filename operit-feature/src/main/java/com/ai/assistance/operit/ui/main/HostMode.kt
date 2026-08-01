package com.ai.assistance.operit.ui.main

import com.ai.assistance.operit.ui.common.NavItem

/** Identifies the product surface hosting the shared Operit UI. */
enum class OperitHostMode {
    BASIC,
    RESCUE,
    STANDALONE;

    val isHosted: Boolean
        get() = this != STANDALONE

    /** Keep hosted surfaces focused on chat, history, and the host tools. */
    fun allowsDrawerItem(item: NavItem): Boolean = when (this) {
        STANDALONE -> true
        BASIC, RESCUE -> item in hostedDrawerItems
    }

    companion object {
        private val hostedDrawerItems = setOf(
            NavItem.AiChat,
            NavItem.MemoryBase,
            NavItem.Packages,
            NavItem.Workflow,
        )

        fun fromExtra(value: String?): OperitHostMode = when (value?.trim()?.lowercase()) {
            "basic" -> BASIC
            "rescue", "repair" -> RESCUE
            else -> STANDALONE
        }
    }
}
