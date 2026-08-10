package com.ai.assistance.operit.rescue.plugins

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueFirstInstallCoordinatorTest {
    @Test
    fun prewarmAndDirectCallShareOnePreparation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator =
            FirstInstallPreparationCoordinator(
                scope = scope,
                timeoutMillis = 5_000,
                onTimeout = { "timeout" },
                prepare = {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    "prepared"
                },
            )

        coordinator.prewarm()
        started.await()
        // Both callers must acquire the in-flight preparation before it completes.
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.awaitPreparation() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.awaitPreparation() }
        release.complete(Unit)

        assertEquals("prepared", first.await())
        assertEquals("prepared", second.await())
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun preparationTimeoutReturnsAvailableFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator =
            FirstInstallPreparationCoordinator(
                scope = scope,
                timeoutMillis = 25,
                onTimeout = { "available" },
                prepare = {
                    delay(5_000)
                    "updated"
                },
            )

        assertEquals("available", coordinator.awaitPreparation())
        scope.cancel()
    }

    @Test
    fun completedPreparationDoesNotSuppressTheNextMarketCheck() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val coordinator =
            FirstInstallPreparationCoordinator(
                scope = scope,
                timeoutMillis = 5_000,
                onTimeout = { -1 },
                prepare = { calls.incrementAndGet() },
            )

        assertEquals(1, coordinator.awaitPreparation())
        assertEquals(2, coordinator.awaitPreparation())
        assertEquals(2, calls.get())
        scope.cancel()
    }

    @Test
    fun timedOutPreparationCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val coordinator =
            FirstInstallPreparationCoordinator(
                scope = scope,
                timeoutMillis = 25,
                onTimeout = { "available" },
                prepare = {
                    if (calls.incrementAndGet() == 1) delay(5_000)
                    "updated"
                },
            )

        assertEquals("available", coordinator.awaitPreparation())
        assertEquals("updated", coordinator.awaitPreparation())
        assertEquals(2, calls.get())
        scope.cancel()
    }

    @Test
    fun failedPreparationCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val coordinator =
            FirstInstallPreparationCoordinator(
                scope = scope,
                timeoutMillis = 5_000,
                onTimeout = { "available" },
                prepare = {
                    if (calls.incrementAndGet() == 1) error("temporary failure")
                    "updated"
                },
            )

        try {
            coordinator.awaitPreparation()
            fail("The first preparation should fail")
        } catch (expected: IllegalStateException) {
            assertEquals("temporary failure", expected.message)
        }
        assertEquals("updated", coordinator.awaitPreparation())
        assertEquals(2, calls.get())
        scope.cancel()
    }

    @Test
    fun invalidUpdatedWorkflowRollsBackAndKeepsReadyStatus() = runBlocking {
        val starts = AtomicInteger()
        val rollbacks = AtomicInteger()
        val restores = AtomicInteger()

        val result =
            startFirstInstallWorkflowWithFallback(
                preparation = preparation(updated = true),
                start = {
                    if (starts.incrementAndGet() == 1) error("invalid updated workflow")
                    JSONObject().put("status", "ready")
                },
                rollback = {
                    rollbacks.incrementAndGet()
                    true
                },
                restoreBundled = { restores.incrementAndGet() },
            )

        assertEquals("ready", result.getString("status"))
        val metadata = result.getJSONObject("firstInstallPreparation")
        assertEquals("previous", metadata.getString("workflowSource"))
        assertTrue(metadata.getBoolean("fallbackUsed"))
        assertEquals(1, metadata.getJSONArray("fallbackFailures").length())
        assertEquals(1, rollbacks.get())
        assertEquals(0, restores.get())
    }

    @Test
    fun previousWorkflowFailureRestoresBundledWorkflow() = runBlocking {
        val starts = AtomicInteger()
        val restores = AtomicInteger()

        val result =
            startFirstInstallWorkflowWithFallback(
                preparation = preparation(updated = true),
                start = {
                    if (starts.incrementAndGet() < 3) error("workflow is unreadable")
                    JSONObject().put("status", "ready")
                },
                rollback = { true },
                restoreBundled = { restores.incrementAndGet() },
            )

        assertEquals("ready", result.getString("status"))
        val metadata = result.getJSONObject("firstInstallPreparation")
        assertEquals("bundled", metadata.getString("workflowSource"))
        assertEquals(2, metadata.getJSONArray("fallbackFailures").length())
        assertEquals(1, restores.get())
        assertTrue(metadata.getBoolean("updated"))
        assertFalse(metadata.isNull("activeVersion"))
    }

    @Test
    fun previousVersionIsTriedEvenWhenThisPreparationDidNotUpdate() = runBlocking {
        val starts = AtomicInteger()
        val rollbacks = AtomicInteger()
        val restores = AtomicInteger()

        val result =
            startFirstInstallWorkflowWithFallback(
                preparation = preparation(updated = false),
                start = {
                    if (starts.incrementAndGet() == 1) error("bad update from an earlier process")
                    JSONObject().put("status", "ready")
                },
                rollback = {
                    rollbacks.incrementAndGet()
                    true
                },
                restoreBundled = { restores.incrementAndGet() },
            )

        assertEquals("ready", result.getString("status"))
        assertEquals(
            "previous",
            result.getJSONObject("firstInstallPreparation").getString("workflowSource"),
        )
        assertEquals(1, rollbacks.get())
        assertEquals(0, restores.get())
    }

    private fun preparation(updated: Boolean): FirstInstallPreparation =
        FirstInstallPreparation(
            initialVersion = "1.0.0",
            activeVersion = if (updated) "1.0.1" else "1.0.0",
            updateStatus = if (updated) "updated" else "current_or_offline",
            updated = updated,
        )
}
