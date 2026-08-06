package com.wuxianpi.openhouse.core.workspace

import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import java.net.URI
import java.util.Locale

/** Result of resolving a registry component to a URL that is safe to embed. */
sealed class ComponentWebResolution {
    class Resolved(
        url: String,
        val serviceId: String? = null,
    ) : ComponentWebResolution() {
        val url: String = requireNotNull(HttpUrlNormalizer.normalize(url)) {
            "Resolved component URL must be a valid http/https URL"
        }
    }

    data class Unavailable(
        val message: String = "",
    ) : ComponentWebResolution()

    /** Keeps older host adapters working until they implement in-workspace resolution. */
    data object DelegateToHost : ComponentWebResolution()

    companion object {
        @JvmStatic
        fun resolved(url: String, serviceId: String? = null): ComponentWebResolution {
            val normalized = HttpUrlNormalizer.normalize(url)
                ?: return Unavailable("Component does not define a valid http/https URL")
            return Resolved(normalized, serviceId?.trim()?.ifEmpty { null })
        }
    }
}

/** Synchronous resolver contract; hosts may schedule it off the main thread. */
fun interface ComponentEndpointResolver {
    fun resolve(component: OpenHouseComponent): ComponentWebResolution
}

data class ServiceEndpointStatus(
    val success: Boolean,
    val url: String = "",
    val message: String = "",
)

data class ComponentEndpointRetryPolicy(
    val attemptsAfterStart: Int = 5,
    val delayMillis: Long = 200,
) {
    init {
        require(attemptsAfterStart >= 0)
        require(delayMillis >= 0)
    }
}

/**
 * Reusable service-backed component algorithm. Hosts only provide endpoint I/O and service start.
 * Callers must run this resolver away from the Android main thread when lookup can block.
 */
class ServiceBackedComponentEndpointResolver(
    private val lookupEndpoint: (String) -> ServiceEndpointStatus,
    private val startService: (String) -> Boolean = { false },
    private val retryPolicy: ComponentEndpointRetryPolicy = ComponentEndpointRetryPolicy(),
    private val waitBeforeRetry: (Long) -> Unit = { delay -> if (delay > 0) Thread.sleep(delay) },
) : ComponentEndpointResolver {
    override fun resolve(component: OpenHouseComponent): ComponentWebResolution {
        val serviceIds = serviceIdsFor(component)
        if (serviceIds.isEmpty()) return ComponentWebResolution.resolved(component.url)

        val failures = mutableListOf<String>()
        serviceIds.forEach { serviceId ->
            var status = lookup(serviceId, failures)
            normalizedUrl(status)?.let { url ->
                return ComponentWebResolution.Resolved(url, serviceId)
            }

            val initialMessage = status?.message?.ifBlank { "no published endpoint" }
                ?: "endpoint lookup failed"
            val started = runCatching { startService(serviceId) }.getOrDefault(false)
            if (!started) {
                failures += "$serviceId: $initialMessage"
                return@forEach
            }

            repeat(retryPolicy.attemptsAfterStart) { attempt ->
                if (attempt > 0) waitBeforeRetry(retryPolicy.delayMillis)
                status = lookup(serviceId, failures = null)
                normalizedUrl(status)?.let { url ->
                    return ComponentWebResolution.Resolved(url, serviceId)
                }
            }
            failures += "$serviceId: started but ${status?.message?.ifBlank { "endpoint is still unavailable" } ?: "endpoint is still unavailable"}"
        }

        val detail = failures.joinToString("; ").ifBlank { "no published endpoint" }
        return ComponentWebResolution.Unavailable(
            "Component ${component.id} services are unavailable: $detail",
        )
    }

    private fun lookup(
        serviceId: String,
        failures: MutableList<String>?,
    ): ServiceEndpointStatus? = runCatching { lookupEndpoint(serviceId) }
        .onFailure { error ->
            failures?.add("$serviceId: ${error.message ?: error.javaClass.simpleName}")
        }
        .getOrNull()

    private fun normalizedUrl(status: ServiceEndpointStatus?): String? {
        if (status?.success != true) return null
        return HttpUrlNormalizer.normalize(status.url)
    }

    companion object {
        @JvmStatic
        fun serviceIdsFor(component: OpenHouseComponent): List<String> =
            serviceIdsFor(component.serviceNames, component.serviceRefs)

        @JvmStatic
        fun serviceIdsFor(
            serviceNames: List<String>?,
            serviceRefs: List<String>?,
        ): List<String> {
            val referencedIds = serviceRefs.orEmpty().mapNotNull { reference ->
                ServiceManagerClient.parseServiceManagerRef(reference)
                    .takeIf { it.valid }
                    ?.serviceId
            }
            return (serviceNames.orEmpty() + referencedIds)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        }
    }
}

object HttpUrlNormalizer {
    @JvmStatic
    fun normalize(value: String?): String? = runCatching {
        val input = value.orEmpty().trim()
        require(input.isNotEmpty())
        val parsed = URI(input).normalize()
        val scheme = parsed.scheme?.lowercase(Locale.US)
        require(scheme == "http" || scheme == "https")
        require(parsed.userInfo == null)
        val host = parsed.host?.lowercase(Locale.US)
        require(!host.isNullOrBlank())
        parsed.toASCIIString()
    }.getOrNull()
}
