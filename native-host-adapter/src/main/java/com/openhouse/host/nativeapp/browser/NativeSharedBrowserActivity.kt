package com.openhouse.host.nativeapp.browser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.termux.app.browser.ControlledBrowserRuntime
import com.wuxianpi.browser.host.BrowserHostDescription

/** Native APK surface for the shared controlled-browser engine. */
class NativeSharedBrowserActivity : Activity() {
    private lateinit var browserContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "共享浏览器"
        browserContainer = FrameLayout(this)
        setContentView(browserContainer)

        val runtime = ControlledBrowserRuntime.getInstance()
        runtime.configureHost(BrowserHostDescription.nativeHost())
        runtime.ensureStarted(this)
        val browserView = runtime.getOrCreateView(this)
        (browserView.parent as? ViewGroup)?.removeView(browserView)
        browserContainer.addView(
            browserView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        NativeBrowserHostConnection.get(applicationContext).start()
    }

    override fun onResume() {
        super.onResume()
        ControlledBrowserRuntime.getInstance().getOrCreateView(this).onHostResume()
    }

    override fun onPause() {
        ControlledBrowserRuntime.getInstance().getOrCreateView(this).onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        val browserView = ControlledBrowserRuntime.getInstance().getOrCreateView(this)
        (browserView.parent as? ViewGroup)?.removeView(browserView)
        super.onDestroy()
    }
}
