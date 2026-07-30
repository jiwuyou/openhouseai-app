package com.ai.assistance.operit.rescue.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wuxianpi.assist.protocol.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object RescueRemoteAssistServiceIntents {
    const val ACTION_START = "com.wuxianpi.rescue.remote.START"
    const val ACTION_VERIFY_SAS = "com.wuxianpi.rescue.remote.VERIFY_SAS"
    const val ACTION_STOP = "com.wuxianpi.rescue.remote.STOP"
    const val EXTRA_CHAT_ID = "chat_id"
    const val EXTRA_RELAY_URL = "relay_url"
    const val EXTRA_PERMISSION = "permission"
    const val EXTRA_SAS = "sas"

    fun start(context: Context, pinnedChatId: String, relayUrl: String, permission: Permission): Intent =
        Intent(context, RescueRemoteAssistService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_CHAT_ID, pinnedChatId)
            putExtra(EXTRA_RELAY_URL, relayUrl)
            putExtra(EXTRA_PERMISSION, permission.name)
        }

    fun verifySas(context: Context, code: String): Intent =
        Intent(context, RescueRemoteAssistService::class.java).apply {
            action = ACTION_VERIFY_SAS
            putExtra(EXTRA_SAS, code)
        }

    fun stop(context: Context): Intent =
        Intent(context, RescueRemoteAssistService::class.java).apply { action = ACTION_STOP }
}

class RescueRemoteAssistService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: RescueRemoteAssistController
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        controller = RescueRemoteAssistController.getInstance(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(controller.state.value))
        notificationJob = serviceScope.launch {
            controller.state.collectLatest { state ->
                notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RescueRemoteAssistServiceIntents.ACTION_START -> {
                val chatId = intent.getStringExtra(RescueRemoteAssistServiceIntents.EXTRA_CHAT_ID).orEmpty()
                val relayUrl = intent.getStringExtra(RescueRemoteAssistServiceIntents.EXTRA_RELAY_URL).orEmpty()
                val permission =
                    runCatching {
                        Permission.valueOf(
                            intent.getStringExtra(RescueRemoteAssistServiceIntents.EXTRA_PERMISSION).orEmpty(),
                        )
                    }.getOrDefault(Permission.VIEW)
                controller.startSharingNow(chatId, relayUrl, permission)
            }
            RescueRemoteAssistServiceIntents.ACTION_VERIFY_SAS ->
                controller.verifySasNow(
                    intent.getStringExtra(RescueRemoteAssistServiceIntents.EXTRA_SAS).orEmpty(),
                )
            RescueRemoteAssistServiceIntents.ACTION_STOP -> {
                controller.stopSharingNow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        controller.stopSharingNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: RescueAssistHostState): Notification {
        val stopIntent = RescueRemoteAssistServiceIntents.stop(this)
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_sys_upload
        val detail = when (state.phase) {
            RescueAssistHostPhase.IDLE -> "Remote assistance is stopping"
            RescueAssistHostPhase.CONNECTING -> "Connecting to assistance relay"
            RescueAssistHostPhase.WAITING_FOR_PEER -> "Waiting for helper"
            RescueAssistHostPhase.WAITING_FOR_SAS -> "Waiting for verification code"
            RescueAssistHostPhase.AUTHORIZED ->
                state.peerDisplayName?.let { "Sharing Rescue chat with $it" }
                    ?: "Encrypted Rescue sharing is active"
            RescueAssistHostPhase.ERROR -> state.error ?: "Remote assistance failed"
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("WuxianPi Rescue assistance")
            .setContentText(detail)
            .setOngoing(state.isSharing)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Rescue remote assistance",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "wuxianpi_rescue_remote_assist"
        private const val NOTIFICATION_ID = 0x525341
    }
}
