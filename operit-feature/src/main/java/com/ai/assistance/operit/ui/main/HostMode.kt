package com.ai.assistance.operit.ui.main

import com.ai.assistance.operit.ui.common.NavItem

/** Identifies the product surface hosting the shared Operit UI. */
enum class OperitHostMode {
    BASIC,
    RESCUE,
    STANDALONE;

    val isHosted: Boolean
        get() = this != STANDALONE

    /** The lean host exposes chat and settings; history remains inside the chat surface. */
    fun allowsDrawerItem(item: NavItem): Boolean = when (this) {
        STANDALONE -> true
        BASIC -> item in basicHostedDrawerItems
        RESCUE -> item in rescueHostedDrawerItems
    }

    companion object {
        private val basicHostedDrawerItems = setOf(
            NavItem.AiChat,
            NavItem.Settings,
        )
        private val rescueHostedDrawerItems = basicHostedDrawerItems + NavItem.MemoryBase

        fun fromExtra(value: String?): OperitHostMode = when (value?.trim()?.lowercase()) {
            "basic" -> BASIC
            "rescue", "repair" -> RESCUE
            else -> STANDALONE
        }
    }
}
