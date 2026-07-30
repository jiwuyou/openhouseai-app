package com.ai.assistance.operit.rescue.ui

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.host.control.OperitShutdownController
import com.ai.assistance.operit.R
import com.ai.assistance.operit.rescue.remote.RescueAssistHostPhase
import com.ai.assistance.operit.rescue.remote.RescueRemoteAssistController
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.ui.main.OperitApp
import com.ai.assistance.operit.ui.theme.OperitTheme
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point for the Android-local Rescue AI.
 *
 * This intentionally hosts the complete Operit UI instead of adding a second maintenance
 * dashboard.  The activity lives in its own process, and ChatViewModel uses the process marker to
 * select ChatRuntimeSlot.RESCUE.  The normal WuxianPi/Node UI is not changed.
 */
class RescueActivity : ComponentActivity() {
    private var remoteAssistStopRequested = false

    companion object {
        const val ACTION_OPEN_RESCUE = "com.wuxianpi.action.OPEN_RESCUE_AI"
        const val EXTRA_RESCUE_ENTRY = "com.wuxianpi.extra.RESCUE_ENTRY"
        const val EXTRA_HOST_RETURN_ACTIVITY = MainActivity.EXTRA_HOST_RETURN_ACTIVITY
        const val RESCUE_PROCESS_SUFFIX = ":rescue_ui"
        private const val TAG = "RescueActivity"

        fun createIntent(context: Context): Intent =
            createIntent(context, hostReturnActivity = null)

        fun createIntent(context: Context, hostReturnActivity: String?): Intent =
            Intent(context, RescueActivity::class.java).apply {
                putExtra(EXTRA_RESCUE_ENTRY, true)
                hostReturnActivity?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    putExtra(EXTRA_HOST_RETURN_ACTIVITY, it)
                }
            }

        /** Returns true for an Activity/Context running the Android-local rescue UI. */
        fun isRescueContext(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is RescueActivity) return true
                current = current.baseContext
            }
            if (current is RescueActivity) return true

            val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }
                    ?.processName
            }
            return processName == "${context.packageName}$RESCUE_PROCESS_SUFFIX"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The rescue process must initialize only the shared Operit environment.  Loading the Rust
        // library itself remains lazy and is owned by RescuePiChatEngine on the first turn.
        // Match MainActivity's startup ordering: initialize the shared Operit environment before
        // composing any screen that may access it.  The Rust library remains lazy afterwards.
        setContent {
            OperitTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                OperitApplication.initializeMainApplication(applicationContext)
            }
            setContent {
                OperitTheme {
                    OperitApp(
                        initialNavItem = NavItem.AiChat,
                        toolHandler = AIToolHandler.getInstance(this@RescueActivity),
                        isHostedMode = true,
                        onReturnToHostMainMenu = ::returnToHostMainMenu,
                        onCloseHostedOperit = ::closeRescueAssistant,
                        hostedCloseLabel = getString(R.string.rescue_ai_close),
                    )
                }
            }
        }
    }

    private fun returnToHostMainMenu() {
        val requestedActivity =
            intent?.getStringExtra(EXTRA_HOST_RETURN_ACTIVITY)?.trim().orEmpty()
        val hostIntent =
            if (requestedActivity.isNotEmpty()) {
                Intent().setClassName(packageName, requestedActivity)
            } else {
                packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName)
            }
        hostIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(hostIntent) }
            .onFailure { AppLogger.e(TAG, "Failed to return from Rescue AI to host main activity", it) }
    }

    private fun closeRescueAssistant() {
        stopRemoteAssistanceIfActive()
        OperitShutdownController.shutdownRescueFromActivity(
            activity = this,
            rescueProcessSuffix = RESCUE_PROCESS_SUFFIX,
        )
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            stopRemoteAssistanceIfActive()
        }
        super.onDestroy()
    }

    private fun stopRemoteAssistanceIfActive() {
        if (remoteAssistStopRequested) return
        val remoteAssistController = RescueRemoteAssistController.getInstance(this)
        if (remoteAssistController.state.value.phase != RescueAssistHostPhase.IDLE) {
            remoteAssistStopRequested = true
            remoteAssistController.stopSharing()
        }
    }
}
