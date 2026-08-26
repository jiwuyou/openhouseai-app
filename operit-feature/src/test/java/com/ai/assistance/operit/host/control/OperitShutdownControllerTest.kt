package com.ai.assistance.operit.host.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitShutdownControllerTest {
    @Test
    fun rescueShutdownMatchesOnlyRescueProcess() {
        assertTrue(
            OperitShutdownController.isExpectedProcessName(
                "com.wuxianpi",
                ":rescue_ui",
                "com.wuxianpi:rescue_ui",
            ),
        )
        assertFalse(
            OperitShutdownController.isExpectedProcessName(
                "com.wuxianpi",
                ":rescue_ui",
                "com.wuxianpi:operit",
            ),
        )
    }

    @Test
    fun rescueShutdownKeepsTheSharedHostTask() {
        assertFalse(OperitShutdownController.REMOVE_RESCUE_TASK_ON_SHUTDOWN)
    }
}
