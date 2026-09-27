package com.ai.assistance.operit.ui.features.token.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekPaymentModeTest {
    @Test
    fun recognizesOfficialTopUpUrlWithOptionalQueryAndTrailingSlash() {
        assertTrue(isDeepSeekTopUpPage("https://platform.deepseek.com/top_up"))
        assertTrue(isDeepSeekTopUpPage("https://platform.deepseek.com/top_up/"))
        assertTrue(isDeepSeekTopUpPage("https://platform.deepseek.com/top_up?from=openhouse"))
    }

    @Test
    fun rejectsOtherHostsPathsAndSchemes() {
        assertFalse(isDeepSeekTopUpPage("https://platform.deepseek.com/api_keys"))
        assertFalse(isDeepSeekTopUpPage("https://example.com/top_up"))
        assertFalse(isDeepSeekTopUpPage("http://platform.deepseek.com/top_up"))
        assertFalse(isDeepSeekTopUpPage("not a url"))
    }
}
