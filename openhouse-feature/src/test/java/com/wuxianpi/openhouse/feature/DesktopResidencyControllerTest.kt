package com.wuxianpi.openhouse.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopResidencyControllerTest {
    @Test
    fun leavingSchedulesReleaseAndReturningCancelsIt() {
        val scheduler = FakeScheduler()
        var releases = 0
        val controller = DesktopResidencyController(scheduler) { releases++ }

        controller.onLeave(keepResident = false)
        assertTrue(controller.isScheduled())
        controller.onReturn()
        scheduler.runPending()

        assertFalse(controller.isScheduled())
        assertEquals(0, releases)
    }

    @Test
    fun timeoutReleasesAndKeepResidentDoesNotSchedule() {
        val scheduler = FakeScheduler()
        var releases = 0
        val controller = DesktopResidencyController(scheduler) { releases++ }

        controller.onLeave(keepResident = true)
        assertFalse(controller.isScheduled())

        controller.onLeave(keepResident = false)
        assertEquals(DesktopResidencyController.DEFAULT_RELEASE_DELAY_MS, scheduler.delay)
        scheduler.runPending()
        assertEquals(1, releases)
    }

    private class FakeScheduler : DesktopResidencyController.Scheduler {
        var pending: Runnable? = null
        var delay = -1L
        override fun postDelayed(task: Runnable, delayMs: Long) {
            pending = task
            delay = delayMs
        }

        override fun remove(task: Runnable) {
            if (pending === task) pending = null
        }

        fun runPending() {
            pending.also { pending = null }?.run()
        }
    }
}
