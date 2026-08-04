package com.ai.assistance.operit.api.chat

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.application.ForegroundServiceCompat
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.integrations.http.ExternalChatHttpState
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitRuntimeContext
import com.ai.assistance.operit.util.WaifuMessageProcessor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Foreground lifetime for active AI conversations in lean BASIC and RESCUE hosts. */
class AIForegroundService : Service() {
    companion object {
        private const val TAG = "AIForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val REPLY_NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "AI_SERVICE_CHANNEL"
        private const val REPLY_CHANNEL_ID_PREFIX = "AI_REPLY_COMPLETE_CHANNEL"
        private const val REPLY_NOTIFICATION_TAG_PREFIX = "ai_reply:"
        private const val ACTION_CANCEL_CURRENT_OPERATION =
            "com.ai.assistance.operit.action.CANCEL_CURRENT_OPERATION"
        private const val ACTION_EXIT_APP = "com.ai.assistance.operit.action.EXIT_APP"
        private const val REQUEST_CODE_CANCEL_CURRENT_OPERATION = 9002
        private const val REQUEST_CODE_EXIT_APP = 9003
        private val REPLY_VIBRATION_PATTERN = longArrayOf(0L, 250L, 150L, 250L)

        val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        private val activeReplyNotificationTags = ConcurrentHashMap.newKeySet<String>()
        private val externalHttpStateFlow = MutableStateFlow(ExternalChatHttpState())
        val externalHttpState = externalHttpStateFlow.asStateFlow()

        const val EXTRA_CHARACTER_NAME = "extra_character_name"
        const val EXTRA_AVATAR_URI = "extra_avatar_uri"
        const val EXTRA_STATE = "extra_state"
        const val STATE_RUNNING = "running"
        const val STATE_IDLE = "idle"

        private fun createMainActivityPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun ensureReplyNotificationChannel(
            context: Context,
            enableSound: Boolean,
            enableVibration: Boolean
        ): String {
            val suffix =
                when {
                    enableSound && enableVibration -> "sound_vibration"
                    enableSound -> "sound"
                    enableVibration -> "vibration"
                    else -> "silent"
                }
            val channelId = "${REPLY_CHANNEL_ID_PREFIX}_$suffix"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return channelId

            val channel =
                NotificationChannel(
                    channelId,
                    context.getString(R.string.service_chat_complete_reminder),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.service_notify_when_complete)
                    setSound(
                        if (enableSound) Settings.System.DEFAULT_NOTIFICATION_URI else null,
                        if (enableSound) {
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        } else {
                            null
                        }
                    )
                    enableVibration(enableVibration)
                    vibrationPattern =
                        if (enableVibration) REPLY_VIBRATION_PATTERN else longArrayOf(0L)
                }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
            return channelId
        }

        fun notifyReplyCompleted(
            context: Context,
            chatId: String?,
            characterName: String?,
            rawReplyContent: String?,
            avatarUri: String?,
            notifyReplyOverride: Boolean? = null
        ) {
            if (ActivityLifecycleManager.getCurrentActivity() != null || rawReplyContent.isNullOrBlank()) {
                return
            }
            runCatching {
                val appContext = context.applicationContext
                val preferences = DisplayPreferencesManager.getInstance(appContext)
                val shouldNotify =
                    notifyReplyOverride ?: runBlocking { preferences.enableReplyNotification.first() }
                if (!shouldNotify) return

                val enableSound =
                    runBlocking { preferences.enableReplyNotificationSound.first() }
                val enableVibration =
                    runBlocking { preferences.enableReplyNotificationVibration.first() }
                val channelId =
                    ensureReplyNotificationChannel(appContext, enableSound, enableVibration)
                val content = WaifuMessageProcessor.cleanContentForWaifu(rawReplyContent)
                val notification =
                    NotificationCompat.Builder(appContext, channelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(
                            characterName
                                ?: appContext.getString(R.string.notification_ai_reply_title)
                        )
                        .setContentText(
                            content.take(100).ifEmpty {
                                appContext.getString(R.string.notification_ai_reply_content)
                            }
                        )
                        .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(createMainActivityPendingIntent(appContext))
                        .setAutoCancel(true)
                        .build()
                val tag = "$REPLY_NOTIFICATION_TAG_PREFIX${chatId?.ifBlank { "default" } ?: "default"}"
                activeReplyNotificationTags.add(tag)
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(tag, REPLY_NOTIFICATION_ID, notification)
            }.onFailure { error ->
                AppLogger.e(TAG, "Failed to publish AI reply notification", error)
            }
        }

        fun setWakeListeningSuspendedForIme(context: Context, imeVisible: Boolean) = Unit

        fun setWakeListeningSuspendedForFloatingFullscreen(context: Context, active: Boolean) = Unit

        fun ensureMicrophoneForeground(context: Context, forceStart: Boolean = false) = Unit

        fun ensureRunningForExternalHttp(context: Context) {
            externalHttpStateFlow.value =
                ExternalChatHttpState(
                    isRunning = false,
                    lastError = "External HTTP control is unavailable in this host build"
                )
        }

        fun refreshBackgroundKeepAlive(context: Context) = Unit

        fun stopExternalHttp(context: Context) {
            externalHttpStateFlow.value = ExternalChatHttpState()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chatRuntimeHolder by lazy { ChatRuntimeHolder.getInstance(applicationContext) }
    private var characterName: String? = null
    private var isAiBusy: Boolean = false
    private var hideRuntimeTaskViewEnabled: Boolean = false
    private var lastAppliedRuntimeTaskViewHidden: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        OperitRuntimeContext.bind(applicationContext)
        AppLogger.bindContext(applicationContext)
        createNotificationChannel()
        ForegroundServiceCompat.startForeground(
            service = this,
            notificationId = NOTIFICATION_ID,
            notification = createNotification(),
            types = ForegroundServiceCompat.buildTypes(dataSync = true)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT_APP -> {
                AIMessageManager.cancelCurrentOperation()
                finishCurrentActivity()
                stopAndRemoveNotification()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_CURRENT_OPERATION -> {
                AIMessageManager.cancelCurrentOperation()
                isAiBusy = false
                updateRuntimeTaskViewVisibility()
                refreshNotification()
                return START_NOT_STICKY
            }
        }

        intent?.let {
            characterName = it.getStringExtra(EXTRA_CHARACTER_NAME)
            when (it.getStringExtra(EXTRA_STATE)) {
                STATE_RUNNING -> isAiBusy = true
                STATE_IDLE -> isAiBusy = false
            }
        }
        hideRuntimeTaskViewEnabled =
            runCatching {
                runBlocking {
                    DisplayPreferencesManager
                        .getInstance(applicationContext)
                        .hideRuntimeTaskView
                        .first()
                }
            }.getOrDefault(false)
        updateRuntimeTaskViewVisibility()

        if (!isAiBusy) {
            stopAndRemoveNotification()
            return START_NOT_STICKY
        }
        refreshNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        isAiBusy = false
        updateRuntimeTaskViewVisibility()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_operit_running),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_keep_background)
                setShowBadge(false)
            }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val activeConversationCount = chatRuntimeHolder.activeConversationCount.value
        val currentSessionToolCount = chatRuntimeHolder.currentSessionToolCount.value
        val contentText =
            if (isAiBusy && activeConversationCount > 0) {
                getString(
                    R.string.service_running_stats,
                    activeConversationCount,
                    currentSessionToolCount
                )
            } else {
                getString(R.string.service_operit_running)
            }
        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(characterName ?: getString(R.string.service_operit_running))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(createMainActivityPendingIntent(this))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)

        val exitIntent = Intent(this, AIForegroundService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.service_exit),
            PendingIntent.getService(
                this,
                REQUEST_CODE_EXIT_APP,
                exitIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        if (isAiBusy) {
            val cancelIntent = Intent(this, AIForegroundService::class.java).apply {
                action = ACTION_CANCEL_CURRENT_OPERATION
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.service_stop),
                PendingIntent.getService(
                    this,
                    REQUEST_CODE_CANCEL_CURRENT_OPERATION,
                    cancelIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        return builder.build()
    }

    private fun refreshNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification())
    }

    private fun updateRuntimeTaskViewVisibility() {
        val shouldHide = hideRuntimeTaskViewEnabled && isAiBusy
        if (lastAppliedRuntimeTaskViewHidden == shouldHide || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        runCatching {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.appTasks.orEmpty().forEach { task ->
                task.setExcludeFromRecents(shouldHide)
            }
            lastAppliedRuntimeTaskViewHidden = shouldHide
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to update runtime task visibility", error)
        }
    }

    private fun finishCurrentActivity() {
        val activity = ActivityLifecycleManager.getCurrentActivity() ?: return
        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.finishAndRemoveTask()
            } else {
                activity.finish()
            }
        }
    }

    private fun stopAndRemoveNotification() {
        isRunning.set(false)
        isAiBusy = false
        updateRuntimeTaskViewVisibility()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        activeReplyNotificationTags.forEach { tag -> manager.cancel(tag, REPLY_NOTIFICATION_ID) }
        activeReplyNotificationTags.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
}
