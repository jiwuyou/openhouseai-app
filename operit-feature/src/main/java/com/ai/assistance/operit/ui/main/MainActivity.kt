package com.ai.assistance.operit.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import com.ai.assistance.operit.util.AppLogger
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.preferences.AgreementPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.host.control.OperitControlProtocol
import com.ai.assistance.operit.host.control.OperitControlStateStore
import com.ai.assistance.operit.host.control.OperitShutdownController
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.agreement.screens.AgreementScreen
import com.ai.assistance.operit.ui.features.permission.screens.PermissionGuideScreen
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingScreenWithState
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.LocalPluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingStateRegistry
import com.ai.assistance.operit.ui.theme.OperitTheme
import com.ai.assistance.operit.ui.common.displays.VirtualDisplayOverlay
import com.ai.assistance.operit.util.AnrMonitor
import com.ai.assistance.operit.util.LocaleUtils
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ai.assistance.operit.data.mcp.MCPRepository
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.ui.features.github.GitHubOAuthCoordinator
import com.ai.assistance.operit.widget.ToolPkgDesktopWidgetHost
import com.ai.assistance.operit.rescue.ui.RescueActivity
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_SETTINGS_SHORTCUT = "com.ai.assistance.operit.action.OPEN_SETTINGS_SHORTCUT"
        const val EXTRA_HOSTED_MODE = "com.ai.assistance.operit.extra.HOSTED_MODE"
        const val EXTRA_HOST_MODE = "com.ai.assistance.operit.extra.HOST_MODE"
        const val EXTRA_HELP_MODE = "com.ai.assistance.operit.extra.HELP_MODE"
        const val EXTRA_HOST_RETURN_ACTIVITY =
            "com.ai.assistance.operit.extra.HOST_RETURN_ACTIVITY"
    }

    private val TAG = "MainActivity"

    // ======== 屏幕方向变更状态 ========
    private var showOrientationChangeDialog by mutableStateOf(false)

    private var lastOrientation: Int? = null

    // ======== 工具和管理器 ========
    private lateinit var toolHandler: AIToolHandler
    private lateinit var preferencesManager: UserPreferencesManager
    private lateinit var agreementPreferences: AgreementPreferences
    private lateinit var anrMonitor: AnrMonitor
    private lateinit var mcpRepository: MCPRepository

    // ======== 导航状态 ========
    private var showPreferencesGuide by mutableStateOf(false)

    // ======== MCP插件状态 ========
    private val pluginLoadingState = PluginLoadingState()

    // ======== 双击返回退出相关变量 ========
    private var backPressedTime: Long = 0
    private val backPressedInterval: Long = 2000 // 两次点击的时间间隔，单位为毫秒

    // 是否显示权限引导界面
    private var showPermissionGuide by mutableStateOf(false)

    // 是否已完成初始检查
    private var initialChecksDone by mutableStateOf(false)

    // 存储待处理的分享文件URIs
    private var pendingSharedFileUris: List<Uri>? = null

    private var pendingSharedText: String? = null
    private var pendingGitHubAuthUri: Uri? = null
    private var pendingShortcutNavItem: NavItem? = null
    private var pendingShortcutRequestId: Long = 0L
    private var currentMainNavItem: NavItem = NavItem.AiChat
    private var pendingRouteId: String? = null
    private var pendingRouteArgs: Map<String, Any?> = emptyMap()
    private var pendingRouteRequestId: Long = 0L
    private var isHostedMode by mutableStateOf(false)
    private var hostMode by mutableStateOf(OperitHostMode.STANDALONE)
    private var isHostedHelpMode by mutableStateOf(false)
    private var hostedShutdownReceiverRegistered = false
    private val hostedShutdownReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != OperitControlProtocol.ACTION_REQUEST_SHUTDOWN) return
                if (!isHostedControlProcess()) return
                val reason =
                    intent.getStringExtra(OperitControlProtocol.EXTRA_SHUTDOWN_REASON)
                        ?: OperitControlProtocol.SHUTDOWN_REASON_USER
                shutdownOperit(
                    reason = reason,
                    requestedBy = OperitControlProtocol.SHUTDOWN_REQUESTED_BY_HOME
                )
            }
        }

    // 通知权限请求启动器
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AppLogger.d(TAG, "通知权限已授予")
        } else {
            AppLogger.d(TAG, "通知权限被拒绝")
            Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private fun processPendingSharedText() {
        if (pendingSharedFileUris != null) {
            AppLogger.d(TAG, "Pending shared text will be processed with shared files")
            return
        }

        val text = pendingSharedText?.trim()
        if (text.isNullOrBlank()) {
            AppLogger.d(TAG, "No pending shared text to process")
            return
        }

        SharedFileHandler.setSharedText(text)
        AppLogger.d(TAG, "Successfully passed shared text to SharedFileHandler")
        pendingSharedText = null
    }

    private fun restoreRuntimeTaskViewVisibilityIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (AIForegroundService.isRunning.get()) return

        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.appTasks?.forEach { task ->
                try {
                    task.setExcludeFromRecents(false)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "恢复最近任务可见性失败", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "恢复运行时任务视图可见性失败", e)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // 获取当前设置的语言
        val code = LocaleUtils.getCurrentLanguage(newBase)
        val locale = LocaleUtils.getLocaleForLanguageCode(code, newBase)
        val config = Configuration(newBase.resources.configuration)

        // 设置语言配置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            Locale.setDefault(locale)
        }

        // 使用createConfigurationContext创建新的本地化上下文
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
        AppLogger.d(TAG, "MainActivity应用语言设置: $code")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OperitApplication.initializeUiProcess(applicationContext)
        lastOrientation = resources.configuration.orientation
        AppLogger.d(TAG, "onCreate: Android SDK version: ${Build.VERSION.SDK_INT}")
        updateHostedFlags(intent)
        registerHostedShutdownReceiverIfNeeded()

        // Set window background to solid color to prevent system theme leaking through
        window.setBackgroundDrawableResource(android.R.color.black)

        setAppContent()
        lifecycleScope.launch {
            try {
                delay(16)
                completeStartup(savedInstanceState)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                handleStartupFailure(error)
            }
        }
    }

    private suspend fun completeStartup(savedInstanceState: Bundle?) {
        withContext(Dispatchers.Default) {
            OperitApplication.initializeMainApplication(applicationContext)
        }

        // Handle the intent that started the activity
        handleIntent(intent)
        restoreRuntimeTaskViewVisibilityIfNeeded()

        // 语言设置已在Application中初始化，这里无需重复

        initializeComponents()
        anrMonitor.start()
        setupPreferencesListener()
        configureDisplaySettings()

        // 设置上下文以便获取插件元数据
        pluginLoadingState.setAppContext(this)
        PluginLoadingStateRegistry.bind(pluginLoadingState, lifecycleScope)

        // 设置跳过加载的回调
        pluginLoadingState.setOnSkipCallback {
            AppLogger.d(TAG, "用户跳过了插件加载过程")
            Toast.makeText(this, getString(R.string.plugin_loading_skipped), Toast.LENGTH_SHORT).show()
        }

        processPendingGitHubAuth()

        // 只在首次创建时执行检查（非配置变更）
        if (savedInstanceState == null) {
            // 进行必要的初始检查
            performInitialChecks()
        } else {
            // 配置变更时不重新检查，直接显示主界面
            initialChecksDone = true
        }

        // 设置双击返回退出
        setupBackPressHandler()
    }

    private fun handleStartupFailure(error: Throwable) {
        AppLogger.e(TAG, "Operit startup failed", error)
        if (isHostedControlProcess()) {
            OperitControlStateStore.markStopped(applicationContext)
            val hostIntent = createHostReturnIntent()
            runCatching { startActivity(hostIntent) }
                .onFailure { AppLogger.e(TAG, "Failed to return to host after Operit startup failure", it) }
        } else {
            Toast.makeText(
                this,
                "Operit 启动失败: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // 重要：更新当前Intent
        updateHostedFlags(intent)
        registerHostedShutdownReceiverIfNeeded()
        AppLogger.d(TAG, "onNewIntent: Received intent with action: ${intent?.action}")
        restoreRuntimeTaskViewVisibilityIfNeeded()
        val handledShortcutIntent = handleIntent(intent)

        if (handledShortcutIntent) {
            processPendingGitHubAuth()
            setAppContent()
            return
        }

        // 如果是分享或打开内容，立即处理
        if (intent?.action == Intent.ACTION_VIEW ||
            intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE
        ) {
            processPendingSharedFiles()
            processPendingSharedText()
        }
    }

    private fun updateHostedFlags(intent: Intent?) {
        val legacyHosted = intent?.getBooleanExtra(EXTRA_HOSTED_MODE, false) == true
        val requestedMode = OperitHostMode.fromExtra(intent?.getStringExtra(EXTRA_HOST_MODE))
        hostMode = if (requestedMode != OperitHostMode.STANDALONE) {
            requestedMode
        } else if (legacyHosted) {
            OperitHostMode.BASIC
        } else {
            OperitHostMode.STANDALONE
        }
        isHostedMode = hostMode.isHosted
        isHostedHelpMode = intent?.getBooleanExtra(EXTRA_HELP_MODE, false) == true
        if (isHostedHelpMode) {
            AppLogger.d(TAG, "Operit launched in hosted AI朋友 Help mode")
            pendingShortcutNavItem = NavItem.Help
            pendingShortcutRequestId = System.currentTimeMillis()
            currentMainNavItem = NavItem.Help
        }
    }

    private fun returnToHostMainMenu() {
        if (!isHostedMode) {
            finish()
            return
        }

        OperitControlStateStore.markBackground(applicationContext)
        val hostIntent = createHostReturnIntent()
        try {
            startActivity(hostIntent)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to return to SmallPhoneAI main menu", error)
        }
    }

    private fun createHostReturnIntent(): Intent {
        val requestedActivity =
            intent?.getStringExtra(EXTRA_HOST_RETURN_ACTIVITY)?.trim().orEmpty()
        if (requestedActivity.isNotEmpty()) {
            return Intent()
                .setClassName(packageName, requestedActivity)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: Intent().setPackage(packageName)
    }

    private fun shutdownOperit(
        reason: String = OperitControlProtocol.SHUTDOWN_REASON_USER,
        requestedBy: String = OperitControlProtocol.SHUTDOWN_REQUESTED_BY_OPERIT
    ) {
        OperitShutdownController.shutdownFromActivity(
            activity = this,
            reason = reason,
            requestedBy = requestedBy,
            anrMonitor = if (::anrMonitor.isInitialized) anrMonitor else null,
            beforeFinish = {
                PluginLoadingStateRegistry.unbind(pluginLoadingState)
                pluginLoadingState.hide()
            }
        )
    }

    private fun isHostedControlProcess(): Boolean =
        isHostedMode && OperitControlProtocol.isCurrentOperitProcess(this)

    private fun registerHostedShutdownReceiverIfNeeded() {
        if (hostedShutdownReceiverRegistered || !isHostedControlProcess()) return
        val filter = IntentFilter(OperitControlProtocol.ACTION_REQUEST_SHUTDOWN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hostedShutdownReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(hostedShutdownReceiver, filter)
        }
        hostedShutdownReceiverRegistered = true
    }

    private fun unregisterHostedShutdownReceiverIfNeeded() {
        if (!hostedShutdownReceiverRegistered) return
        runCatching { unregisterReceiver(hostedShutdownReceiver) }
        hostedShutdownReceiverRegistered = false
    }

    override fun onResume() {
        super.onResume()
        if (isHostedControlProcess()) {
            OperitControlStateStore.markForeground(applicationContext)
        }
    }

    override fun onPause() {
        if (isHostedControlProcess() && !OperitShutdownController.isShutdownInProgress()) {
            OperitControlStateStore.markBackground(applicationContext)
        }
        super.onPause()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action == ACTION_OPEN_SETTINGS_SHORTCUT) {
            pendingShortcutNavItem = NavItem.Settings
            pendingShortcutRequestId = System.currentTimeMillis()
            currentMainNavItem = NavItem.Settings
            AppLogger.d(TAG, "Shortcut requested opening settings")
            return true
        }

        val pendingWidgetRouteId =
            intent?.getStringExtra(ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ID)?.trim().orEmpty()
        if (pendingWidgetRouteId.isNotBlank()) {
            pendingRouteId = pendingWidgetRouteId
            pendingRouteArgs =
                parseRouteArgsJson(
                    intent?.getStringExtra(ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ARGS_JSON)
                )
            pendingRouteRequestId = System.currentTimeMillis()
            AppLogger.d(TAG, "Shortcut requested opening route: $pendingWidgetRouteId")
            return true
        }

        val intentUri = intent?.data
        if (GitHubAuthPreferences.isOAuthRedirectUri(intentUri)) {
            pendingGitHubAuthUri = intentUri
            AppLogger.d(TAG, "Received GitHub OAuth redirect: $intentUri")
            return true
        }

        // Handle opened and shared files
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                // Handle "Open with" action
                intent.data?.let { uri ->
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        pendingSharedText = uri.toString()
                        AppLogger.d(TAG, "Received link to open: $uri")
                    } else {
                        pendingSharedFileUris = listOf(uri)
                        AppLogger.d(TAG, "Received file to open: $uri")
                    }
                }
            }
            Intent.ACTION_SEND -> {
                // Handle "Share" action
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uri?.let {
                    pendingSharedFileUris = listOf(it)
                    AppLogger.d(TAG, "Received shared file: $it")
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    pendingSharedText = sharedText
                    AppLogger.d(TAG, "Received shared text")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    pendingSharedFileUris = uris
                    AppLogger.d(TAG, "Received shared files: ${uris.size}")
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    pendingSharedText = sharedText
                    AppLogger.d(TAG, "Received shared text")
                }
            }
        }
        return false
    }

    private fun processPendingGitHubAuth() {
        val authUri = pendingGitHubAuthUri ?: return
        pendingGitHubAuthUri = null

        lifecycleScope.launch {
            val coordinator = GitHubOAuthCoordinator(this@MainActivity)
            val result = coordinator.completeExternalLogin(authUri)
            result.fold(
                onSuccess = { user ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.main_github_login_success, user.login),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { error ->
                    val message = error.message.orEmpty()
                    if (authUri.getQueryParameter("error") == "access_denied") {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.github_login_external_cancelled),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.main_github_login_failed, message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    AppLogger.e(TAG, "Failed to complete external GitHub login", error)
                }
            )
        }
    }

    private suspend fun prepareStartupChatIfNeeded() {
        try {
            val displayPreferencesManager =
                DisplayPreferencesManager.getInstance(this@MainActivity)
            if (!displayPreferencesManager.startWithNewChat.first()) {
                return
            }

            val chatHistoryManager = ChatHistoryManager.getInstance(this@MainActivity)
            val newChat = chatHistoryManager.createNewChat(
                setAsCurrentChat = false
            )
            chatHistoryManager.setCurrentChatId(newChat.id)
            AppLogger.d(TAG, "启动时已创建新的空白聊天")
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动时创建空白聊天失败", e)
        }
    }

    private fun parseRouteArgsJson(raw: String?): Map<String, Any?> {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) {
            return emptyMap()
        }
        return try {
            val json = JSONObject(text)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.opt(key)
                    put(key, if (value == JSONObject.NULL) null else value)
                }
            }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to parse pending route args json", error)
            emptyMap()
        }
    }

    // ======== 设置初始占位内容 ========

    // ======== 执行初始化检查 ========
    private fun performInitialChecks() {
        lifecycleScope.launch {
            if (hostMode.isHosted) {
                // Basic/hosted surfaces must open the product UI directly.  The standalone
                // Operit agreement, permission wizard, and profile setup remain unchanged.
                showPermissionGuide = false
                showPreferencesGuide = false
                prepareStartupChatIfNeeded()
                initialChecksDone = true
                setAppContent()
                return@launch
            }

            // 1. 检查通知权限（Android 13+）
            checkNotificationPermission()

            // 2. 检查权限级别设置
            checkPermissionLevelSet()

            prepareStartupChatIfNeeded()

            // 3. 在协议已接受且无需权限引导时，启动插件加载
            if (!showPermissionGuide && agreementPreferences.isAgreementAccepted()) {
                startPluginLoading()
            }

            // 标记完成初始检查
            initialChecksDone = true

            // 设置应用内容
            setAppContent()
        }
    }

    // ======== 启动插件加载 ========
    private fun startPluginLoading() {
        // 显示插件加载界面
        pluginLoadingState.show()

        // 启动超时检测（30秒）
        pluginLoadingState.startTimeoutCheck(30000L, lifecycleScope)

        // 初始化MCP服务器并启动插件
        // 轻微延迟让首帧 Compose 完成，避免启动阶段后台重任务立刻抢占导致掉帧
        lifecycleScope.launch {
            delay(500)
            pluginLoadingState.initializeMCPServer(this@MainActivity, lifecycleScope)
        }
    }

    // ======== 处理待处理的分享文件 ========
    private fun processPendingSharedFiles() {
        val uris = pendingSharedFileUris
        if (uris == null) {
            AppLogger.d(TAG, "No pending shared files to process")
            return
        }

        AppLogger.d(TAG, "Processing ${uris.size} pending shared file(s)")
        uris.forEachIndexed { index, uri ->
            AppLogger.d(TAG, "  [$index] URI: $uri")
        }

        lifecycleScope.launch {
            try {
                // Pass the URIs to the chat screen via SharedFileHandler
                val sharedText = pendingSharedText?.trim()
                SharedFileHandler.setSharedFiles(uris, sharedText)
                AppLogger.d(TAG, "Successfully passed shared files to SharedFileHandler")
                pendingSharedFileUris = null
                pendingSharedText = null
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to process shared files", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.chat_process_shared_files_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
                pendingSharedFileUris = null
            }
        }
    }

    // 配置双击返回退出的处理器
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (isHostedMode) {
                            returnToHostMainMenu()
                            return
                        }

                        val currentTime = System.currentTimeMillis()

                        if (currentTime - backPressedTime > backPressedInterval) {
                            // 第一次点击，显示提示
                            backPressedTime = currentTime
                            Toast.makeText(this@MainActivity, getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
                        } else {
                            // 第二次点击，退出应用
                            finish()
                        }
                    }
                }
        )
    }


    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d(TAG, "onDestroy called")
        unregisterHostedShutdownReceiverIfNeeded()

        PluginLoadingStateRegistry.unbind(pluginLoadingState)

        // 确保隐藏加载界面
        pluginLoadingState.hide()

        // 主界面销毁时，确保关闭虚拟屏幕 Overlay 并断开 Shower WebSocket 连接
        try {
            VirtualDisplayOverlay.hideAll()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error hiding VirtualDisplayOverlay in MainActivity.onDestroy", e)
        }

        if (::anrMonitor.isInitialized) {
            anrMonitor.stop()
        }
        if (isHostedControlProcess() && !OperitShutdownController.isShutdownInProgress()) {
            OperitControlStateStore.markStopped(applicationContext)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLogger.d(TAG, "onConfigurationChanged: new orientation=${newConfig.orientation}, last orientation=${lastOrientation}")

        // 屏幕方向变化时，确保加载界面不可见
        pluginLoadingState.hide()

        // 仅当方向确实发生变化时才处理
        if (newConfig.orientation != lastOrientation) {
            // 记录变化前的方向
            val orientationBeforeChange = lastOrientation
            // 更新最后的方向记录
            lastOrientation = newConfig.orientation

            // 检查是否是“转回去”的操作
            if (showOrientationChangeDialog && newConfig.orientation == orientationBeforeChange) {
                // 如果是，隐藏弹窗并结束
                showOrientationChangeDialog = false
                return
            }

            // 如果不是“转回去”，或者弹窗还未显示，则显示弹窗
            showOrientationChangeDialog = true
        }
    }

    // ======== 初始化组件 ========
    private fun initializeComponents() {
        // 初始化工具处理器（工具注册已在Application中完成）
        toolHandler = AIToolHandler.getInstance(this)

        // 初始化MCP仓库
        mcpRepository = MCPRepository(this)

        anrMonitor = AnrMonitor(this, lifecycleScope)

        // 初始化用户偏好管理器并直接检查初始化状态
        preferencesManager = UserPreferencesManager.getInstance(this)
        showPreferencesGuide =
            hostMode != OperitHostMode.BASIC && !preferencesManager.isPreferencesInitialized()
        AppLogger.d(
                TAG,
                "初始化检查: 用户偏好已初始化=${!showPreferencesGuide}，将${if(showPreferencesGuide) "" else "不"}显示引导界面"
        )

        // 初始化协议偏好管理器
        agreementPreferences = AgreementPreferences(this)

    }

    // ======== 检查通知权限 ========
    private fun checkNotificationPermission() {
        if (hostMode.isHosted) {
            AppLogger.d(TAG, "Hosted mode skips automatic notification permission request")
            return
        }
        // Android 13 (API 33) 及以上需要请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    AppLogger.d(TAG, "通知权限已授予")
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    // 用户之前拒绝过，显示说明并再次请求
                    AppLogger.d(TAG, "需要显示通知权限说明")
                    Toast.makeText(
                        this,
                        getString(R.string.notification_permission_rationale),
                        Toast.LENGTH_LONG
                    ).show()
                    notificationPermissionLauncher.launch(permission)
                }
                else -> {
                    // 直接请求权限
                    AppLogger.d(TAG, "请求通知权限")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        } else {
            // Android 13 以下不需要运行时通知权限
            AppLogger.d(TAG, "Android 版本 < 13，无需请求通知权限")
        }
    }

    // ======== 检查权限级别设置 ========
    private fun checkPermissionLevelSet() {
        if (hostMode.isHosted) {
            showPermissionGuide = false
            AppLogger.d(TAG, "Hosted mode skips automatic permission guide")
            return
        }
        // 检查是否已设置权限级别
        val permissionLevel = androidPermissionPreferences.getPreferredPermissionLevel()
        AppLogger.d(TAG, "当前权限级别: $permissionLevel")
        showPermissionGuide = permissionLevel == null
        AppLogger.d(
                TAG,
                "权限级别检查: 已设置=${!showPermissionGuide}, 将${if(showPermissionGuide) "" else "不"}显示权限引导界面"
        )
    }

    // ======== 偏好监听器设置 ========
    private fun setupPreferencesListener() {
        // 监听偏好变化
        lifecycleScope.launch {
            preferencesManager.getUserPreferencesFlow().collect { profile ->
                // 只有当状态变化时才更新UI
                if (hostMode == OperitHostMode.BASIC) return@collect
                val newValue = !profile.isInitialized
                if (showPreferencesGuide != newValue) {
                    AppLogger.d(TAG, "偏好变更: 从 $showPreferencesGuide 变为 $newValue")
                    showPreferencesGuide = newValue
                    setAppContent()
                }
            }
        }
    }

    // ======== 显示与性能配置 ========
    private fun configureDisplaySettings() {
        // 1. 请求持续的高性能模式 (API 31+)
        // 这会提示系统为应用提供持续的高性能，避免CPU/GPU降频。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setSustainedPerformanceMode(true)
                AppLogger.d(TAG, "已成功请求持续高性能模式。")
            } catch (e: Exception) {
                // 在某些设备上，此模式可能不可用或不支持。
                AppLogger.w(TAG, "请求持续高性能模式失败。", e)
            }
        }

        // 2. 设置应用以最高刷新率运行
        // 高刷新率优化：通过设置窗口属性确保流畅
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 为Android 11+设备优化高刷新率
            val highestMode = getHighestRefreshRate()
            if (highestMode > 0) {
                window.attributes.preferredDisplayModeId = highestMode
                AppLogger.d(TAG, "设置窗口首选显示模式ID: $highestMode")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 为Android 6.0-10设备优化高刷新率
            val refreshRate = getDeviceRefreshRate()
            if (refreshRate > 60f) {
                window.attributes.preferredRefreshRate = refreshRate
                AppLogger.d(TAG, "设置窗口首选刷新率: $refreshRate Hz")
            }
        }

        // 启用硬件加速以提高渲染性能
        window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
    }

    // ======== 设置应用内容 ========
    private fun setAppContent() {
        setContent {
            OperitTheme {
                Box {
                    // 如果初始化检查未完成，则显示一个占位符，避免在检查完成前显示不完整的界面
                    if (!initialChecksDone) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        // Hosted modes intentionally bypass Operit's onboarding screens.  The
                        // screens and stored agreement state remain available in standalone mode.
                        if (!hostMode.isHosted && !agreementPreferences.isAgreementAccepted()) {
                            AgreementScreen(
                                    onAgreementAccepted = {
                                        agreementPreferences.setAgreementAccepted(true)
                                        // 协议接受后，检查权限级别设置
                                        lifecycleScope.launch {
                                            // 确保使用非阻塞方式更新UI
                                            delay(300) // 短暂延迟确保UI状态更新
                                            checkPermissionLevelSet()
                                            if (!showPermissionGuide) {
                                                startPluginLoading()
                                            }
                                            // 重新设置应用内容
                                            setAppContent()
                                        }
                                    }
                            )
                        }
                        // 检查是否需要显示权限引导界面
                        else if (!hostMode.isHosted && showPermissionGuide) {
                            PermissionGuideScreen(
                                    onComplete = {
                                        showPermissionGuide = false
                                        // 权限设置完成后，启动插件加载并更新内容
                                        startPluginLoading()
                                        setAppContent()
                                    }
                            )
                        }
                        // 显示主应用界面
                        else {
                            // 处理待处理的分享文件
                            processPendingSharedFiles()
                            processPendingSharedText()
                            val shortcutNavItem = if (!showPreferencesGuide) pendingShortcutNavItem else null
                            val shortcutNavRequestId =
                                if (!showPreferencesGuide) pendingShortcutRequestId else 0L
                            val routeNavRequest = if (!showPreferencesGuide) pendingRouteId else null
                            val routeNavArgs = if (!showPreferencesGuide) pendingRouteArgs else emptyMap()
                            val routeNavRequestId =
                                if (!showPreferencesGuide) pendingRouteRequestId else 0L
                            val initialNavItem = when {
                                !hostMode.isHosted && showPreferencesGuide -> NavItem.UserPreferencesGuide
                                shortcutNavItem != null -> shortcutNavItem
                                else -> currentMainNavItem
                            }

                            CompositionLocalProvider(LocalPluginLoadingState provides pluginLoadingState) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 主应用界面 (始终存在于底层)
                                    OperitApp(
                                        initialNavItem = initialNavItem,
                                        toolHandler = toolHandler,
                                        shortcutNavRequest = shortcutNavItem,
                                        shortcutNavRequestId = shortcutNavRequestId,
                                        routeNavRequest = routeNavRequest,
                                        routeNavArgs = routeNavArgs,
                                        routeNavRequestId = routeNavRequestId,
                                        isHostedMode = isHostedMode,
                                        hostMode = hostMode,
                                        onReturnToHostMainMenu = ::returnToHostMainMenu,
                                        onCloseHostedOperit = {
                                            shutdownOperit(
                                                requestedBy = OperitControlProtocol.SHUTDOWN_REQUESTED_BY_OPERIT
                                            )
                                        },
                                        onShortcutNavHandled = { handledRequestId ->
                                            if (pendingShortcutRequestId == handledRequestId) {
                                                pendingShortcutNavItem = null
                                                pendingShortcutRequestId = 0L
                                            }
                                        },
                                        onCurrentNavItemChanged = { navItem ->
                                            currentMainNavItem = navItem
                                        },
                                        onRouteNavHandled = { handledRequestId ->
                                            if (pendingRouteRequestId == handledRequestId) {
                                                pendingRouteId = null
                                                pendingRouteArgs = emptyMap()
                                                pendingRouteRequestId = 0L
                                            }
                                        }
                                    )
                                    // Keep this entry capability-driven so host manifest overrides
                                    // can still disable Repair mode without changing the Operit UI.
                                    val rescueIntentAvailable = remember {
                                        packageManager.resolveActivity(
                                            RescueActivity.createIntent(this@MainActivity),
                                            PackageManager.MATCH_DEFAULT_ONLY,
                                        ) != null
                                    }
                                    if (!isHostedMode && rescueIntentAvailable) {
                                        TextButton(
                                            onClick = {
                                                startActivity(RescueActivity.createIntent(this@MainActivity))
                                            },
                                            modifier =
                                                Modifier.align(Alignment.BottomEnd)
                                                    .padding(16.dp)
                                                    .zIndex(5f),
                                        ) {
                                            Text(stringResource(R.string.rescue_ai_open))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 插件加载界面 (带有淡出效果) - 始终在最上层
                    PluginLoadingScreenWithState(
                            loadingState = pluginLoadingState,
                            modifier = Modifier.zIndex(10f) // 确保加载界面在最上层
                    )
                }

                // 方向改变时显示对话框
                if (showOrientationChangeDialog) {
                    OrientationChangeDialog(
                        onConfirm = {
                            showOrientationChangeDialog = false
                            // 重新创建Activity以重新加载页面
                            recreate()
                        },
                        onDismiss = {
                            showOrientationChangeDialog = false
                        }
                    )
                }
            }
        }
    }

    private fun getHighestRefreshRate(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayModes = display?.supportedModes ?: return 0
            var maxRefreshRate = 60f // Default to 60Hz
            var highestModeId = 0

            for (mode in displayModes) {
                if (mode.refreshRate > maxRefreshRate) {
                    maxRefreshRate = mode.refreshRate
                    highestModeId = mode.modeId
                }
            }
            AppLogger.d(TAG, "Selected display mode with refresh rate: $maxRefreshRate Hz")
            return highestModeId
        }
        return 0
    }

    private fun getDeviceRefreshRate(): Float {
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val display =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION") windowManager.defaultDisplay
                }

        var refreshRate = 60f // Default refresh rate

        if (display != null) {
            try {
                @Suppress("DEPRECATION") val modes = display.supportedModes
                for (mode in modes) {
                    if (mode.refreshRate > refreshRate) {
                        refreshRate = mode.refreshRate
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting refresh rate", e)
            }
        }

        AppLogger.d(TAG, "Selected refresh rate: $refreshRate Hz")
        return refreshRate
    }

}

@Composable
private fun OrientationChangeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.dialog_title_orientation_change)) },
        text = { Text(text = stringResource(id = R.string.dialog_message_orientation_change)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(id = R.string.dialog_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_button_dismiss))
            }
        }
    )
}
