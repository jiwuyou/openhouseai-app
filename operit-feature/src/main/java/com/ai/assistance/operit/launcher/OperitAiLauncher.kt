package com.ai.assistance.operit.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ai.assistance.operit.rescue.ui.RescueActivity
import com.ai.assistance.operit.ui.main.MainActivity

/** Stable Java-friendly Basic and Repair entry points for every APK host. */
object OperitAiLauncher {
    // Keep these Java-facing constants local. K2/KAPT cannot reliably fold const aliases
    // imported from the larger Pi pairing source file during incremental Android builds.
    const val PI_RUNTIME_URL = "http://127.0.0.1:8765/"
    const val AION_UI_URL = "http://127.0.0.1:25808/"
    const val BUILTIN_WEB_UI_URL = PI_RUNTIME_URL
    const val ADVANCED_UI_METADATA_URL = "${PI_RUNTIME_URL}v1/ui/metadata"

    @JvmStatic
    fun basicIntent(context: Context): Intent = basicIntent(context, null)

    @JvmStatic
    fun basicIntent(context: Context, hostReturnActivity: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HOSTED_MODE, true)
            putExtra(MainActivity.EXTRA_HOST_MODE, "basic")
            hostReturnActivity
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { putExtra(MainActivity.EXTRA_HOST_RETURN_ACTIVITY, it) }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    @JvmStatic
    fun repairIntent(context: Context): Intent =
        RescueActivity.createIntent(context).apply {
            // Rescue has its own task/process so returning to OpenHouse does not destroy the
            // running repair session. Reusing the task keeps reopening the mode instantaneous.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }

    @JvmStatic
    fun advancedUrls(): Array<String> = arrayOf(AION_UI_URL, BUILTIN_WEB_UI_URL)

    @JvmStatic
    fun advancedIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(AION_UI_URL))

    @JvmStatic
    fun advancedFallbackIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(BUILTIN_WEB_UI_URL))

    @JvmStatic
    fun openBasic(context: Context) {
        launch(context, basicIntent(context))
    }

    @JvmStatic
    fun openBasic(context: Context, hostReturnActivity: String?) {
        launch(context, basicIntent(context, hostReturnActivity))
    }

    @JvmStatic
    fun openRepair(context: Context) {
        launch(context, repairIntent(context))
    }

    @JvmStatic
    fun openAdvanced(context: Context) {
        launch(context, advancedIntent())
    }

    @JvmStatic
    fun openAdvancedFallback(context: Context) {
        launch(context, advancedFallbackIntent())
    }

    private fun launch(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
