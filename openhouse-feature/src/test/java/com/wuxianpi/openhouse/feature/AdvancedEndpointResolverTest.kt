package com.wuxianpi.openhouse.feature

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedEndpointResolverTest {
    @Test
    fun prefersAionUiWhenBothEndpointsAreHealthy() {
        val resolver = AdvancedEndpointResolver { true }
        val result = resolver.resolve(AdvancedUiEndpoints("http://127.0.0.1:25808", "http://127.0.0.1:8765"))
        assertEquals(AdvancedEndpointResolver.Target.AION_UI, result.target)
        assertEquals("http://127.0.0.1:25808/", result.url)
    }

    @Test
    fun fallsBackToAiWebUiWhenAionUiIsUnavailable() {
        val resolver = AdvancedEndpointResolver { url -> url.contains("8765") }
        val result = resolver.resolve(AdvancedUiEndpoints.defaults())
        assertEquals(AdvancedEndpointResolver.Target.AI_WEB_UI, result.target)
    }

    @Test
    fun loadFailureFallbackNeverRetriesAionUi() {
        val resolver = AdvancedEndpointResolver { url -> url.contains("8765") }
        val result = resolver.fallbackAfterLoadFailure(
            AdvancedEndpointResolver.Target.AION_UI,
            AdvancedUiEndpoints.defaults(),
        )
        assertEquals(AdvancedEndpointResolver.Target.AI_WEB_UI, result.target)
    }
}
