package com.ai.assistance.operit.rescue.ui

import com.ai.assistance.operit.ui.main.DEFAULT_HOSTED_CLOSE_LABEL
import com.ai.assistance.operit.ui.main.MainActivity
import com.wuxianpi.openhouse.core.rescue.RescueControlProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueHostedContractTest {
    @Test
    fun rescueUsesSharedHostReturnExtraAndItsOwnProcess() {
        assertEquals(MainActivity.EXTRA_HOST_RETURN_ACTIVITY, RescueActivity.EXTRA_HOST_RETURN_ACTIVITY)
        assertEquals(RescueControlProtocol.PROCESS_SUFFIX, RescueActivity.RESCUE_PROCESS_SUFFIX)
        assertNotEquals(
            RescueActivity.EXTRA_HOST_RETURN_ACTIVITY,
            RescueActivity.EXTRA_HOST_RETURN_INTENT,
        )
    }

    @Test
    fun hostedCloseLabelCanDifferFromBasicMode() {
        assertEquals("关闭 Operit", DEFAULT_HOSTED_CLOSE_LABEL)
        assertNotEquals("关闭救援助手", DEFAULT_HOSTED_CLOSE_LABEL)
    }

    @Test
    fun rescueManifestUsesSharedTaskAndIsolatedProcess() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val rescueBlock = manifest.substringAfter(
            "android:name=\".rescue.ui.RescueActivity\"",
        ).substringBefore("/>")

        assertTrue(rescueBlock.contains("android:launchMode=\"singleTop\""))
        assertTrue(rescueBlock.contains("android:process=\":rescue_ui\""))
        assertTrue(!rescueBlock.contains("taskAffinity"))
        assertTrue(!rescueBlock.contains("excludeFromRecents"))
    }

    @Test
    fun rescueShellKeepsHostNavigationOutsideTheEmbeddedWorkspace() {
        val layout = java.io.File("src/main/res/layout/activity_rescue_shell.xml").readText()
        assertTrue(layout.contains("@+id/rescue_shell_menu"))
        assertTrue(layout.contains("@+id/rescue_shell_top_desktop"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_desktop"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_set_home"))
        assertTrue(layout.contains("@+id/rescue_shell_workspace_apps"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_terminal"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_files"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_service"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_settings"))
        assertTrue(layout.contains("@+id/rescue_shell_nav_close"))
        assertTrue(layout.contains("@+id/rescue_content"))
    }
}
