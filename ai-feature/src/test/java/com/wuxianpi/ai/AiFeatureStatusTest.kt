package com.wuxianpi.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFeatureStatusTest {
    @Test
    fun `activity lifecycle updates Java visible and running status`() {
        AiFeatureStatus.onCreated()
        AiFeatureStatus.onStarted()
        try {
            assertTrue(AiFeatureStatus.isRunning())
            assertTrue(AiFeatureStatus.isVisible())
            AiFeatureStatus.onStopped()
            assertFalse(AiFeatureStatus.isVisible())
            assertTrue(AiFeatureStatus.isRunning())
        } finally {
            AiFeatureStatus.onStopped()
            AiFeatureStatus.onDestroyed()
        }
        assertFalse(AiFeatureStatus.isRunning())
    }
}
