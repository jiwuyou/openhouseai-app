package com.openhouse.host.nativeapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class NativeTermuxCommandResult(
    val executionId: Int,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errorCode: Int,
    val errorMessage: String,
)

internal class NativeTermuxCallbackRegistry<T> {
    private data class Entry<T>(
        val callback: (T) -> Unit,
        val cleanup: () -> Unit,
    )

    private val entries = ConcurrentHashMap<Int, Entry<T>>()

    fun register(
        executionId: Int,
        callback: (T) -> Unit,
        cleanup: () -> Unit,
    ) {
        entries.put(executionId, Entry(callback, cleanup))?.cleanup?.invoke()
    }

    fun complete(executionId: Int, result: T): Boolean {
        val entry = entries.remove(executionId) ?: return false
        try {
            entry.callback(result)
        } finally {
            entry.cleanup()
        }
        return true
    }

    fun cancel(executionId: Int): Boolean {
        val entry = entries.remove(executionId) ?: return false
        entry.cleanup()
        return true
    }

    fun size(): Int = entries.size
}

internal class NativeTermuxPendingRequest(
    val executionId: Int,
    val pendingIntent: PendingIntent,
) {
    fun cancel() {
        NativeTermuxCommandResultCallbacks.removeCallback(executionId)
        pendingIntent.cancel()
    }
}

internal object NativeTermuxCommandResultCallbacks {
    private const val EXTRA_EXECUTION_ID = "execution_id"
    private val nextExecutionId = AtomicInteger(1)
    private val callbacks = NativeTermuxCallbackRegistry<NativeTermuxCommandResult>()

    fun createPendingIntent(
        context: Context,
        receiverClass: Class<out NativeTermuxCommandResultReceiver>,
        callback: (NativeTermuxCommandResult) -> Unit,
    ): NativeTermuxPendingRequest {
        val executionId = nextExecutionId.getAndIncrement()
        val intent = Intent(context, receiverClass).putExtra(EXTRA_EXECUTION_ID, executionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, executionId, intent, flags)
        callbacks.register(executionId, callback, pendingIntent::cancel)
        return NativeTermuxPendingRequest(executionId, pendingIntent)
    }

    fun removeCallback(executionId: Int) {
        callbacks.cancel(executionId)
    }

    fun deliver(result: NativeTermuxCommandResult): Boolean =
        callbacks.complete(result.executionId, result)

    fun executionId(intent: Intent): Int = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
}

abstract class NativeTermuxCommandResultReceiver : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val executionId = NativeTermuxCommandResultCallbacks.executionId(intent)
        if (executionId < 0) return
        val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        val result = if (resultBundle == null) {
            NativeTermuxCommandResult(
                executionId = executionId,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errorCode = TERMUX_PROTOCOL_ERROR_CODE,
                errorMessage = "Termux did not return a result bundle",
            )
        } else {
            val hasErrorCode = resultBundle.containsKey(EXTRA_ERROR_CODE)
            NativeTermuxCommandResult(
                executionId = executionId,
                stdout = resultBundle.getString(EXTRA_STDOUT, ""),
                stderr = resultBundle.getString(EXTRA_STDERR, ""),
                exitCode = if (resultBundle.containsKey(EXTRA_EXIT_CODE)) {
                    resultBundle.getInt(EXTRA_EXIT_CODE)
                } else {
                    -1
                },
                errorCode = if (hasErrorCode) {
                    resultBundle.getInt(EXTRA_ERROR_CODE)
                } else {
                    TERMUX_PROTOCOL_ERROR_CODE
                },
                errorMessage = if (hasErrorCode) {
                    resultBundle.getString(EXTRA_ERROR_MESSAGE, "")
                } else {
                    "Termux result bundle is missing required err field"
                },
            )
        }
        NativeTermuxCommandResultCallbacks.deliver(result)
    }

    private companion object {
        const val EXTRA_RESULT_BUNDLE = "result"
        const val EXTRA_STDOUT = "stdout"
        const val EXTRA_STDERR = "stderr"
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_ERROR_CODE = "err"
        const val EXTRA_ERROR_MESSAGE = "errmsg"
        const val TERMUX_PROTOCOL_ERROR_CODE = 1
    }
}

class NativeDefaultTermuxCommandResultReceiver : NativeTermuxCommandResultReceiver()
class NativeOpenHouseTermuxCommandResultReceiver : NativeTermuxCommandResultReceiver()
class NativeOperitTermuxCommandResultReceiver : NativeTermuxCommandResultReceiver()
class NativeRescueTermuxCommandResultReceiver : NativeTermuxCommandResultReceiver()
class NativeAdvancedUiTermuxCommandResultReceiver : NativeTermuxCommandResultReceiver()

internal object NativeTermuxReceiverSelector {
    fun receiverClassFor(
        processName: String,
        applicationPackage: String,
    ): Class<out NativeTermuxCommandResultReceiver> = when (processName) {
        "$applicationPackage:openhouse" -> NativeOpenHouseTermuxCommandResultReceiver::class.java
        "$applicationPackage:operit" -> NativeOperitTermuxCommandResultReceiver::class.java
        "$applicationPackage:rescue_ui" -> NativeRescueTermuxCommandResultReceiver::class.java
        "$applicationPackage:advanced_ui" -> NativeAdvancedUiTermuxCommandResultReceiver::class.java
        else -> NativeDefaultTermuxCommandResultReceiver::class.java
    }
}
