package com.ai.assistance.operit.rescue.pi

import org.junit.Assert.assertEquals
import org.junit.Test

class RescueImageCapabilityTest {
    @Test
    fun imageInputIsSupported() {
        assertEquals(
            RescueImageCapability.SUPPORTED,
            rescueImageCapabilityForInput(listOf("text", "image")),
        )
    }

    @Test
    fun declaredTextOnlyInputIsUnsupported() {
        assertEquals(
            RescueImageCapability.UNSUPPORTED,
            rescueImageCapabilityForInput(listOf("text")),
        )
    }

    @Test
    fun missingInputMetadataIsUnknown() {
        assertEquals(RescueImageCapability.UNKNOWN, rescueImageCapabilityForInput(emptyList()))
    }
}
