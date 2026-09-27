package com.ai.assistance.operit.ui.features.token.payment

import com.ai.assistance.operit.ui.features.token.network.DeepseekApiConstants
import java.net.URI

enum class DeepSeekPaymentMode {
    MOBILE,
    DESKTOP,
}

fun isDeepSeekTopUpPage(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(DeepseekApiConstants.DEEPSEEK_PLATFORM_DOMAIN, ignoreCase = true) &&
        uri.path.orEmpty().trimEnd('/') == "/top_up"
}
