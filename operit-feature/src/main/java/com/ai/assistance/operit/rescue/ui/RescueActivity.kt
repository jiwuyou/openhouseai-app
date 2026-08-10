package com.ai.assistance.operit.rescue.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
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
import com.wuxianpi.openhouse.core.rescue.RescueControlProtocol
import com.wuxianpi.openhouse.core.rescue.RescueControlStateStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.rescue.remote.RescueAssistHostPhase
import com.ai.assistance.operit.rescue.remote.RescueRemoteAssistController
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.ui.main.OperitHostMode
import com.ai.assistance.operit.ui.theme.OperitTheme
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.workspace.OperitWorkspaceContentFactory
import com.ai.assistance.operit.workspace.OperitWorkspaceSpec
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
    private var rescueShutdownReceiverRegistered = false

    private val rescueShutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RescueControlProtocol.ACTION_REQUEST_SHUTDOWN) return
            closeRescueAssistant()
        }
    }

    companion object {
        const val ACTION_OPEN_RESCUE = "com.wuxianpi.action.OPEN_RESCUE_AI"
        const val EXTRA_RESCUE_ENTRY = "com.wuxianpi.extra.RESCUE_ENTRY"
        const val EXTRA_HOST_RETURN_ACTIVITY = MainActivity.EXTRA_HOST_RETURN_ACTIVITY
        const val EXTRA_HOST_RETURN_INTENT = "com.wuxianpi.extra.RESCUE_HOST_RETURN_INTENT"
        const val EXTRA_PENDING_ACTION_ID = "com.wuxianpi.extra.RESCUE_ACTION_ID"
        const val EXTRA_PENDING_ACTION_PROMPT = "com.wuxianpi.extra.RESCUE_ACTION_PROMPT"
        const val RESOURCE_UPDATE_PROMPT =
            "请检查 APK、Termux 和维修助手市场中的资源更新；先完成官方插件统一更新，再读取最新版资源更新插件说明，比较本地状态，只处理缺失、损坏或 SHA 不一致的资源。"
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
        acceptPendingAction(intent)
        registerRescueShutdownReceiver()
        OperitApplication.initializeUiProcess(applicationContext)

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
                OperitWorkspaceContentFactory.Content(
                    OperitWorkspaceSpec(
                        hostMode = OperitHostMode.RESCUE,
                        initialNavItem = NavItem.AiChat,
                        toolHandler = AIToolHandler.getInstance(this@RescueActivity),
                        onReturnToHostMainMenu = ::returnToHostMainMenu,
                        onCloseHostedOperit = ::closeRescueAssistant,
                        hostedCloseLabel = getString(R.string.rescue_ai_close),
                    )
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPendingAction(intent)
    }

    override fun onResume() {
        super.onResume()
        RescueControlStateStore.markForeground(applicationContext)
    }

    override fun onPause() {
        if (!isFinishing && !OperitShutdownController.isShutdownInProgress()) {
            RescueControlStateStore.markBackground(applicationContext)
        }
        super.onPause()
    }

    private fun returnToHostMainMenu() {
        val requestedActivity =
            intent?.getStringExtra(EXTRA_HOST_RETURN_ACTIVITY)?.trim().orEmpty()
        @Suppress("DEPRECATION")
        val suppliedIntent = intent?.getParcelableExtra<Intent>(EXTRA_HOST_RETURN_INTENT)
        val hostIntent = suppliedIntent ?: if (requestedActivity.isNotEmpty()) {
            Intent().setClassName(packageName, requestedActivity)
        } else {
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName)
        }
        hostIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching { startActivity(hostIntent) }
            .onFailure { AppLogger.e(TAG, "Failed to return from Rescue AI to host main activity", it) }
    }

    private fun closeRescueAssistant() {
        RescueControlStateStore.markStopping(applicationContext)
        stopRemoteAssistanceIfActive()
        OperitShutdownController.shutdownRescueFromActivity(
            activity = this,
            rescueProcessSuffix = RESCUE_PROCESS_SUFFIX,
            beforeFinish = { RescueControlStateStore.markStopped(applicationContext) },
        )
    }

    override fun onDestroy() {
        if (rescueShutdownReceiverRegistered) {
            runCatching { unregisterReceiver(rescueShutdownReceiver) }
            rescueShutdownReceiverRegistered = false
        }
        if (isFinishing && !isChangingConfigurations) {
            stopRemoteAssistanceIfActive()
        }
        super.onDestroy()
    }

    private fun registerRescueShutdownReceiver() {
        if (rescueShutdownReceiverRegistered) return
        val filter = IntentFilter(RescueControlProtocol.ACTION_REQUEST_SHUTDOWN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rescueShutdownReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(rescueShutdownReceiver, filter)
        }
        rescueShutdownReceiverRegistered = true
    }

    private fun stopRemoteAssistanceIfActive() {
        if (remoteAssistStopRequested) return
        val remoteAssistController = RescueRemoteAssistController.getInstance(this)
        if (remoteAssistController.state.value.phase != RescueAssistHostPhase.IDLE) {
            remoteAssistStopRequested = true
            remoteAssistController.stopSharing()
        }
    }

    private fun acceptPendingAction(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PENDING_ACTION_ID).orEmpty()
        val prompt = intent?.getStringExtra(EXTRA_PENDING_ACTION_PROMPT).orEmpty()
        PendingRescueActionHandler.set(id, prompt)
    }
}
