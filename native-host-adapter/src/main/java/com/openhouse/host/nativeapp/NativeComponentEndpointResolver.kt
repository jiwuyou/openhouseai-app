package com.openhouse.host.nativeapp

import com.wuxianpi.openhouse.core.RuntimeConnection
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.service.HttpRequestSpec
import com.wuxianpi.openhouse.core.service.HttpTransport
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import com.wuxianpi.openhouse.core.service.UrlConnectionHttpTransport
import org.json.JSONObject
import java.net.URI

internal sealed interface NativeComponentEndpointResult {
    data class Resolved(
        val url: String,
        val serviceId: String?,
    ) : NativeComponentEndpointResult

    data class Unavailable(val message: String) : NativeComponentEndpointResult
}

internal data class NativeServiceEndpointStatus(
    val success: Boolean,
    val url: String,
    val message: String,
)

internal class NativeComponentEndpointResolver(
    private val lookupEndpoint: (String) -> NativeServiceEndpointStatus,
) {
    fun resolve(component: OpenHouseComponent): NativeComponentEndpointResult {
        val serviceIds = serviceIdsFor(component)
        if (serviceIds.isEmpty()) {
            return if (component.url.isHttpUrl()) {
                NativeComponentEndpointResult.Resolved(component.url, null)
            } else {
                NativeComponentEndpointResult.Unavailable(
                    "Component ${component.id} does not define a valid Web URL",
                )
            }
        }

        val failures = mutableListOf<String>()
        serviceIds.forEach { serviceId ->
            val status = runCatching { lookupEndpoint(serviceId) }.getOrElse { error ->
                failures += "$serviceId: ${error.message ?: error.javaClass.simpleName}"
                return@forEach
            }
            if (status.success && status.url.isHttpUrl()) {
                return NativeComponentEndpointResult.Resolved(status.url, serviceId)
            }
            failures += "$serviceId: ${status.message.ifBlank { "no published endpoint" }}"
        }

        val detail = failures.joinToString("; ").ifBlank { "no published endpoint" }
        return NativeComponentEndpointResult.Unavailable(
            "Component ${component.id} services are unavailable: $detail",
        )
    }

    companion object {
        fun fromRuntimeConnection(
            runtimeConnection: () -> RuntimeConnection,
            transport: HttpTransport = UrlConnectionHttpTransport(),
        ): NativeComponentEndpointResolver = NativeComponentEndpointResolver { serviceId ->
            queryServiceEndpoints(runtimeConnection(), transport, serviceId)
        }
    }
}

private fun queryServiceEndpoints(
    runtimeConnection: RuntimeConnection,
    transport: HttpTransport,
    serviceId: String,
): NativeServiceEndpointStatus {
    val sanitizedId = ServiceManagerClient.sanitizeServiceId(serviceId)
    if (sanitizedId.isEmpty()) {
        return NativeServiceEndpointStatus(false, "", "invalid service id")
    }
    return runCatching {
        val response = transport.execute(
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
            return@runCatching NativeServiceEndpointStatus(
                false,
                "",
                response.body.ifBlank { "endpoint query failed with HTTP ${response.code}" },
            )
        }
        val root = JSONObject(response.body)
        val endpoints = root.optJSONArray("endpoints")
        val selectedUrl = endpoints
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull(array::optJSONObject)
                    .sortedBy(::endpointPriority)
                    .firstNotNullOfOrNull(::endpointUrl)
            }
            .orEmpty()
        if (selectedUrl.isNotEmpty()) {
            NativeServiceEndpointStatus(true, selectedUrl, root.optString("status", "ready"))
        } else {
            NativeServiceEndpointStatus(
                false,
                "",
                "service endpoint is ${root.optString("status", "unavailable")}",
            )
        }
    }.getOrElse { error ->
        NativeServiceEndpointStatus(false, "", error.message ?: error.javaClass.simpleName)
    }
}

private fun endpointPriority(endpoint: JSONObject): Int = when (endpoint.optString("name").trim().lowercase()) {
    "api" -> 0
    "runtime" -> 1
    "web" -> 2
    else -> 3
}

private fun endpointUrl(endpoint: JSONObject): String? {
    normalizeHttpUrl(endpoint.optString("url"))?.let { return it }
    val port = endpoint.optInt("port", 0)
    if (port !in 1..65535) return null
    val protocol = endpoint.optString("protocol").trim().lowercase()
    if (protocol == "udp") return null
    val scheme = if (protocol == "https") "https" else "http"
    val rawHost = endpoint.optString("host").trim()
    if (rawHost.isEmpty()) return null
    val host = when (rawHost) {
        "0.0.0.0", "::", "[::]" -> "127.0.0.1"
        else -> rawHost
    }
    val renderedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    return normalizeHttpUrl("$scheme://$renderedHost:$port")
}

internal fun serviceIdsFor(component: OpenHouseComponent): List<String> {
    return serviceIdsFor(component.serviceNames, component.serviceRefs)
}

internal fun serviceIdsFor(
    serviceNames: List<String>,
    serviceRefs: List<String>,
): List<String> {
    val referencedIds = serviceRefs.mapNotNull { reference ->
        ServiceManagerClient.parseServiceManagerRef(reference)
            .takeIf { it.valid }
            ?.serviceId
    }
    return (serviceNames + referencedIds)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

private fun String.isHttpUrl(): Boolean =
    normalizeHttpUrl(this) != null

private fun normalizeHttpUrl(value: String): String? = runCatching {
    val uri = URI(value.trim())
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https")
    require(!uri.host.isNullOrBlank())
    uri.toString()
}.getOrNull()
