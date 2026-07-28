package com.openhouse.host.nativeapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-visible Native coordinator for acquiring or preparing the All-in-One Termux host. */
class NativeRuntimeHostPreparationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            setPadding(48, 48, 48, 48)
            text = "Checking WuxianPi runtime host..."
        }
        setContentView(status)
        val probe = NativeExternalHostInspector.inspect(this)
        when (probe.state) {
            NativeExternalHostState.READY -> {
                if (NativeExternalHostInspector.launchPreparation(this, probe)) {
                    status.text = "WuxianPi All-in-One preparation opened."
                    finish()
                } else {
                    status.text = "Unable to open WuxianPi All-in-One preparation."
                }
            }
            NativeExternalHostState.INCOMPATIBLE_TERMUX -> status.text = probe.message
            NativeExternalHostState.ABSENT -> downloadAllInOne()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun downloadAllInOne() {
        scope.launch {
            status.text = "Finding the latest ARM64 WuxianPi All-in-One APK..."
            runCatching {
                withContext(Dispatchers.IO) {
                    val downloader = NativeAllInOneReleaseDownloader()
                    val asset = downloader.fetchLatestArm64Asset()
                    val apk = downloader.download(this@NativeRuntimeHostPreparationActivity, asset) { read, total ->
                        val percent = if (total > 0L) read * 100L / total else -1L
                        runOnUiThread {
                            status.text = if (percent >= 0L) {
                                String.format(Locale.US, "Downloading %s: %d%%", asset.name, percent)
                            } else {
                                String.format(Locale.US, "Downloading %s: %.1f MiB", asset.name, read / 1048576.0)
                            }
                        }
                    }
                    Triple(downloader, asset, apk)
                }
            }.onSuccess { (downloader, asset, apk) ->
                downloader.openInstaller(this@NativeRuntimeHostPreparationActivity, apk)
                status.text = "Downloaded ${asset.name}. Complete installation, open All-in-One once, then return here."
            }.onFailure { error ->
                status.text = error.message ?: "Unable to download WuxianPi All-in-One."
            }
        }
    }
}
