package com.wuxianpi.openhouse.feature

import java.net.HttpURLConnection
import java.net.URL

class AdvancedEndpointResolver(
    private val probe: EndpointProbe = HttpEndpointProbe(),
) {
    fun resolve(endpoints: AdvancedUiEndpoints): Resolution {
        val aion = normalize(endpoints.aionUiUrl)
        val aiWeb = normalize(endpoints.aiWebUiUrl)
        if (aion.isNotEmpty() && probe.isReachable(aion)) {
            return Resolution(Target.AION_UI, aion)
        }
        if (aiWeb.isNotEmpty() && probe.isReachable(aiWeb)) {
            return Resolution(Target.AI_WEB_UI, aiWeb)
        }
        return Resolution(Target.UNAVAILABLE, "")
    }

    fun fallbackAfterLoadFailure(
        failedTarget: Target,
        endpoints: AdvancedUiEndpoints,
    ): Resolution {
        if (failedTarget != Target.AION_UI) return Resolution(Target.UNAVAILABLE, "")
        val aiWeb = normalize(endpoints.aiWebUiUrl)
        return if (aiWeb.isNotEmpty() && probe.isReachable(aiWeb)) {
            Resolution(Target.AI_WEB_UI, aiWeb)
        } else {
            Resolution(Target.UNAVAILABLE, "")
        }
    }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
    }

    fun interface EndpointProbe {
        fun isReachable(url: String): Boolean
    }

    class HttpEndpointProbe : EndpointProbe {
        override fun isReachable(url: String): Boolean {
            var connection: HttpURLConnection? = null
            return try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 1_200
                connection.readTimeout = 1_500
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.responseCode in 200..399
            } catch (_: Exception) {
                false
            } finally {
                connection?.disconnect()
            }
        }
    }

    enum class Target { AION_UI, AI_WEB_UI, UNAVAILABLE }

    data class Resolution(val target: Target, val url: String)
}
