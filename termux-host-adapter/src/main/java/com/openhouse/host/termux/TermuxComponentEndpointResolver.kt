package com.openhouse.host.termux

import com.wuxianpi.openhouse.core.RuntimeConnection
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.service.HttpRequestSpec
import com.wuxianpi.openhouse.core.service.HttpTransport
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import com.wuxianpi.openhouse.core.service.UrlConnectionHttpTransport
import com.wuxianpi.openhouse.core.workspace.ComponentWebResolution
import com.wuxianpi.openhouse.core.workspace.ServiceBackedComponentEndpointResolver
import com.wuxianpi.openhouse.core.workspace.ServiceEndpointStatus
import org.json.JSONObject

/** Host I/O adapter for the shared service-backed component resolution algorithm. */
internal class TermuxComponentEndpointResolver private constructor(
    private val delegate: ServiceBackedComponentEndpointResolver,
) {
    fun resolve(component: OpenHouseComponent): ComponentWebResolution = delegate.resolve(component)

    companion object {
        fun fromRuntimeConnection(
            runtimeConnection: () -> RuntimeConnection,
            transport: HttpTransport = UrlConnectionHttpTransport(),
        ): TermuxComponentEndpointResolver =
            TermuxComponentEndpointResolver(
                ServiceBackedComponentEndpointResolver(
                    lookupEndpoint = { serviceId ->
                        queryTermuxServiceEndpoint(runtimeConnection(), transport, serviceId)
                    },
                    startService = { serviceId ->
                        ServiceManagerClient(runtimeConnection(), transport)
                            .runAction(serviceId, ServiceAction.START)
                            .success
                    },
                )
            )
    }
}

internal fun queryTermuxServiceEndpoint(
    runtimeConnection: RuntimeConnection,
    transport: HttpTransport,
    serviceId: String,
): ServiceEndpointStatus {
    val sanitizedId = ServiceManagerClient.sanitizeServiceId(serviceId)
    if (sanitizedId.isEmpty()) return ServiceEndpointStatus(false, message = "invalid service id")

    return runCatching {
        val response =
            transport.execute(
                runtimeConnection,
                HttpRequestSpec(
                    "GET",
                    "/api/v1/services/$sanitizedId/endpoints",
                    true,
                    2500,
                    7000,
                ),
            )
        if (!response.isSuccess) {
            return@runCatching ServiceEndpointStatus(
                success = false,
                message = response.body.ifBlank { "endpoint query failed with HTTP ${response.code}" },
            )
        }

        val root = JSONObject(response.body)
        val endpoints = root.optJSONArray("endpoints")
        val selectedUrl =
            endpoints
                ?.let { array ->
                    (0 until array.length())
                        .mapNotNull(array::optJSONObject)
                        .sortedBy(::termuxEndpointPriority)
                        .firstNotNullOfOrNull(::termuxEndpointUrl)
                }
                .orEmpty()
        if (selectedUrl.isNotEmpty()) {
            ServiceEndpointStatus(true, selectedUrl, root.optString("status", "ready"))
        } else {
            ServiceEndpointStatus(
                success = false,
                message = "service endpoint is ${root.optString("status", "unavailable")}",
            )
        }
    }.getOrElse { error ->
        ServiceEndpointStatus(false, message = error.message ?: error.javaClass.simpleName)
    }
}

private fun termuxEndpointPriority(endpoint: JSONObject): Int =
    when (endpoint.optString("name").trim().lowercase()) {
        "api" -> 0
        "runtime" -> 1
        "web" -> 2
        else -> 3
    }

private fun termuxEndpointUrl(endpoint: JSONObject): String? {
    com.wuxianpi.openhouse.core.workspace.HttpUrlNormalizer.normalize(endpoint.optString("url"))
        ?.let { return it }
    val port = endpoint.optInt("port", 0)
    if (port !in 1..65535) return null
    if (endpoint.optString("protocol").trim().equals("udp", ignoreCase = true)) return null
    val scheme = if (endpoint.optString("protocol").trim().equals("https", ignoreCase = true)) {
        "https"
    } else {
        "http"
    }
    val rawHost = endpoint.optString("host").trim()
    if (rawHost.isEmpty()) return null
    val host = when (rawHost) {
        "0.0.0.0", "::", "[::]" -> "127.0.0.1"
        else -> rawHost
    }
    val renderedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    return com.wuxianpi.openhouse.core.workspace.HttpUrlNormalizer.normalize(
        "$scheme://$renderedHost:$port"
    )
}
