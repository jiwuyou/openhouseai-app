package com.ai.assistance.operit.rescue.ui

import com.ai.assistance.operit.ui.main.DEFAULT_HOSTED_CLOSE_LABEL
import com.ai.assistance.operit.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RescueHostedContractTest {
    @Test
    fun rescueUsesSharedHostReturnExtraAndItsOwnProcess() {
        assertEquals(MainActivity.EXTRA_HOST_RETURN_ACTIVITY, RescueActivity.EXTRA_HOST_RETURN_ACTIVITY)
        assertEquals(":rescue_ui", RescueActivity.RESCUE_PROCESS_SUFFIX)
    }

    @Test
    fun hostedCloseLabelCanDifferFromBasicMode() {
        assertEquals("关闭 Operit", DEFAULT_HOSTED_CLOSE_LABEL)
        assertNotEquals("关闭救援助手", DEFAULT_HOSTED_CLOSE_LABEL)
    }
}
