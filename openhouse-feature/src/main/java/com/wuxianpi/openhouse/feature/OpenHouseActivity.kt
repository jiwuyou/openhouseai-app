package com.wuxianpi.openhouse.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.StartupTarget
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.workspace.ComponentWebResolution
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalog
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.desktop.DesktopCatalog
import com.wuxianpi.openhouse.feature.desktop.DesktopComponent
import com.wuxianpi.openhouse.feature.desktop.DesktopIconOverride
import com.wuxianpi.openhouse.feature.desktop.DesktopLayoutState
import com.wuxianpi.openhouse.feature.desktop.DesktopLayoutStore
import com.wuxianpi.openhouse.feature.desktop.ui.DesktopUiEntry
import com.wuxianpi.openhouse.feature.desktop.ui.OpenHouseDesktopView
import com.wuxianpi.openhouse.feature.workspace.EmbeddedWebPagePool
import com.wuxianpi.openhouse.feature.workspace.WorkspaceContent
import com.wuxianpi.openhouse.feature.workspace.WorkspaceNavigator
import com.wuxianpi.openhouse.feature.workspace.WorkspaceSidebar
import com.wuxianpi.openhouse.feature.workspace.WorkspaceWebMountGate

class OpenHouseActivity : AppCompatActivity() {
    private lateinit var drawer: DrawerLayout
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var doneEditing: Button
    private lateinit var host: OpenHouseFeatureHost
    private lateinit var layoutStore: DesktopLayoutStore
    private lateinit var startupStore: StartupRouteStore
    private lateinit var residency: DesktopResidencyController
    private lateinit var workspaceSidebar: WorkspaceSidebar
    private lateinit var webPagePool: EmbeddedWebPagePool
    private val workspaceNavigator = WorkspaceNavigator()
    private val retainedContents = LinkedHashMap<String, WorkspaceContent>()
    private val webMountGate = WorkspaceWebMountGate()
    private var activeWorkspaceContent: WorkspaceContent? = null
    private var desktopView: OpenHouseDesktopView? = null
    private var layoutState: DesktopLayoutState? = null
    private var components: List<DesktopComponent> = emptyList()
    private var registryComponents: List<OpenHouseComponent> = emptyList()
    private var currentRoute = ProductRoute.DESKTOP
    private var bindingPage = false
    private var released = false
    private var lastRegistryRefreshAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_house)
        host = OpenHouseFeatureHosts.from(this)
        layoutStore = DesktopLayoutStore(this)
        startupStore = StartupRouteStore(this)
        residency = DesktopResidencyController(DesktopResidencyController.MainThreadScheduler()) {
            releaseDesktopAndFinish()
        }
        bindViews()
        bindNavigation()
        refreshComponents()
        showDesktop()

        if (savedInstanceState == null) {
            val explicit = parseRoute(intent.getStringExtra(OpenHouseFeature.EXTRA_STARTUP_ROUTE))
            val startup = startupStore.resolve(explicit, host.capabilities())
            if (startup != ProductRoute.DESKTOP) {
                content.post { openRoute(startup) }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseRoute(intent.getStringExtra(OpenHouseFeature.EXTRA_STARTUP_ROUTE))?.let(::openRoute)
        requestDesktopRefresh(force = true)
    }

    override fun onStart() {
        super.onStart()
        residency.onReturn()
    }

    override fun onResume() {
        super.onResume()
        if (workspaceNavigator.current is WorkspaceDestination.Component && !webMountGate.isPending) webPagePool.onResume()
        else activeWorkspaceContent?.onResume()
        // Installation flows and external app setup return here while this Activity stays alive.
        requestDesktopRefresh()
    }

    override fun onPause() {
        activeWorkspaceContent?.onPause()
        webPagePool.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (!isFinishing && !released) residency.onLeave(startupStore.keepDesktopResident())
        super.onStop()
    }

    override fun onDestroy() {
        residency.onDestroy()
        releaseDesktopResources()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        webPagePool.onTrimMemory(level)
        retainedContents.values.toSet().forEach { it.onTrimMemory(level) }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            trimRetainedContents(keep = 1)
        }
        super.onTrimMemory(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
            return
        }
        desktopView?.takeIf { it.isEditMode() }?.let {
            it.setEditMode(false)
            return
        }
        if (workspaceNavigator.current is WorkspaceDestination.Component && webMountGate.cancelPendingForBack()) {
            showDesktop()
            return
        }
        if (webPagePool.canGoBack() && workspaceNavigator.current is WorkspaceDestination.Component) {
            webPagePool.goBack()
            return
        }
        if (activeWorkspaceContent?.onBackPressed() == true) return
        if (workspaceNavigator.current != WorkspaceDestination.Desktop) {
            showDesktop()
            return
        }
        super.onBackPressed()
    }

    private fun bindViews() {
        drawer = findViewById(R.id.oh_drawer)
        content = findViewById(R.id.oh_content)
        title = findViewById(R.id.oh_title)
        doneEditing = findViewById(R.id.oh_done_editing)
        workspaceSidebar = WorkspaceSidebar(this, findViewById(R.id.oh_workspace_apps), ::openWorkspaceDestination)
        webPagePool = EmbeddedWebPagePool(this, workspaceWebCallbacks())
        findViewById<Button>(R.id.oh_open_drawer).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<Button>(R.id.oh_top_desktop).setOnClickListener { showDesktop() }
        findViewById<Button>(R.id.oh_close_drawer).setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        doneEditing.setOnClickListener { desktopView?.setEditMode(false) }
    }

    private fun bindNavigation() {
        findViewById<View>(R.id.oh_nav_desktop).setOnClickListener { openRoute(ProductRoute.DESKTOP) }
        findViewById<View>(R.id.oh_nav_terminal).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            host.launchTerminal(this)
        }
        findViewById<View>(R.id.oh_nav_files).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            host.launchFiles(this)
        }
        findViewById<View>(R.id.oh_nav_service).setOnClickListener { openRoute(ProductRoute.SERVICE_CONTROL) }
        findViewById<View>(R.id.oh_nav_settings).setOnClickListener { openRoute(ProductRoute.SETTINGS) }
        findViewById<View>(R.id.oh_nav_service).isEnabled = host.capabilities().supports(ProductRoute.SERVICE_CONTROL)
    }

    private fun refreshComponents() {
        registryComponents = host.desktopComponents()
        components = DesktopCatalog.merge(registryComponents, host.capabilities())
        layoutState = layoutStore.merge(components)
        workspaceSidebar.bind(WorkspaceCatalog.applications(registryComponents, host.capabilities()))
    }

    private fun requestDesktopRefresh(
        force: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRegistryRefreshAt < REGISTRY_REFRESH_DEBOUNCE_MS) return
        lastRegistryRefreshAt = now
        host.refreshDesktopComponents {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshComponents()
                when (val destination = workspaceNavigator.current) {
                    WorkspaceDestination.Desktop -> bindDesktopState()
                    is WorkspaceDestination.Component -> {
                        findWorkspaceComponent(destination.normalizedComponentId)
                            ?.let(::openWorkspaceComponent) ?: showDesktop()
                    }
                    is WorkspaceDestination.Route -> Unit
                }
                onComplete?.invoke()
            }
        }
    }

    private fun openWorkspaceDestination(destination: WorkspaceDestination) {
        when (destination) {
            WorkspaceDestination.Desktop -> showDesktop()
            is WorkspaceDestination.Route -> openRoute(destination.route)
            is WorkspaceDestination.Component -> {
                findWorkspaceComponent(destination.normalizedComponentId)
                    ?.let(::openWorkspaceComponent) ?: showDesktop()
            }
        }
    }

    private fun findWorkspaceComponent(normalizedId: String): OpenHouseComponent? =
        registryComponents.firstOrNull { component ->
            component.visible && component.hasEntry() &&
                WorkspaceDestination.normalizeId(component.id) == normalizedId
        }

    private fun openRoute(route: ProductRoute) {
        drawer.closeDrawer(GravityCompat.START)
        when (route) {
            ProductRoute.DESKTOP -> showDesktop()
            ProductRoute.BASIC, ProductRoute.ADVANCED, ProductRoute.REPAIR -> showEmbeddedRoute(route)
            ProductRoute.SERVICE_CONTROL -> host.launchServiceControl(this)
            ProductRoute.PERMISSIONS -> host.launchHostRoute(this, route)
            ProductRoute.SETTINGS -> showSettings()
            ProductRoute.ABOUT -> showAbout()
            else -> host.launchHostRoute(this, route)
        }
    }

    private fun showDesktop() {
        webMountGate.cancel()
        workspaceNavigator.navigate(WorkspaceDestination.Desktop)
        currentRoute = ProductRoute.DESKTOP
        startupStore.recordLast(ProductRoute.DESKTOP)
        title.setText(R.string.oh_desktop)
        doneEditing.visibility = View.GONE
        pauseWorkspaceContent()
        webPagePool.onPause()
        content.removeAllViews()
        refreshComponents()

        val state = layoutState ?: layoutStore.merge(components)
        val view = OpenHouseDesktopView(this).apply {
            setGridSize(3, 4)
            setCallbacks(object : OpenHouseDesktopView.Callbacks {
                override fun onOpen(entry: DesktopUiEntry) = openDesktopEntry(entry)
                override fun onEdit(entry: DesktopUiEntry) = showEditDialog(entry.id)
                override fun onMove(entry: DesktopUiEntry, fromSlot: Int, toSlot: Int) {
                    layoutState = layoutStore.moveToSlot(components, entry.id, toSlot)
                    bindDesktopState()
                }
                override fun onPageChanged(pageIndex: Int, pageCount: Int) {
                    if (!bindingPage) layoutState = layoutStore.saveCurrentPage(components, pageIndex)
                }
                override fun onBlankLongPress() = setEditMode(true)
                override fun onEditModeChanged(editMode: Boolean) {
                    doneEditing.visibility = if (editMode) View.VISIBLE else View.GONE
                }
            })
        }
        desktopView = view
        content.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP })
        bindDesktopState(state)
        requestDesktopRefresh()
    }

    private fun showEmbeddedRoute(route: ProductRoute) {
        webMountGate.cancel()
        val destination = WorkspaceDestination.forRoute(route)
        startupStore.recordLast(route)
        val existing = retainedContents.remove(destination.stableKey)?.also {
            retainedContents[destination.stableKey] = it
        }
        val workspaceContent = existing ?: host.createEmbeddedContent(this, destination)?.also {
            retainedContents[destination.stableKey] = it
        }
        if (workspaceContent == null) {
            host.launchAiMode(this, route)
            return
        }

        workspaceNavigator.navigate(destination)
        currentRoute = route
        title.text = routeTitle(route)
        doneEditing.visibility = View.GONE
        desktopView = null
        webPagePool.onPause()
        attachWorkspaceContent(workspaceContent)
        trimRetainedContents(keep = MAX_RETAINED_NATIVE_CONTENTS)
    }

    private fun bindDesktopState(state: DesktopLayoutState? = layoutState) {
        val desktop = desktopView ?: return
        val value = state ?: return
        bindingPage = true
        try {
            desktop.setEntries(value.entries.map(DesktopUiEntry::from))
            desktop.setCurrentPage(value.currentPage, false)
        } finally {
            bindingPage = false
        }
    }

    private fun setEditMode(enabled: Boolean) {
        desktopView?.setEditMode(enabled)
        if (enabled) Toast.makeText(this, "桌面编辑模式：点击应用可改名、修改图标或隐藏。", Toast.LENGTH_SHORT).show()
    }

    private fun openDesktopEntry(uiEntry: DesktopUiEntry) {
        val entry = layoutState?.find(uiEntry.id)
        if (entry == null || !entry.component.enabled) {
            Toast.makeText(this, "应用当前不可用。", Toast.LENGTH_SHORT).show()
            return
        }
        val route = entry.component.route
        if (route != null) {
            openRoute(route)
        } else if (entry.component.source.entryType == OpenHouseComponent.EntryType.FILES) {
            host.launchFiles(this)
        } else if (entry.component.source.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
            openWorkspaceComponent(entry.component.source)
        } else {
            host.launchDynamicComponent(this, entry.component.source)
        }
    }

    private fun openWorkspaceComponent(component: OpenHouseComponent) {
        drawer.closeDrawer(GravityCompat.START)
        if (component.entryType != OpenHouseComponent.EntryType.WEBVIEW) {
            host.launchDynamicComponent(this, component)
            return
        }

        val destination = WorkspaceDestination.Component(component.id)
        val transition = workspaceNavigator.navigate(destination)
        currentRoute = ProductRoute.DESKTOP
        title.text = component.title.ifBlank { component.id }
        doneEditing.visibility = View.GONE
        desktopView = null
        pauseWorkspaceContent()
        webPagePool.onPause()
        webMountGate.begin(transition.generation)
        content.removeAllViews()
        content.addView(workspaceLoadingView(component.title), matchFrame())

        host.resolveComponentWeb(component) { resolution ->
            runOnUiThread {
                if (isFinishing || isDestroyed ||
                    !workspaceNavigator.isCurrent(transition.generation, destination)
                ) return@runOnUiThread
                when (resolution) {
                    is ComponentWebResolution.Resolved -> showResolvedWebComponent(component, resolution.url, transition.generation)
                    is ComponentWebResolution.Unavailable -> showResolvedWebComponent(component, "", transition.generation)
                    ComponentWebResolution.DelegateToHost -> {
                        webMountGate.complete(transition.generation)
                        host.launchDynamicComponent(this, component)
                        showDesktop()
                    }
                }
            }
        }
    }

    private fun showResolvedWebComponent(component: OpenHouseComponent, resolvedUrl: String, generation: Long) {
        val destination = WorkspaceDestination.Component(component.id)
        if (!workspaceNavigator.isCurrent(generation, destination) || !webMountGate.complete(generation)) return
        title.text = component.title.ifBlank { component.id }
        content.removeAllViews()
        val webHost = FrameLayout(this)
        content.addView(webHost, matchFrame())
        webPagePool.show(ComponentWebLaunchArgs.from(component, resolvedUrl), webHost)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) webPagePool.onResume()
    }

    private fun showEditDialog(appId: String) {
        val entry = layoutState?.find(appId) ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val titleInput = EditText(this).apply {
            hint = entry.component.displayTitle()
            setText(entry.override.title)
            maxLines = 1
        }
        val iconInput = EditText(this).apply {
            hint = entry.component.displayIconLabel()
            setText(entry.override.icon.label)
            maxLines = 1
        }
        panel.addView(label("显示名称"))
        panel.addView(titleInput, matchWrap())
        panel.addView(label("图标文字").apply { setPadding(0, dp(12), 0, 0) })
        panel.addView(iconInput, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setView(panel)
            .setPositiveButton("保存", null)
            .setNeutralButton("恢复默认", null)
            .setNegativeButton(if (entry.component.fixed) "关闭" else "隐藏", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                layoutState = layoutStore.updateOverride(
                    components,
                    entry.id,
                    titleInput.text?.toString().orEmpty(),
                    DesktopIconOverride(label = iconInput.text?.toString().orEmpty()),
                )
                bindDesktopState()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                layoutState = layoutStore.resetApp(components, entry.id)
                bindDesktopState()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (!entry.component.fixed) {
                    layoutState = layoutStore.hide(components, entry.id, true)
                    bindDesktopState()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showSettings() {
        webMountGate.cancel()
        workspaceNavigator.navigate(WorkspaceDestination.Route(ProductRoute.SETTINGS))
        currentRoute = ProductRoute.SETTINGS
        title.setText(R.string.oh_settings)
        doneEditing.visibility = View.GONE
        desktopView = null
        pauseWorkspaceContent()
        webPagePool.onPause()
        content.removeAllViews()

        val scroll = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(heading("默认打开"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val selected = startupStore.target()
        StartupTarget.values().forEach { target ->
            group.addView(RadioButton(this).apply {
                text = when (target) {
                    StartupTarget.LAST_PAGE -> "上次退出页"
                    StartupTarget.DESKTOP -> "桌面"
                    StartupTarget.BASIC -> "基础模式"
                    StartupTarget.ADVANCED -> "高级 UI"
                }
                tag = target
                isChecked = target == selected
            })
        }
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val checked = radioGroup.findViewById<RadioButton>(checkedId)
            (checked?.tag as? StartupTarget)?.let(startupStore::setTarget)
        }
        panel.addView(group, matchWrap())

        panel.addView(heading("桌面驻留").apply { setPadding(0, dp(24), 0, 0) })
        val keepResident = Switch(this).apply {
            text = "桌面常驻"
            isChecked = startupStore.keepDesktopResident()
            setOnCheckedChangeListener { _, checked ->
                startupStore.setKeepDesktopResident(checked)
                if (checked) residency.onReturn()
            }
        }
        panel.addView(keepResident, matchWrap())
        panel.addView(body("关闭时，离开桌面 10 分钟后释放桌面 Activity 和显示资源；返回桌面会取消计时。"))

        panel.addView(actionButton("刷新桌面组件") {
            requestDesktopRefresh(force = true) {
                Toast.makeText(this, "桌面组件已刷新。", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(actionButton("重置桌面布局") {
            layoutState = layoutStore.reset(components)
            Toast.makeText(this, "桌面布局已重置。", Toast.LENGTH_SHORT).show()
        })
        panel.addView(actionButton(getString(R.string.oh_open_host_settings)) {
            host.launchHostRoute(this, ProductRoute.SETTINGS)
        })
        content.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun showAbout() {
        webMountGate.cancel()
        workspaceNavigator.navigate(WorkspaceDestination.Route(ProductRoute.ABOUT))
        currentRoute = ProductRoute.ABOUT
        title.setText(R.string.oh_about)
        doneEditing.visibility = View.GONE
        desktopView = null
        pauseWorkspaceContent()
        webPagePool.onPause()
        content.removeAllViews()

        val scroll = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(heading(getString(R.string.oh_about_product_name)))
        panel.addView(body(getString(R.string.oh_about_description)))
        panel.addView(body(getString(R.string.oh_about_version, appVersion())))

        panel.addView(heading(getString(R.string.oh_about_repositories)).apply {
            setPadding(0, dp(24), 0, 0)
        })
        productRepositories().forEach { (name, url) -> panel.addView(linkButton(name, url)) }

        panel.addView(heading(getString(R.string.oh_about_acknowledgements)).apply {
            setPadding(0, dp(24), 0, 0)
        })
        acknowledgementRepositories().forEach { (name, url) -> panel.addView(linkButton(name, url)) }

        content.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("").ifBlank { getString(R.string.oh_about_version_unknown) }

    private fun productRepositories(): List<Pair<String, String>> = listOf(
        "Android Host" to "https://github.com/jiwuyou/openhouseai-app",
        "WuxianPi Runtime" to "https://github.com/jiwuyou/wuxianpi",
        "service-manager" to "https://github.com/jiwuyou/service-manager",
        "WuxianPi Rescue" to "https://github.com/jiwuyou/wuxianpi-rescue",
        "OpenHouse Docs" to "https://github.com/jiwuyou/openhouse-docs",
    )

    private fun acknowledgementRepositories(): List<Pair<String, String>> = listOf(
        "Operit" to "https://github.com/AAswordman/Operit",
        "Termux" to "https://github.com/termux/termux-app",
        "Pi" to "https://github.com/earendil-works/pi",
        "OpenAI Codex" to "https://github.com/openai/codex",
    )

    private fun linkButton(name: String, url: String) = Button(this).apply {
        text = "$name\n$url"
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(ContextCompat.getColor(this@OpenHouseActivity, R.color.oh_text))
        textSize = 13f
        minHeight = dp(58)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setBackgroundResource(R.drawable.oh_button_background)
        setOnClickListener { openExternalUrl(url) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun openExternalUrl(url: String) {
        openExternalUri(Uri.parse(url))
    }

    private fun openExternalUri(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.oh_about_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun workspaceWebCallbacks() = object : EmbeddedWebPagePool.Callbacks {
        override fun onStateChanged(args: ComponentWebLaunchArgs, state: ComponentWebPageState) {
            if (workspaceNavigator.current == WorkspaceDestination.Component(args.componentId)) {
                title.text = args.title
            }
        }

        override fun onOpenControl(args: ComponentWebLaunchArgs) {
            host.launchComponentControl(this@OpenHouseActivity, args)
        }

        override fun onOpenMaintenance() {
            host.launchMaintenance(this@OpenHouseActivity)
        }

        override fun onOpenExternal(uri: Uri) {
            openExternalUri(uri)
        }

        override fun onCopyAddress(args: ComponentWebLaunchArgs, address: String) {
            if (address.isBlank()) {
                Toast.makeText(this@OpenHouseActivity, R.string.oh_component_web_no_address, Toast.LENGTH_SHORT).show()
                return
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText(args.title, address))
            Toast.makeText(this@OpenHouseActivity, R.string.oh_address_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachWorkspaceContent(workspaceContent: WorkspaceContent) {
        if (activeWorkspaceContent !== workspaceContent) activeWorkspaceContent?.onPause()
        activeWorkspaceContent = workspaceContent
        (workspaceContent.view.parent as? ViewGroup)?.removeView(workspaceContent.view)
        content.removeAllViews()
        content.addView(workspaceContent.view, matchFrame())
        if (hasWindowFocus()) workspaceContent.onResume()
    }

    private fun pauseWorkspaceContent() {
        activeWorkspaceContent?.onPause()
        activeWorkspaceContent = null
    }

    private fun trimRetainedContents(keep: Int) {
        while (retainedContents.size > keep.coerceAtLeast(1)) {
            val oldest = retainedContents.entries.firstOrNull { it.value !== activeWorkspaceContent } ?: return
            retainedContents.remove(oldest.key)
            (oldest.value.view.parent as? ViewGroup)?.removeView(oldest.value.view)
            oldest.value.destroy()
        }
    }

    private fun workspaceLoadingView(componentTitle: String): View = TextView(this).apply {
        text = getString(R.string.oh_workspace_connecting, componentTitle.ifBlank { getString(R.string.oh_app_name) })
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@OpenHouseActivity, R.color.oh_text_secondary))
        textSize = 15f
    }

    private fun routeTitle(route: ProductRoute): String = when (route) {
        ProductRoute.BASIC -> getString(R.string.oh_basic)
        ProductRoute.ADVANCED -> getString(R.string.oh_advanced)
        ProductRoute.REPAIR -> getString(R.string.oh_repair)
        ProductRoute.SETTINGS -> getString(R.string.oh_settings)
        ProductRoute.ABOUT -> getString(R.string.oh_about)
        else -> getString(R.string.oh_app_name)
    }

    private fun releaseDesktopAndFinish() {
        if (released || isFinishing) return
        released = true
        releaseDesktopResources()
        host.onDesktopReleased()
        finish()
    }

    private fun releaseDesktopResources() {
        desktopView?.releaseResources()
        desktopView = null
        layoutState = null
        components = emptyList()
        registryComponents = emptyList()
        activeWorkspaceContent?.onPause()
        activeWorkspaceContent = null
        retainedContents.values.forEach { workspaceContent ->
            (workspaceContent.view.parent as? ViewGroup)?.removeView(workspaceContent.view)
            workspaceContent.destroy()
        }
        retainedContents.clear()
        if (::webPagePool.isInitialized) webPagePool.destroy()
        if (::content.isInitialized) content.removeAllViews()
    }

    private fun parseRoute(value: String?): ProductRoute? = try {
        value?.trim()?.uppercase()?.let(ProductRoute::valueOf)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@OpenHouseActivity, R.color.oh_text))
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@OpenHouseActivity, R.color.oh_text_secondary))
        textSize = 13f
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@OpenHouseActivity, R.color.oh_text_secondary))
        textSize = 14f
        setPadding(0, dp(8), 0, 0)
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setBackgroundResource(R.drawable.oh_button_background)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(10)
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchFrame() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REGISTRY_REFRESH_DEBOUNCE_MS = 750L
        const val MAX_RETAINED_NATIVE_CONTENTS = 2
    }
}
