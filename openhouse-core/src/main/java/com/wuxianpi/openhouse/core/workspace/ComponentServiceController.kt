package com.wuxianpi.openhouse.core.workspace

import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.service.ServiceAction
import com.wuxianpi.openhouse.core.service.ServiceManagerClient
import com.wuxianpi.openhouse.core.service.ServiceManagerService

enum class ComponentServiceState {
    NONE,
    UNKNOWN,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
    MIXED,
    ;

    fun isTransitioning(): Boolean = this == STARTING || this == STOPPING
}

data class ComponentServiceSummary(
    val componentId: String,
    val serviceIds: List<String>,
    val state: ComponentServiceState,
    val message: String = "",
) {
    val hasServices: Boolean get() = serviceIds.isNotEmpty()
}

data class ComponentServiceActionResult(
    val success: Boolean,
    val componentId: String,
    val running: Boolean,
    val message: String = "",
)

/** Synchronous service facade. Android hosts schedule calls away from the main thread. */
class ComponentServiceController(
    private val clientProvider: () -> ServiceManagerClient,
) {
    fun load(components: List<OpenHouseComponent>): Map<String, ComponentServiceSummary> {
        val client = clientProvider()
        val requestedIds = components
            .flatMap(ServiceBackedComponentEndpointResolver::serviceIdsFor)
            .distinct()
        val states = loadStates(client, requestedIds)
        return components.associate { component ->
            val serviceIds = ServiceBackedComponentEndpointResolver.serviceIdsFor(component)
            val serviceStates = serviceIds.map { states[it].orEmpty() }
            component.id to ComponentServiceSummary(
                componentId = component.id,
                serviceIds = serviceIds,
                state = aggregate(serviceIds, serviceStates),
                message = serviceIds.zip(serviceStates)
                    .filter { (_, state) -> normalize(state) == ComponentServiceState.UNKNOWN }
                    .joinToString("; ") { (id, _) -> "$id: unknown" },
            )
        }
    }

    fun setRunning(component: OpenHouseComponent, running: Boolean): ComponentServiceActionResult {
        val ids = ServiceBackedComponentEndpointResolver.serviceIdsFor(component)
        if (ids.isEmpty()) {
            return ComponentServiceActionResult(false, component.id, running, "component has no managed services")
        }
        val action = if (running) ServiceAction.START else ServiceAction.STOP
        val ordered = if (running) ids else ids.asReversed()
        val failures = ordered.mapNotNull { serviceId ->
            val result = clientProvider().runAction(serviceId, action)
            if (result.success) null else "$serviceId: ${result.message.ifBlank { "action failed" }}"
        }
        return ComponentServiceActionResult(
            success = failures.isEmpty(),
            componentId = component.id,
            running = running,
            message = failures.joinToString("; ").ifBlank {
                if (running) "services started" else "services stopped"
            },
        )
    }

    private fun loadStates(client: ServiceManagerClient, requestedIds: List<String>): Map<String, String> {
        if (requestedIds.isEmpty()) return emptyMap()
        val states = linkedMapOf<String, String>()
        val bulk = client.listServiceStatuses()
        if (bulk.success) {
            bulk.services.forEach { service ->
                addAliases(states, service)
            }
        }
        requestedIds.filterNot(states::containsKey).forEach { serviceId ->
            val status = client.getStatus(serviceId)
            states[serviceId] = if (status.success) status.state else "unknown"
        }
        return states
    }

    private fun addAliases(output: MutableMap<String, String>, service: ServiceManagerService) {
        val state = service.state.ifBlank { "unknown" }
        service.id.takeIf(String::isNotBlank)?.let { output[it] = state }
        service.name.takeIf(String::isNotBlank)?.let { output.putIfAbsent(it, state) }
    }

    private fun aggregate(ids: List<String>, rawStates: List<String>): ComponentServiceState {
        if (ids.isEmpty()) return ComponentServiceState.NONE
        val states = rawStates.map(::normalize)
        if (states.all { it == ComponentServiceState.RUNNING }) return ComponentServiceState.RUNNING
        if (states.all { it == ComponentServiceState.STOPPED }) return ComponentServiceState.STOPPED
        if (states.all { it == ComponentServiceState.UNKNOWN }) return ComponentServiceState.UNKNOWN
        if (states.any { it == ComponentServiceState.FAILED }) return ComponentServiceState.FAILED
        if (states.all { it == ComponentServiceState.STARTING }) return ComponentServiceState.STARTING
        if (states.all { it == ComponentServiceState.STOPPING }) return ComponentServiceState.STOPPING
        return ComponentServiceState.MIXED
    }

    private fun normalize(value: String): ComponentServiceState = when (value.trim().lowercase()) {
        "running" -> ComponentServiceState.RUNNING
        "stopped" -> ComponentServiceState.STOPPED
        "starting" -> ComponentServiceState.STARTING
        "stopping" -> ComponentServiceState.STOPPING
        "failed" -> ComponentServiceState.FAILED
        else -> ComponentServiceState.UNKNOWN
    }
}
