package com.wuxianpi.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.util.concurrent.atomic.AtomicInteger

/** Stable host entry point shared by Native and All-in-One editions. */
open class WuxianPiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WuxianPiDiagnostics.recordActivity(this, "create")
        AiFeatureStatus.onCreated()
        val config = intent.toFeatureConfig()
        setContent { WuxianPiFeature(config) }
    }

    override fun onStart() {
        super.onStart()
        WuxianPiDiagnostics.recordActivity(this, "start")
        AiFeatureStatus.onStarted()
    }

    override fun onStop() {
        WuxianPiDiagnostics.recordActivity(this, "stop")
        AiFeatureStatus.onStopped()
        super.onStop()
    }

    override fun onDestroy() {
        WuxianPiDiagnostics.recordActivity(this, "destroy")
        AiFeatureStatus.onDestroyed()
        super.onDestroy()
    }

    private fun Intent.toFeatureConfig(): AiFeatureConfig = when (getStringExtra(EXTRA_MODE)) {
        MODE_BUNDLED -> AiFeatureConfig.bundledTermux(
            serviceUrl = requireNotNull(getStringExtra(EXTRA_ADMIN_URL)) { "serviceUrl is required" },
            clientId = requireNotNull(getStringExtra(EXTRA_CLIENT_ID)) { "clientId is required" },
        )
        else -> AiFeatureConfig.externalTermux()
    }

    companion object {
        private const val EXTRA_MODE = "com.wuxianpi.ai.MODE"
        private const val EXTRA_ADMIN_URL = "com.wuxianpi.ai.ADMIN_URL"
        private const val EXTRA_TOKEN = "com.wuxianpi.ai.TOKEN"
        private const val EXTRA_CLIENT_ID = "com.wuxianpi.ai.CLIENT_ID"
        private const val MODE_EXTERNAL = "external_termux"
        private const val MODE_BUNDLED = "bundled_termux"

        @JvmStatic
        fun createExternalIntent(context: Context): Intent =
            Intent(context, WuxianPiActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_EXTERNAL)

        @JvmStatic
        fun createBundledIntent(
            context: Context,
            serviceUrl: String,
            clientId: String,
        ): Intent {
            AiFeatureConfig.bundledTermux(serviceUrl, clientId)
            return Intent(context, WuxianPiActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_BUNDLED)
                .putExtra(EXTRA_ADMIN_URL, serviceUrl)
                .putExtra(EXTRA_CLIENT_ID, clientId)
        }

        /** Backward-compatible overload for already integrated hosts; token is no longer used. */
        @JvmStatic
        fun createBundledIntent(
            context: Context,
            adminUrl: String,
            token: String,
            clientId: String,
        ): Intent {
            // Validate before credentials are placed in an Intent.
            AiFeatureConfig.bundledTermux(adminUrl, token, clientId)
            return Intent(context, WuxianPiActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_BUNDLED)
                .putExtra(EXTRA_ADMIN_URL, adminUrl)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_CLIENT_ID, clientId)
        }
    }
}

object AiFeatureStatus {
    private val createdActivities = AtomicInteger(0)
    private val visibleActivities = AtomicInteger(0)

    @JvmStatic
    fun isRunning(): Boolean = createdActivities.get() > 0

    @JvmStatic
    fun isVisible(): Boolean = visibleActivities.get() > 0

    internal fun onCreated() {
        createdActivities.incrementAndGet()
    }

    internal fun onDestroyed() {
        createdActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    internal fun onStarted() {
        visibleActivities.incrementAndGet()
    }

    internal fun onStopped() {
        visibleActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
