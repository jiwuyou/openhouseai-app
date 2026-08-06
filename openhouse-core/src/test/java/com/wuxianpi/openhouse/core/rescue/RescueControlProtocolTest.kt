package com.wuxianpi.openhouse.core.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueControlProtocolTest {
    @Test
    fun processIdentityUsesDedicatedRescueSuffix() {
        assertEquals(":rescue_ui", RescueControlProtocol.PROCESS_SUFFIX)
        assertEquals(
            "com.wuxianpi:rescue_ui",
            RescueControlProtocol.processName("com.wuxianpi"),
        )
    }

    @Test
    fun runningStatesAreDistinctFromStoppedState() {
        assertFalse(RescueProcessState.NOT_RUNNING.isRunningLike())
        assertTrue(RescueProcessState.FOREGROUND.isRunningLike())
        assertTrue(RescueProcessState.BACKGROUND.isRunningLike())
        assertTrue(RescueProcessState.STOPPING.isRunningLike())
        assertEquals(
            RescueProcessState.NOT_RUNNING,
            RescueProcessState.fromWireName("invalid"),
        )
    }
}
