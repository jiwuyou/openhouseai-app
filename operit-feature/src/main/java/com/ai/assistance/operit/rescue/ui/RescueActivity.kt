package com.ai.assistance.operit.rescue.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.host.control.OperitShutdownController
import com.wuxianpi.openhouse.core.rescue.RescueControlProtocol
import com.wuxianpi.openhouse.core.rescue.RescueControlStateStore
import com.wuxianpi.openhouse.core.workspace.ComponentServiceSummary
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalog
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalogEntry
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.HostEdition
import com.wuxianpi.openhouse.feature.OpenHouseFeature
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHostProvider
import com.wuxianpi.openhouse.feature.pages.BuiltInPageRegistry
import com.wuxianpi.openhouse.feature.workspace.WorkspacePreferenceStore
import com.wuxianpi.openhouse.feature.workspace.WorkspaceSidebar
import com.ai.assistance.operit.R
import com.ai.assistance.operit.rescue.remote.RescueAssistHostPhase
import com.ai.assistance.operit.rescue.remote.RescueRemoteAssistController
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.ui.main.OperitHostMode
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.workspace.OperitWorkspaceContentFactory
import com.ai.assistance.operit.workspace.OperitWorkspaceSpec

/**
 * Entry point for the Android-local Rescue AI.
 *
 * This intentionally hosts the complete Operit UI instead of adding a second maintenance
 * dashboard.  The activity lives in its own process, and ChatViewModel uses the process marker to
 * select ChatRuntimeSlot.RESCUE.  The normal WuxianPi/Node UI is not changed.
 */
class RescueActivity : ComponentActivity() {
    private lateinit var shellDrawer: DrawerLayout
    private lateinit var rescueContentHost: FrameLayout
    private lateinit var openHouseHost: OpenHouseFeatureHost
    private lateinit var workspaceSidebar: WorkspaceSidebar
    private lateinit var workspacePreferences: WorkspacePreferenceStore
    private lateinit var pageRegistry: BuiltInPageRegistry
    private var workspaceEntries: List<WorkspaceCatalogEntry> = emptyList()
    private var serviceStates: Map<String, ComponentServiceSummary> = emptyMap()
    private var pendingServiceIds: Set<String> = emptySet()
    private var rescueContent: com.ai.assistance.operit.workspace.OperitWorkspaceContent? = null
    private var remoteAssistStopRequested = false
    private var rescueShutdownReceiverRegistered = false

    private val rescueShutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RescueControlProtocol.ACTION_REQUEST_SHUTDOWN) return
            closeRescueAssistant()
        }
    }

    companion object {
        const val ACTION_OPEN_RESCUE = "com.wuxianpi.action.OPEN_RESCUE_AI"
        const val EXTRA_RESCUE_ENTRY = "com.wuxianpi.extra.RESCUE_ENTRY"
        const val EXTRA_HOST_RETURN_ACTIVITY = MainActivity.EXTRA_HOST_RETURN_ACTIVITY
        const val EXTRA_HOST_RETURN_INTENT = "com.wuxianpi.extra.RESCUE_HOST_RETURN_INTENT"
        const val EXTRA_PENDING_ACTION_ID = "com.wuxianpi.extra.RESCUE_ACTION_ID"
        const val EXTRA_PENDING_ACTION_PROMPT = "com.wuxianpi.extra.RESCUE_ACTION_PROMPT"
        const val RESOURCE_UPDATE_PROMPT =
            "请检查 APK 配套状态；先使用最新版 APK 配套更新插件，只确认或修复 Android 私有 service-manager 连接，不更新 WuxianPi 或 Termux 运行资源。"
        const val RESCUE_PROCESS_SUFFIX = ":rescue_ui"
        private const val TAG = "RescueActivity"

        fun createIntent(context: Context): Intent =
            createIntent(context, hostReturnActivity = null)

        fun createIntent(context: Context, hostReturnActivity: String?): Intent =
            Intent(context, RescueActivity::class.java).apply {
                putExtra(EXTRA_RESCUE_ENTRY, true)
                hostReturnActivity?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    putExtra(EXTRA_HOST_RETURN_ACTIVITY, it)
                }
            }

        /** Returns true for an Activity/Context running the Android-local rescue UI. */
        fun isRescueContext(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is RescueActivity) return true
                current = current.baseContext
            }
            if (current is RescueActivity) return true

            val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }
                    ?.processName
            }
            return processName == "${context.packageName}$RESCUE_PROCESS_SUFFIX"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptPendingAction(intent)
        registerRescueShutdownReceiver()
        setContentView(R.layout.activity_rescue_shell)
        shellDrawer = findViewById(R.id.rescue_shell_drawer)
        rescueContentHost = findViewById(R.id.rescue_content)
        openHouseHost = (application as? OpenHouseFeatureHostProvider)
            ?.openHouseFeatureHost()
            ?: error("OpenHouse host is unavailable")
        findViewById<android.widget.TextView>(R.id.rescue_shell_subtitle).text = when (openHouseHost.edition()) {
            HostEdition.TERMUX_EMBEDDED -> "AIO · 内置 Termux"
            HostEdition.NATIVE_ANDROID -> "Native · 连接外部 Termux"
        }
        workspacePreferences = WorkspacePreferenceStore(this)
        pageRegistry = BuiltInPageRegistry(this)
        bindShell()
        refreshWorkspaceSidebar(force = true)
        rescueContent = OperitWorkspaceContentFactory.create(
            this,
            OperitWorkspaceSpec(
                hostMode = OperitHostMode.RESCUE,
                initialNavItem = NavItem.AiChat,
                toolHandler = AIToolHandler.getInstance(this),
                showTopBar = true,
                applyTopBarInsets = false,
                applySystemBars = false,
                onReturnToHostMainMenu = ::returnToHostMainMenu,
                onCloseHostedOperit = ::closeRescueAssistant,
                hostedCloseLabel = getString(R.string.rescue_ai_close),
            ),
        ).also { rescueContentHost.addView(it.view) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    shellDrawer.isDrawerOpen(GravityCompat.START) ->
                        shellDrawer.closeDrawer(GravityCompat.START)
                    rescueContent?.onBackPressed() == true -> Unit
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPendingAction(intent)
    }

    override fun onResume() {
        super.onResume()
        rescueContent?.onResume()
        RescueControlStateStore.markForeground(applicationContext)
        if (::workspaceSidebar.isInitialized) bindWorkspaceSidebar()
    }

    override fun onPause() {
        rescueContent?.onPause()
        if (!isFinishing && !OperitShutdownController.isShutdownInProgress()) {
            RescueControlStateStore.markBackground(applicationContext)
        }
        super.onPause()
    }

    private fun returnToHostMainMenu() {
        val requestedActivity =
            intent?.getStringExtra(EXTRA_HOST_RETURN_ACTIVITY)?.trim().orEmpty()
        @Suppress("DEPRECATION")
        val suppliedIntent = intent?.getParcelableExtra<Intent>(EXTRA_HOST_RETURN_INTENT)
        val hostIntent = suppliedIntent ?: if (requestedActivity.isNotEmpty()) {
            Intent().setClassName(packageName, requestedActivity)
        } else {
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName)
        }
        hostIntent.addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching {
            startActivity(hostIntent)
            overridePendingTransition(0, 0)
        }
            .onFailure { AppLogger.e(TAG, "Failed to return from Rescue AI to host main activity", it) }
    }

    private fun closeRescueAssistant() {
        RescueControlStateStore.markStopping(applicationContext)
        stopRemoteAssistanceIfActive()
        OperitShutdownController.shutdownRescueFromActivity(
            activity = this,
            rescueProcessSuffix = RESCUE_PROCESS_SUFFIX,
            beforeFinish = { RescueControlStateStore.markStopped(applicationContext) },
        )
    }

    override fun onDestroy() {
        if (rescueShutdownReceiverRegistered) {
            runCatching { unregisterReceiver(rescueShutdownReceiver) }
            rescueShutdownReceiverRegistered = false
        }
        if (isFinishing && !isChangingConfigurations) {
            stopRemoteAssistanceIfActive()
        }
        pageRegistry.close()
        rescueContent?.destroy()
        rescueContent = null
        super.onDestroy()
    }

    private fun bindShell() {
        shellDrawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: android.view.View) {
                if (drawerView.id == R.id.rescue_shell_navigation_drawer) {
                    refreshWorkspaceSidebar(loadServices = true)
                }
            }
        })
        findViewById<Button>(R.id.rescue_shell_menu).setOnClickListener {
            shellDrawer.openDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.rescue_shell_top_desktop).setOnClickListener {
            returnToHostMainMenu()
        }
        findViewById<Button>(R.id.rescue_shell_drawer_close).setOnClickListener {
            shellDrawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.rescue_shell_nav_desktop).setOnClickListener {
            shellDrawer.closeDrawer(GravityCompat.START)
            returnToHostMainMenu()
        }
        findViewById<Button>(R.id.rescue_shell_nav_close).setOnClickListener { closeRescueAssistant() }
        findViewById<Button>(R.id.rescue_shell_nav_terminal).setOnClickListener {
            shellDrawer.closeDrawer(GravityCompat.START)
            openHouseHost.launchTerminal(this)
        }
        findViewById<Button>(R.id.rescue_shell_nav_files).setOnClickListener {
            shellDrawer.closeDrawer(GravityCompat.START)
            openHouseHost.launchFiles(this)
        }
        findViewById<Button>(R.id.rescue_shell_nav_service).setOnClickListener {
            shellDrawer.closeDrawer(GravityCompat.START)
            openHouseHost.launchServiceControl(this)
        }
        findViewById<Button>(R.id.rescue_shell_nav_settings).setOnClickListener {
            shellDrawer.closeDrawer(GravityCompat.START)
            openHouseRoute(ProductRoute.SETTINGS)
        }
        workspaceSidebar = WorkspaceSidebar(
            context = this,
            container = findViewById(R.id.rescue_shell_workspace_apps),
            onSelected = ::openWorkspaceDestination,
            onCloseRescue = ::closeRescueAssistant,
            onPinnedChanged = { entry, pinned ->
                workspacePreferences.setPinned(entry.component, pinned)
                bindWorkspaceSidebar()
            },
            onServiceRunningChanged = ::setWorkspaceServiceRunning,
        )
    }

    private fun refreshWorkspaceSidebar(
        loadServices: Boolean = false,
        force: Boolean = false,
    ) {
        pageRegistry.refreshAsync(force = force) {
            openHouseHost.refreshDesktopComponents {
                workspaceEntries = WorkspaceCatalog.applications(
                    openHouseHost.desktopComponents() + pageRegistry.components(),
                    openHouseHost.capabilities(),
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    bindWorkspaceSidebar()
                    if (loadServices) loadWorkspaceServiceStates()
                }
            }
        }
    }

    private fun bindWorkspaceSidebar() {
        if (!::workspaceSidebar.isInitialized) return
        val pinned = workspaceEntries.asSequence()
            .filter { workspacePreferences.isPinned(it.component) }
            .mapTo(linkedSetOf()) { WorkspaceDestination.normalizeId(it.component.id) }
        workspaceSidebar.bind(
            workspaceEntries,
            RescueControlStateStore.read(applicationContext).effectiveState,
            pinned,
            serviceStates,
            pendingServiceIds,
        )
    }

    private fun loadWorkspaceServiceStates() {
        openHouseHost.loadComponentServiceStates(workspaceEntries.map { it.component }) { loaded ->
            runOnUiThread {
                serviceStates = loaded
                bindWorkspaceSidebar()
            }
        }
    }

    private fun openWorkspaceDestination(destination: WorkspaceDestination) {
        shellDrawer.closeDrawer(GravityCompat.START)
        when (destination) {
            WorkspaceDestination.Desktop -> returnToHostMainMenu()
            is WorkspaceDestination.Route -> {
                if (destination.route != ProductRoute.REPAIR) openHouseRoute(destination.route)
            }
            is WorkspaceDestination.Component -> openHouseComponent(destination.normalizedComponentId)
        }
    }

    private fun openHouseRoute(route: ProductRoute) {
        val intent = OpenHouseFeature.createIntent(this, route).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            startActivity(intent)
            overridePendingTransition(0, 0)
        }.onFailure { error -> AppLogger.e(TAG, "Failed to open OpenHouse route $route", error) }
    }

    private fun openHouseComponent(componentId: String) {
        val intent = OpenHouseFeature.createIntent(this).apply {
            putExtra(OpenHouseFeature.EXTRA_STARTUP_COMPONENT_ID, componentId)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            startActivity(intent)
            overridePendingTransition(0, 0)
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to open OpenHouse component $componentId", error)
        }
    }

    private fun setWorkspaceServiceRunning(entry: WorkspaceCatalogEntry, running: Boolean) {
        val id = WorkspaceDestination.normalizeId(entry.component.id)
        if (id in pendingServiceIds) return
        pendingServiceIds += id
        bindWorkspaceSidebar()
        openHouseHost.setComponentServicesRunning(entry.component, running) { result ->
            runOnUiThread {
                pendingServiceIds -= id
                if (result.success) serviceStates -= id
                bindWorkspaceSidebar()
                if (shellDrawer.isDrawerOpen(GravityCompat.START)) loadWorkspaceServiceStates()
            }
        }
    }

    private fun registerRescueShutdownReceiver() {
        if (rescueShutdownReceiverRegistered) return
        val filter = IntentFilter(RescueControlProtocol.ACTION_REQUEST_SHUTDOWN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rescueShutdownReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(rescueShutdownReceiver, filter)
        }
        rescueShutdownReceiverRegistered = true
    }

    private fun stopRemoteAssistanceIfActive() {
        if (remoteAssistStopRequested) return
        val remoteAssistController = RescueRemoteAssistController.getInstance(this)
        if (remoteAssistController.state.value.phase != RescueAssistHostPhase.IDLE) {
            remoteAssistStopRequested = true
            remoteAssistController.stopSharing()
        }
    }

    private fun acceptPendingAction(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PENDING_ACTION_ID).orEmpty()
        val prompt = intent?.getStringExtra(EXTRA_PENDING_ACTION_PROMPT).orEmpty()
        PendingRescueActionHandler.set(id, prompt)
    }
}
