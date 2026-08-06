package com.ai.assistance.operit.host.control

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.api.voice.VoiceServiceFactory
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.agent.PhoneAgentJobRegistry
import com.ai.assistance.operit.core.tools.agent.ShowerController
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.system.ScreenCaptureService
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.ui.common.displays.VirtualDisplayOverlay
import com.ai.assistance.operit.util.AnrMonitor
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object OperitShutdownController {
    private const val TAG = "OperitShutdownController"
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun isShutdownInProgress(): Boolean = shutdownStarted.get()

    fun shutdownFromActivity(
        activity: Activity,
        reason: String = OperitControlProtocol.SHUTDOWN_REASON_USER,
        requestedBy: String = OperitControlProtocol.SHUTDOWN_REQUESTED_BY_OPERIT,
        anrMonitor: AnrMonitor? = null,
        beforeFinish: (() -> Unit)? = null
    ) {
        if (!ensureOperitProcess(activity)) return
        if (!shutdownStarted.compareAndSet(false, true)) return

        shutdownScope.launch {
            performShutdownCleanup(
                context = activity.applicationContext,
                reason = reason,
                requestedBy = requestedBy,
                anrMonitor = anrMonitor,
                beforeFinish = beforeFinish,
                updateControlState = true,
                stopApplicationServices = true,
            )
            finishActivity(activity, removeTask = true)
            killCurrentProcess(
                activity.applicationContext,
                OperitControlProtocol.OPERIT_PROCESS_SUFFIX,
                "Operit",
            )
        }
    }

    /** Close an isolated Rescue UI process without changing the main :operit control state. */
    fun shutdownRescueFromActivity(
        activity: Activity,
        rescueProcessSuffix: String,
        beforeFinish: (() -> Unit)? = null,
    ) {
        if (!ensureProcessSuffix(activity, rescueProcessSuffix, "Rescue")) return
        if (!shutdownStarted.compareAndSet(false, true)) return

        shutdownScope.launch {
            performShutdownCleanup(
                context = activity.applicationContext,
                reason = OperitControlProtocol.SHUTDOWN_REASON_USER,
                requestedBy = "rescue",
                beforeFinish = beforeFinish,
                updateControlState = false,
                stopApplicationServices = false,
            )
            finishActivity(activity, removeTask = true)
            killCurrentProcess(activity.applicationContext, rescueProcessSuffix, "Rescue")
        }
    }

    fun shutdownFromContext(
        context: Context,
        reason: String = OperitControlProtocol.SHUTDOWN_REASON_USER,
        requestedBy: String = OperitControlProtocol.SHUTDOWN_REQUESTED_BY_HOME
    ) {
        val appContext = context.applicationContext ?: context
        if (!ensureOperitProcess(appContext)) return
        if (!shutdownStarted.compareAndSet(false, true)) return

        shutdownScope.launch {
            performShutdownCleanup(
                context = appContext,
                reason = reason,
                requestedBy = requestedBy,
                updateControlState = true,
                stopApplicationServices = true,
            )
            killCurrentProcess(appContext, OperitControlProtocol.OPERIT_PROCESS_SUFFIX, "Operit")
        }
    }

    private fun ensureOperitProcess(context: Context): Boolean {
        val isOperitProcess = OperitControlProtocol.isCurrentOperitProcess(context)
        if (!isOperitProcess) {
            AppLogger.w(TAG, "Ignoring shutdown request outside :operit process")
        }
        return isOperitProcess
    }

    private fun ensureProcessSuffix(context: Context, suffix: String, label: String): Boolean {
        val processName = OperitControlProtocol.resolveCurrentProcessName(context)
        val matches = isExpectedProcessName(context.packageName, suffix, processName)
        if (!matches) AppLogger.w(TAG, "Ignoring $label shutdown outside expected process: $processName")
        return matches
    }

    internal fun isExpectedProcessName(
        packageName: String,
        processSuffix: String,
        processName: String?,
    ): Boolean = processName == packageName + processSuffix

    private suspend fun performShutdownCleanup(
        context: Context,
        reason: String,
        requestedBy: String,
        anrMonitor: AnrMonitor? = null,
        beforeFinish: (() -> Unit)? = null,
        updateControlState: Boolean,
        stopApplicationServices: Boolean,
    ) {
        AppLogger.d(TAG, "Shutdown started: reason=$reason requestedBy=$requestedBy")
        if (updateControlState) OperitControlStateStore.markStopping(context)

        runCleanupStep("cancel AI operations") {
            AIMessageManager.cancelAllOperations()
        }
        if (stopApplicationServices) {
            runCleanupStep("stop plugin package runtime") {
                PackageManager.getInstance(context, AIToolHandler.getInstance(context)).destroy()
            }
        }
        runSuspendCleanupStep("stop terminal runtime") {
            Terminal.getInstance(context).destroy()
        }
        runCleanupStep("stop phone agents") {
            PhoneAgentJobRegistry.cancelAll("Operit shutdown")
        }
        runCleanupStep("hide virtual displays") {
            VirtualDisplayOverlay.hideAll()
            ShowerController.shutdown()
        }
        withContext(Dispatchers.IO) {
            runSuspendCleanupStep("stop speech recognition") {
                SpeechServiceFactory.getInstance(context).cancelRecognition()
            }
            runSuspendCleanupStep("stop voice playback") {
                VoiceServiceFactory.getInstance(context).stop()
            }
        }
        if (stopApplicationServices) {
            runCleanupStep("stop foreground services") {
                AIForegroundService.stopExternalHttp(context)
                stopService(context, AIForegroundService::class.java)
                stopService(context, ScreenCaptureService::class.java)
            }
        }
        runCleanupStep("stop ANR monitor") {
            anrMonitor?.stop()
        }
        runCleanupStep("before finish callback") {
            beforeFinish?.invoke()
        }

        if (updateControlState) OperitControlStateStore.markStopped(context)
        AppLogger.d(TAG, "Shutdown cleanup finished")
    }

    private fun stopService(context: Context, serviceClass: Class<*>) {
        runCatching {
            context.stopService(Intent(context, serviceClass))
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to stop service ${serviceClass.simpleName}", error)
        }
    }

    private inline fun runCleanupStep(name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Shutdown step failed: $name", error)
        }
    }

    private suspend inline fun runSuspendCleanupStep(name: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Shutdown step failed: $name", error)
        }
    }

    private fun finishActivity(activity: Activity, removeTask: Boolean) {
        val finishAction = {
            runCatching {
                if (removeTask && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activity.finishAndRemoveTask()
                } else {
                    @Suppress("DEPRECATION")
                    activity.finish()
                }
            }.onFailure { error ->
                AppLogger.e(TAG, "Failed to finish Operit activity", error)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishAction()
        } else {
            Handler(Looper.getMainLooper()).post { finishAction() }
        }
    }

    private suspend fun killCurrentProcess(
        context: Context,
        expectedSuffix: String,
        label: String,
    ) {
        delay(120)
        val processName = OperitControlProtocol.resolveCurrentProcessName(context)
        if (!isExpectedProcessName(context.packageName, expectedSuffix, processName)) {
            AppLogger.w(TAG, "Refusing to kill unexpected $label process: $processName")
            return
        }
        AppLogger.d(TAG, "Killing $label process pid=${Process.myPid()}")
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
