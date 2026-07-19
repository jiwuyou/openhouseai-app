package com.wuxianpi.pi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelConfigTest {
    @Test
    fun `status parses dynamic providers models and default`() {
        val status = PiModelStatus.from(
            JSONObject(
                """{"providers":[{"id":"p","name":"Provider","authenticated":true,"authSource":"settings"}],"models":[{"provider":"p","id":"m","name":"Model","available":true,"reasoning":true,"input":["text","image"],"contextWindow":200000,"maxTokens":8192}],"defaultModel":{"provider":"p","modelId":"m"}}""",
            ),
        )
        assertEquals("Provider", status.providers.single().name)
        assertEquals(listOf("text", "image"), status.models.single().input)
        assertEquals(200000L, status.models.single().contextWindow)
        assertTrue(status.hasUsableDefault)
    }

    @Test
    fun `unavailable or unauthenticated default is not usable`() {
        val status = PiModelStatus.from(
            JSONObject(
                """{"providers":[{"id":"p","authenticated":false}],"models":[{"provider":"p","id":"m","available":true}],"defaultModel":{"provider":"p","modelId":"m"},"availabilityError":"login required"}""",
            ),
        )
        assertFalse(status.hasUsableDefault)
        assertEquals("login required", status.availabilityError)
    }

    @Test
    fun `missing optional fields are safe and secrets are never modeled`() {
        val status = PiModelStatus.from(JSONObject("""{"providers":[],"models":[],"defaultModel":null}"""))
        assertTrue(status.providers.isEmpty())
        assertTrue(status.models.isEmpty())
        assertNull(status.defaultModel)
        assertTrue(PiProviderStatus::class.java.declaredFields.none { it.name.contains("key", ignoreCase = true) })
    }
}
