package com.wuxianpi.openhouse.feature.workspace

import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination

/** Owns navigation generation so stale asynchronous endpoint results cannot replace a newer page. */
class WorkspaceNavigator(
    initialDestination: WorkspaceDestination = WorkspaceDestination.Desktop,
) {
    var current: WorkspaceDestination = initialDestination
        private set

    var generation: Long = 0
        private set

    fun navigate(destination: WorkspaceDestination): Transition {
        val transition = Transition(current, destination, ++generation)
        current = destination
        return transition
    }

    fun invalidatePendingResults(): Long = ++generation

    fun isCurrent(expectedGeneration: Long, destination: WorkspaceDestination): Boolean =
        generation == expectedGeneration && current == destination

    data class Transition(
        val previous: WorkspaceDestination,
        val current: WorkspaceDestination,
        val generation: Long,
    )
}
