package com.ai.assistance.operit.rescue.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RescueSessionPhaseTest {
    @Test
    fun phasesOnlyAdvanceAndRuntimeCannotReturnToBootstrap() {
        assertEquals(
            RescueSessionPhase.SESSION_RUNTIME,
            nextRescueSessionPhase(
                RescueSessionPhase.SESSION_RUNTIME,
                RescueSessionPhase.MARKET_REFRESH,
            ),
        )
        assertEquals(
            RescueSessionPhase.COMPLETE,
            nextRescueSessionPhase(RescueSessionPhase.WRITE_MEMORY, RescueSessionPhase.COMPLETE),
        )
        assertThrows(IllegalArgumentException::class.java) {
            nextRescueSessionPhase(
                RescueSessionPhase.SESSION_RUNTIME,
                RescueSessionPhase.LOCAL_BOOTSTRAP,
            )
        }
    }
}
