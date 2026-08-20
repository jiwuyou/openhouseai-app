package com.wuxianpi.openhouse.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.feature.workspace.EmbeddedWebPagePool

/** Compatibility Activity for old intents and deep links. Daily navigation uses OpenHouseActivity. */
class OpenHouseComponentWebActivity : AppCompatActivity() {
    private lateinit var host: OpenHouseFeatureHost
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var browserHost: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private lateinit var controlButton: Button
    private lateinit var pagePool: EmbeddedWebPagePool
    private lateinit var openModes: ImageButton
    private lateinit var floatingWebViewHost: FloatingWebViewHost
    private lateinit var floatingWindowStore: FloatingWindowStore
    private var returnToSmallAppUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_component_web)
        host = OpenHouseFeatureHosts.from(this)
        pagePool = EmbeddedWebPagePool(this, pageCallbacks())
        floatingWindowStore = FloatingWindowStore(this)
        bindViews()
        floatingWebViewHost = FloatingWebViewHost(
            context = this,
            parent = overlayHost,
            store = floatingWindowStore,
            callbacks = object : FloatingWebViewHost.Callbacks {
                override fun onBrowser(url: String) = openExternal(Uri.parse(url))
            },
        )
        showIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        pagePool.onResume()
        floatingWebViewHost.onResume()
    }

    override fun onPause() {
        pagePool.onPause()
        floatingWebViewHost.onPause()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        pagePool.onTrimMemory(level)
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        pagePool.destroy()
        floatingWebViewHost.dispose()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::floatingWebViewHost.isInitialized && floatingWebViewHost.isOpen) {
            floatingWebViewHost.close()
            updatePresentationState()
            return
        }
        if (!pagePool.goBack()) super.onBackPressed()
    }

    private fun bindViews() {
        titleView = findViewById(R.id.oh_component_web_title)
        statusView = findViewById(R.id.oh_component_web_status)
        browserHost = findViewById(R.id.oh_component_web_host)
        overlayHost = findViewById(R.id.oh_component_web_overlay_host)
        openModes = findViewById(R.id.oh_component_web_modes)
        controlButton = findViewById(R.id.oh_component_web_control)
        findViewById<Button>(R.id.oh_component_web_desktop).setOnClickListener { returnToDesktop() }
        findViewById<Button>(R.id.oh_component_web_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.oh_component_web_refresh).setOnClickListener { refreshOrReturnToSmallApp() }
        openModes.setOnClickListener { showWebPresentationMenu() }
        findViewById<Button>(R.id.oh_component_web_copy).setOnClickListener { copyActiveAddress() }
        findViewById<Button>(R.id.oh_component_web_maintenance).setOnClickListener {
            host.launchMaintenance(this)
        }
        controlButton.setOnClickListener {
            pagePool.activeArgs?.let { args -> host.launchComponentControl(this, args) }
        }
    }

    private fun showIntent(intent: Intent) {
        val args = ComponentWebLaunchArgs.fromIntent(intent)
        if (args == null) {
            Toast.makeText(this, R.string.oh_component_web_invalid, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        titleView.text = args.title
        controlButton.text = args.controlTitle.ifEmpty { getString(R.string.oh_service_control) }
        controlButton.visibility = if (args.hasControlEntry) View.VISIBLE else View.GONE
        pagePool.show(args, browserHost)
        updatePresentationState()
    }

    private fun pageCallbacks() = object : EmbeddedWebPagePool.Callbacks {
        override fun onStateChanged(args: ComponentWebLaunchArgs, state: ComponentWebPageState) {
            statusView.text = when (state.phase) {
                ComponentWebLoadPhase.IDLE -> getString(R.string.oh_component_web_address, state.url)
                ComponentWebLoadPhase.LOADING -> getString(R.string.oh_component_web_connecting, state.url)
                ComponentWebLoadPhase.CONNECTED -> getString(R.string.oh_component_web_connected, state.url)
                ComponentWebLoadPhase.FAILED -> getString(R.string.oh_component_web_disconnected, state.url)
            }
            updatePresentationState()
        }

        override fun onOpenControl(args: ComponentWebLaunchArgs) {
            host.launchComponentControl(this@OpenHouseComponentWebActivity, args)
        }

        override fun onOpenMaintenance() {
            host.launchMaintenance(this@OpenHouseComponentWebActivity)
        }

        override fun onOpenExternal(uri: Uri) {
            openExternal(uri)
        }

        override fun onCopyAddress(args: ComponentWebLaunchArgs, address: String) {
            copyAddress(args.title, address)
        }
    }

    private fun copyActiveAddress() {
        val args = pagePool.activeArgs ?: return
        copyAddress(args.title, pagePool.activeAddress)
    }

    private fun refreshOrReturnToSmallApp() {
        val returnUrl = returnToSmallAppUrl
        if (!returnUrl.isNullOrBlank()) {
            pagePool.loadActiveUrl(returnUrl)
            returnToSmallAppUrl = null
        } else {
            pagePool.reloadActive()
        }
        updatePresentationState()
    }

    private fun showWebPresentationMenu() {
        val address = pagePool.activeAddress
        val entries = buildList {
            add(WebPresentationMenu.Entry(getString(R.string.oh_current_page)) {
                floatingWebViewHost.close()
                returnToSmallAppUrl = null
                updatePresentationState()
            })
            if (address.isNotBlank()) {
                add(WebPresentationMenu.Entry(getString(R.string.oh_open_floating)) {
                    pagePool.activeArgs?.let { args ->
                        val returnUrl = pagePool.previousAddress.ifBlank { args.loadUrl }
                        floatingWebViewHost.open(
                            FloatingWindowSnapshot(
                                title = args.title,
                                conversationUrl = address,
                                returnUrl = returnUrl,
                                x = 0,
                                y = 0,
                                width = dp(340),
                                height = dp(460),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        returnToSmallAppUrl = returnUrl
                        updatePresentationState()
                    }
                })
            }
            floatingWindowStore.load()?.let { last ->
                add(WebPresentationMenu.Entry(getString(R.string.oh_open_last_floating)) {
                    floatingWebViewHost.open(last)
                    returnToSmallAppUrl = last.returnUrl.ifBlank { null }
                    updatePresentationState()
                })
            }
            if (address.isNotBlank()) {
                add(WebPresentationMenu.Entry(getString(R.string.oh_open_in_browser)) {
                    openExternal(Uri.parse(address))
                })
            }
        }
        WebPresentationMenu(this).show(openModes, entries)
    }

    private fun updatePresentationState() {
        val refresh = findViewById<Button>(R.id.oh_component_web_refresh)
        refresh.text = if (returnToSmallAppUrl != null) {
            getString(R.string.oh_return_to_small_app)
        } else {
            getString(R.string.oh_refresh)
        }
        openModes.isEnabled = pagePool.activeAddress.isNotBlank() || floatingWindowStore.load() != null
        openModes.alpha = if (openModes.isEnabled) 1f else 0.45f
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun copyAddress(label: String, address: String) {
        if (address.isBlank()) {
            Toast.makeText(this, R.string.oh_component_web_no_address, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, address))
        Toast.makeText(this, R.string.oh_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(this, R.string.oh_component_web_external_failed, Toast.LENGTH_SHORT).show()
            }
    }

    private fun returnToDesktop() {
        startActivity(OpenHouseFeature.createIntent(this, ProductRoute.DESKTOP).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
