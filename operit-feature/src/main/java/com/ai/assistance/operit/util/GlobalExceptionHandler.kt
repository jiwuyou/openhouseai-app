package com.ai.assistance.operit.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.assistance.operit.ui.error.CrashReportActivity

class GlobalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private companion object {
        private const val TAG = "GlobalExceptionHandler"
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        CrashRecoveryState.markPendingCrashReportLaunch(context)
        val stackTrace = ThrowableTextFormatter.format(ex)

        try {
            val intent =
                    Intent(context, CrashReportActivity::class.java).apply {
                        putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
            context.startActivity(intent)
        } catch (launchError: Throwable) {
            Log.e(TAG, "Failed to launch crash report activity", launchError)
        }

        Log.e(TAG, "Uncaught exception captured from ${thread.name}; host process was not terminated.", ex)
    }
}
