package com.wuxianpi.openhouse.feature.workspace

import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNavigatorTest {
    @Test
    fun componentIdentityUsesNormalizedId() {
        assertEquals(
            WorkspaceDestination.Component(" My App "),
            WorkspaceDestination.Component("my-app"),
        )
    }

    @Test
    fun newerNavigationInvalidatesOlderAsyncResult() {
        val navigator = WorkspaceNavigator()
        val first = navigator.navigate(WorkspaceDestination.Component("first"))
        val secondDestination = WorkspaceDestination.Route(ProductRoute.BASIC)
        val second = navigator.navigate(secondDestination)

        assertFalse(navigator.isCurrent(first.generation, first.current))
        assertTrue(navigator.isCurrent(second.generation, secondDestination))
    }

    @Test
    fun invalidatePendingResultsKeepsCurrentDestination() {
        val navigator = WorkspaceNavigator()
        val destination = WorkspaceDestination.Component("manual")
        val transition = navigator.navigate(destination)

        navigator.invalidatePendingResults()

        assertFalse(navigator.isCurrent(transition.generation, destination))
        assertTrue(navigator.current == destination)
    }

    @Test
    fun webMountGateOnlyCompletesTheCurrentResolution() {
        val gate = WorkspaceWebMountGate()
        gate.begin(4)
        gate.begin(5)

        assertFalse(gate.complete(4))
        assertTrue(gate.isPending)
        assertTrue(gate.complete(5))
        assertFalse(gate.isPending)
    }

    @Test
    fun pendingWebBackCancelsResolutionBeforeHistoryCanBeUsed() {
        val gate = WorkspaceWebMountGate()
        gate.begin(8)

        assertTrue(gate.cancelPendingForBack())
        assertFalse(gate.isPending)
        assertFalse(gate.cancelPendingForBack())
    }
}
