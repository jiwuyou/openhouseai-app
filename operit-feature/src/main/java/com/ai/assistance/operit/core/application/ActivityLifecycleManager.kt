package com.ai.assistance.operit.core.application

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycle
import com.ai.assistance.operit.host.lifecycle.OperitLifecycleEvent
import com.ai.assistance.operit.host.lifecycle.OperitLifecycleEventParams
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ActivityLifecycleManager : Application.ActivityLifecycleCallbacks {
    private const val TAG = "ActivityLifecycleManager"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentActivity: WeakReference<Activity>? = null
    private var application: Application? = null
    private var activityCount = 0
    private var startedActivityCount = 0
    private var isAppInForeground = false
    private var keepScreenOnPreferenceRequestCount = 0
    private var keepScreenOnForcedRequestCount = 0

    @Volatile
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized && this.application === application) {
            return
        }
        if (initialized) {
            this.application?.unregisterActivityLifecycleCallbacks(this)
        }
        this.application = application
        application.registerActivityLifecycleCallbacks(this)
        initialized = true
    }

    fun getCurrentActivity(): Activity? =
        currentActivity?.get()

    fun checkAndApplyKeepScreenOn(enable: Boolean) {
        applyKeepScreenOnRequest(enable = enable, respectUserPreference = true)
    }

    fun forceKeepScreenOn(enable: Boolean) {
        applyKeepScreenOnRequest(enable = enable, respectUserPreference = false)
    }

    private fun applyKeepScreenOnRequest(enable: Boolean, respectUserPreference: Boolean) {
        scope.launch {
            try {
                val runtimeConfig = OperitApplication.currentConfig()
                if (enable && respectUserPreference && !runtimeConfig.keepScreenOnEnabledByDefault) {
                    return@launch
                }

                if (respectUserPreference) {
                    if (enable) {
                        keepScreenOnPreferenceRequestCount += 1
                    } else if (keepScreenOnPreferenceRequestCount > 0) {
                        keepScreenOnPreferenceRequestCount -= 1
                    }
                } else {
                    if (enable) {
                        keepScreenOnForcedRequestCount += 1
                    } else if (keepScreenOnForcedRequestCount > 0) {
                        keepScreenOnForcedRequestCount -= 1
                    }
                }

                val activity = getCurrentActivity()
                if (activity == null) {
                    Log.w(TAG, "Cannot apply screen-on flag: current activity is null.")
                    return@launch
                }

                activity.runOnUiThread {
                    val window = activity.window
                    if (keepScreenOnPreferenceRequestCount + keepScreenOnForcedRequestCount > 0) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply screen-on flag.", e)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activityCount += 1
        dispatchActivityEvent(OperitLifecycleEvent.ACTIVITY_CREATE, activity)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount += 1
        dispatchActivityEvent(OperitLifecycleEvent.ACTIVITY_START, activity)
        if (!isAppInForeground && startedActivityCount > 0) {
            isAppInForeground = true
            OperitHostLifecycle.dispatchAsync(
                event = OperitLifecycleEvent.APPLICATION_FOREGROUND,
                params = OperitLifecycleEventParams(activity.applicationContext)
            )
        }
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        dispatchActivityEvent(OperitLifecycleEvent.ACTIVITY_RESUME, activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() == activity) {
            currentActivity?.clear()
        }
        dispatchActivityEvent(OperitLifecycleEvent.ACTIVITY_PAUSE, activity)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        dispatchActivityEvent(OperitLifecycleEvent.ACTIVITY_STOP, activity)
        if (isAppInForeground && startedActivityCount == 0) {
            isAppInForeground = false
            OperitHostLifecycle.dispatchAsync(
                event = OperitLifecycleEvent.APPLICATION_BACKGROUND,
                params = OperitLifecycleEventParams(activity.applicationContext)
            )
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() == activity) {
            currentActivity?.clear()
        }
        activityCount = (activityCount - 1).coerceAtLeast(0)
        dispatchActivityEvent(OperitLifecycleEvent.ACTIVITY_DESTROY, activity)
    }

    private fun dispatchActivityEvent(event: OperitLifecycleEvent, activity: Activity) {
        OperitHostLifecycle.dispatchAsync(
            event = event,
            params = OperitLifecycleEventParams(
                context = activity.applicationContext,
                extras = mapOf("activityClassName" to activity.javaClass.name)
            )
        )
    }
}

