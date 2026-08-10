package com.ai.assistance.operit.ui.main

import com.ai.assistance.operit.ui.common.NavItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostModeTest {
    private val hostedModes = listOf(OperitHostMode.BASIC, OperitHostMode.RESCUE)

    @Test
    fun hostedModesAllowCoreItemsAndSettings() {
        val allowedItems =
            listOf(
                NavItem.AiChat,
                NavItem.Settings,
            )

        hostedModes.forEach { mode ->
            allowedItems.forEach { item ->
                assertTrue("$mode should allow ${item.route}", mode.allowsDrawerItem(item))
            }
        }
    }

    @Test
    fun hostedModesRejectOperitOnlyDrawerItems() {
        val rejectedItems =
            listOf(
                NavItem.Toolbox,
                NavItem.Packages,
                NavItem.Workflow,
                NavItem.About,
                NavItem.Help,
                NavItem.Agreement,
                NavItem.ShizukuCommands,
                NavItem.ToolPermissions,
                NavItem.UserPreferencesGuide,
            )

        hostedModes.forEach { mode ->
            rejectedItems.forEach { item ->
                assertFalse("$mode should reject ${item.route}", mode.allowsDrawerItem(item))
            }
        }
        assertFalse(OperitHostMode.BASIC.allowsDrawerItem(NavItem.MemoryBase))
        assertTrue(OperitHostMode.RESCUE.allowsDrawerItem(NavItem.MemoryBase))
    }

    @Test
    fun standaloneModeDoesNotFilterDrawerItems() {
        val representativeItems =
            listOf(
                NavItem.Settings,
                NavItem.Toolbox,
                NavItem.About,
                NavItem.Help,
                NavItem.Agreement,
                NavItem.ShizukuCommands,
                NavItem.ToolPermissions,
                NavItem.UserPreferencesGuide,
            )

        representativeItems.forEach { item ->
            assertTrue(
                "STANDALONE should allow ${item.route}",
                OperitHostMode.STANDALONE.allowsDrawerItem(item),
            )
        }
    }
}
