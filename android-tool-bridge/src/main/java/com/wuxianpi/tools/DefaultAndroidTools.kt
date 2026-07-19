package com.wuxianpi.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

object DefaultAndroidTools {
    private const val NOTIFICATION_CHANNEL = "openhouse_ai_tools"

    fun create(context: Context): AndroidToolRegistry {
        val app = context.applicationContext
        return AndroidToolRegistry()
            .register("clipboard", clipboard(app))
            .register("intent", intent(app))
            .register("share", share(app))
            .register("notification", notification(app))
    }

    private fun clipboard(context: Context) = AndroidToolHandler { call ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@AndroidToolHandler ToolResult.failure(
                call.id,
                "service_unavailable",
                "Clipboard service is unavailable",
            )
        when (call.arguments.optString("operation", "read")) {
            "read" -> {
                val value = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    .orEmpty()
                ToolResult.success(call.id, JSONObject().put("text", value))
            }
            "write" -> {
                val text = call.arguments.requireString("text")
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenHouse AI", text))
                ToolResult.success(call.id, JSONObject().put("written", true))
            }
            else -> ToolResult.failure(call.id, "invalid_arguments", "operation must be read or write")
        }
    }

    private fun intent(context: Context) = AndroidToolHandler { call ->
        val args = call.arguments
        val action = args.optString("action", Intent.ACTION_VIEW)
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("data").takeIf(String::isNotBlank)?.let { intent.data = Uri.parse(it) }
        args.optString("mimeType").takeIf(String::isNotBlank)?.let { intent.type = it }
        args.optString("package").takeIf(String::isNotBlank)?.let { intent.setPackage(it) }
        args.optJSONObject("extras")?.let { extras ->
            extras.keys().forEach { key ->
                when (val value = extras.get(key)) {
                    is String -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                    else -> throw IllegalArgumentException("Unsupported extra type for $key")
                }
            }
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            ToolResult.failure(call.id, "activity_not_found", "No Android app can handle this intent")
        } else {
            context.startActivity(intent)
            ToolResult.success(call.id, JSONObject().put("launched", true))
        }
    }

    private fun share(context: Context) = AndroidToolHandler { call ->
        val text = call.arguments.requireString("text")
        val share = Intent(Intent.ACTION_SEND)
            .setType(call.arguments.optString("mimeType", "text/plain"))
            .putExtra(Intent.EXTRA_TEXT, text)
        call.arguments.optString("subject").takeIf(String::isNotBlank)?.let {
            share.putExtra(Intent.EXTRA_SUBJECT, it)
        }
        val chooser = Intent.createChooser(share, call.arguments.optString("title", "Share with"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        ToolResult.success(call.id, JSONObject().put("chooserOpened", true))
    }

    private fun notification(context: Context) = AndroidToolHandler { call ->
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return@AndroidToolHandler ToolResult.failure(
                call.id,
                "service_unavailable",
                "Notification service is unavailable",
            )
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "OpenHouse AI tools",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val title = call.arguments.requireString("title")
        val text = call.arguments.requireString("text")
        val id = call.arguments.optInt("id", (System.currentTimeMillis() and 0x7fffffff).toInt())
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
        ToolResult.success(call.id, JSONObject().put("notificationId", id))
    }

    private fun JSONObject.requireString(key: String): String =
        optString(key).takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")
}
