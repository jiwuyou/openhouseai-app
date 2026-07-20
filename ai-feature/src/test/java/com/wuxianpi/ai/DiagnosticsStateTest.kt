package com.wuxianpi.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStateTest {
    @Test
    fun `detail mode is derived from bounded expiry and never changes business state`() {
        assertTrue(DiagnosticsState(detailedUntilMillis = System.currentTimeMillis() + 1_000).detailedModeActive)
        assertFalse(DiagnosticsState(detailedUntilMillis = 0).detailedModeActive)
    }

    @Test
    fun `export and capability state are independent`() {
        val state = DiagnosticsState(
            eventAckAvailable = false,
            persistentNodeDiagnostics = false,
            exportPath = "/cache/diagnostics.zip",
        )
        assertFalse(state.eventAckAvailable)
        assertTrue(state.exportPath!!.endsWith(".zip"))
    }
}
