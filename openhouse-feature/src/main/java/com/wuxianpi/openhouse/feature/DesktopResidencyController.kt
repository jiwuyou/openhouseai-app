package com.wuxianpi.openhouse.feature

import android.os.Handler
import android.os.Looper
import com.wuxianpi.openhouse.core.DesktopResidencyPolicy

class DesktopResidencyController(
    private val scheduler: Scheduler,
    private val releaseDelayMs: Long = DEFAULT_RELEASE_DELAY_MS,
    private val release: () -> Unit,
) {
    private var scheduled = false
    private val releaseTask = Runnable {
        if (!scheduled) return@Runnable
        scheduled = false
        release()
    }

    fun onLeave(keepResident: Boolean) {
        cancel()
        if (keepResident) return
        scheduled = true
        scheduler.postDelayed(releaseTask, releaseDelayMs)
    }

    fun onReturn() = cancel()

    fun onDestroy() = cancel()

    fun isScheduled(): Boolean = scheduled

    private fun cancel() {
        scheduled = false
        scheduler.remove(releaseTask)
    }

    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long)
        fun remove(task: Runnable)
    }

    class MainThreadScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun postDelayed(task: Runnable, delayMs: Long) {
            handler.postDelayed(task, delayMs)
        }

        override fun remove(task: Runnable) {
            handler.removeCallbacks(task)
        }
    }

    companion object {
        const val DEFAULT_RELEASE_DELAY_MS = DesktopResidencyPolicy.DEFAULT_RELEASE_DELAY_MILLIS
    }
}
