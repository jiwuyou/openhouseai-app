package com.ai.assistance.operit.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PiServiceConnectionTest {
    @Test
    fun `credentials normalize the shared loopback endpoint`() {
        assertEquals(
            "http://127.0.0.1:8765/",
            PiServiceCredentials("http://127.0.0.1:8765", "basic").serviceUrl,
        )
    }

    @Test
    fun `credentials reject a non-loopback endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            PiServiceCredentials("https://example.com", "basic")
        }
    }
}
