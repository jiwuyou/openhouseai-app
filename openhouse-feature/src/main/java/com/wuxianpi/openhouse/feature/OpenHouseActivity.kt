package com.wuxianpi.openhouse.feature

import android.app.Activity
import android.os.Bundle
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
import com.wuxianpi.openhouse.core.ProductRoute
import com.wuxianpi.openhouse.core.StartupTarget
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.feature.desktop.DesktopCatalog
import com.wuxianpi.openhouse.feature.desktop.DesktopComponent
import com.wuxianpi.openhouse.feature.desktop.DesktopIconOverride
import com.wuxianpi.openhouse.feature.desktop.DesktopLayoutEntry
import com.wuxianpi.openhouse.feature.desktop.DesktopLayoutState
import com.wuxianpi.openhouse.feature.desktop.DesktopLayoutStore
import com.wuxianpi.openhouse.feature.desktop.ui.DesktopUiEntry
import com.wuxianpi.openhouse.feature.desktop.ui.OpenHouseDesktopView

class OpenHouseActivity : AppCompatActivity() {
    private lateinit var drawer: DrawerLayout
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var doneEditing: Button
    private lateinit var host: OpenHouseFeatureHost
    private lateinit var layoutStore: DesktopLayoutStore
    private lateinit var startupStore: StartupRouteStore
    private lateinit var residency: DesktopResidencyController
    private var desktopView: OpenHouseDesktopView? = null
    private var layoutState: DesktopLayoutState? = null
    private var components: List<DesktopComponent> = emptyList()
    private var currentRoute = ProductRoute.DESKTOP
    private var bindingPage = false
    private var released = false

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
    }

    override fun onStart() {
        super.onStart()
        residency.onReturn()
    }

    override fun onStop() {
        if (!isFinishing && !released) residency.onLeave(startupStore.keepDesktopResident())
        super.onStop()
    }

    override fun onDestroy() {
        residency.onDestroy()
        if (isFinishing) releaseDesktopResources()
        super.onDestroy()
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
        if (currentRoute == ProductRoute.SETTINGS) {
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
        findViewById<Button>(R.id.oh_open_drawer).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<Button>(R.id.oh_close_drawer).setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        doneEditing.setOnClickListener { desktopView?.setEditMode(false) }
    }

    private fun bindNavigation() {
        findViewById<View>(R.id.oh_nav_desktop).setOnClickListener { openRoute(ProductRoute.DESKTOP) }
        findViewById<View>(R.id.oh_nav_basic).setOnClickListener { openRoute(ProductRoute.BASIC) }
        findViewById<View>(R.id.oh_nav_advanced).setOnClickListener { openRoute(ProductRoute.ADVANCED) }
        findViewById<View>(R.id.oh_nav_repair).setOnClickListener { openRoute(ProductRoute.REPAIR) }
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
        val capabilities = host.capabilities()
        findViewById<View>(R.id.oh_nav_basic).isEnabled = capabilities.supports(ProductRoute.BASIC)
        findViewById<View>(R.id.oh_nav_advanced).isEnabled = capabilities.supports(ProductRoute.ADVANCED)
        findViewById<View>(R.id.oh_nav_repair).isEnabled = capabilities.supports(ProductRoute.REPAIR)
        findViewById<View>(R.id.oh_nav_service).isEnabled = capabilities.supports(ProductRoute.SERVICE_CONTROL)
    }

    private fun refreshComponents() {
        components = DesktopCatalog.merge(host.desktopComponents(), host.capabilities())
        layoutState = layoutStore.merge(components)
    }

    private fun openRoute(route: ProductRoute) {
        drawer.closeDrawer(GravityCompat.START)
        when (route) {
            ProductRoute.DESKTOP -> showDesktop()
            ProductRoute.BASIC, ProductRoute.ADVANCED, ProductRoute.REPAIR -> {
                startupStore.recordLast(route)
                host.launchAiMode(this, route)
            }
            ProductRoute.SERVICE_CONTROL -> host.launchServiceControl(this)
            ProductRoute.SETTINGS -> showSettings()
            else -> host.launchHostRoute(this, route)
        }
    }

    private fun showDesktop() {
        currentRoute = ProductRoute.DESKTOP
        startupStore.recordLast(ProductRoute.DESKTOP)
        title.setText(R.string.oh_desktop)
        doneEditing.visibility = View.GONE
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
        } else {
            host.launchDynamicComponent(this, entry.component.source)
        }
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
        currentRoute = ProductRoute.SETTINGS
        title.setText(R.string.oh_settings)
        doneEditing.visibility = View.GONE
        desktopView = null
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

        panel.addView(actionButton("重置桌面布局") {
            layoutState = layoutStore.reset(components)
            Toast.makeText(this, "桌面布局已重置。", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(20) })
        panel.addView(actionButton(getString(R.string.oh_open_host_settings)) {
            host.launchHostRoute(this, ProductRoute.SETTINGS)
        })
        content.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
