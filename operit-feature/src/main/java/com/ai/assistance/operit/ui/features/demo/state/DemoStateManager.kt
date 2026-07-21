package com.ai.assistance.operit.ui.features.demo.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.ai.assistance.operit.util.AppLogger
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.ai.assistance.operit.core.tools.system.RootAuthorizer
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.core.tools.system.AccessibilityProviderInstaller
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "DemoStateManager"
private const val ENVIRONMENT_CHECK_TIMEOUT_MS = 3_000L

/**
 * Consolidated state management for the demo screens. Handles state initialization, updates, and
 * listeners for Shizuku and other features.
 */
class DemoStateManager(private val context: Context, private val coroutineScope: CoroutineScope) : ViewModel() {
    // Main UI state holder
    private val _uiState = MutableStateFlow(DemoScreenState())
    val uiState: StateFlow<DemoScreenState> = _uiState.asStateFlow()

    // NodeJS和Python环境状态
    val isPnpmInstalled = mutableStateOf(false)
    val isPythonInstalled = mutableStateOf(false)
    val isNodejsPythonEnvironmentReady = mutableStateOf(false)
    private val environmentRefreshMutex = Mutex()

    // Shizuku state change listeners
    private val shizukuListener: () -> Unit = { refreshStatus() }

    // Root state change listener
    private val rootListener: () -> Unit = { refreshStatus() }

    init {
        // Register listeners for Shizuku and Root state changes
        ShizukuAuthorizer.addStateChangeListener(shizukuListener)
        RootAuthorizer.addStateChangeListener(rootListener)
        // 初始化时刷新所有状态
        coroutineScope.launch {
            refreshAllStates()
        }
    }

    /** Initialize state */
    fun initialize() {
        coroutineScope.launch {
            AppLogger.d(TAG, "初始化状态...")
            registerStateChangeListeners()
            refreshStatusAsync()
        }
    }

    /** Refresh permissions and component status */
    fun refreshStatus() {
       coroutineScope.launch {
           refreshStatusAsync()
       }
    }

    /** Update root status */
    fun updateRootStatus(isDeviceRooted: Boolean, hasRootAccess: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                    isDeviceRooted = mutableStateOf(isDeviceRooted),
                    hasRootAccess = mutableStateOf(hasRootAccess)
            )
        }

        // 如果设备已Root但未获取权限，则显示Root向导
        if (isDeviceRooted && !hasRootAccess) {
            _uiState.update { currentState ->
                currentState.copy(showRootWizard = mutableStateOf(true))
            }
        }
    }

    /** Update UI state */
    fun updateOutputText(text: String) {
        // This function is kept for compatibility but might be repurposed or removed.
    }

    /** Dialog management */
    fun showResultDialog(title: String, content: String) {
        _uiState.update { currentState ->
            currentState.copy(
                    resultDialogTitle = mutableStateOf(title),
                    resultDialogContent = mutableStateOf(content),
                    showResultDialogState = mutableStateOf(true)
            )
        }
    }

    fun hideResultDialog() {
        _uiState.update { currentState ->
            currentState.copy(showResultDialogState = mutableStateOf(false))
        }
    }

    /** Toggle UI visibility */
    fun toggleShizukuWizard() {
        _uiState.update { currentState ->
            currentState.copy(
                    showShizukuWizard = mutableStateOf(!currentState.showShizukuWizard.value)
            )
        }
    }

    fun toggleOperitTerminalWizard() {
        _uiState.update { currentState ->
            currentState.copy(
                showOperitTerminalWizard = mutableStateOf(!currentState.showOperitTerminalWizard.value)
            )
        }
    }

    fun toggleRootWizard() {
        _uiState.update { currentState ->
            currentState.copy(showRootWizard = mutableStateOf(!currentState.showRootWizard.value))
        }
    }

    fun toggleAccessibilityWizard() {
        _uiState.update { currentState ->
            currentState.copy(
                showAccessibilityWizard = mutableStateOf(!currentState.showAccessibilityWizard.value)
            )
        }
    }

    fun toggleAdbCommandExecutor() {
        _uiState.update { currentState ->
            currentState.copy(
                    showAdbCommandExecutor =
                            mutableStateOf(!currentState.showAdbCommandExecutor.value)
            )
        }
    }

    fun toggleSampleCommands() {
        _uiState.update { currentState ->
            currentState.copy(
                    showSampleCommands = mutableStateOf(!currentState.showSampleCommands.value)
            )
        }
    }

    /** Command handling */
    fun updateCommandText(text: String) {
        _uiState.update { currentState -> currentState.copy(commandText = mutableStateOf(text)) }
    }

    fun updateResultText(text: String) {
        _uiState.update { currentState -> currentState.copy(resultText = mutableStateOf(text)) }
    }

    /** Clean up resources */
    fun cleanup() {
        // Remove listeners
        ShizukuAuthorizer.removeStateChangeListener(shizukuListener)
        RootAuthorizer.removeStateChangeListener(rootListener)
    }

    /**
     * 刷新所有状态
     */
    suspend fun refreshAllStates() {
        refreshNodejsPythonEnvironment()
    }

    /**
     * 公开的刷新所有状态方法
     */
    fun refreshAllStatesPublic() {
        coroutineScope.launch {
            refreshAllStates()
        }
    }

    private fun registerStateChangeListeners() {
        // Implementation of registerStateChangeListeners method
    }

    /** Set loading state */
    fun setLoading(isLoading: Boolean) {
        _uiState.update { currentState -> currentState.copy(isLoading = mutableStateOf(isLoading)) }
    }

    /** Initialize state asynchronously */
    suspend fun initializeAsync() {
        AppLogger.d(TAG, "异步初始化状态...")
        registerStateChangeListeners()
        refreshStatusAsync()
    }

    /** Refresh permissions and component status asynchronously */
    private suspend fun refreshStatusAsync() {
        _uiState.update { currentState -> currentState.copy(isRefreshing = mutableStateOf(true)) }

        try {
            // Refresh permissions and status
            refreshPermissionsAndStatus(
                    context = context,
                    updateShizukuInstalled = { _uiState.value.isShizukuInstalled.value = it },
                    updateShizukuRunning = { _uiState.value.isShizukuRunning.value = it },
                    updateShizukuPermission = { _uiState.value.hasShizukuPermission.value = it },
                    updateOperitTerminalInstalled = { _uiState.value.isOperitTerminalInstalled.value = it },
                    updateOperitTerminalRunning = { isOperitTerminalRunning -> 
                        // Add logic if needed for OperitTerminal running state
                    },
                    updateStoragePermission = { _uiState.value.hasStoragePermission.value = it },
                    updateLocationPermission = { _uiState.value.hasLocationPermission.value = it },
                    updateOverlayPermission = { _uiState.value.hasOverlayPermission.value = it },
                    updateBatteryOptimizationExemption = {
                        _uiState.value.hasBatteryOptimizationExemption.value = it
                    },
                    updateAccessibilityProviderInstalled = {
                        _uiState.value.isAccessibilityProviderInstalled.value = it
                    },
                    updateAccessibilityServiceEnabled = {
                        _uiState.value.hasAccessibilityServiceEnabled.value = it
                    }
            )

            // Check Shizuku API_V23 permission
            if (_uiState.value.isShizukuInstalled.value && _uiState.value.isShizukuRunning.value) {
                _uiState.value.hasShizukuPermission.value = ShizukuAuthorizer.hasShizukuPermission()

                if (!_uiState.value.hasShizukuPermission.value) {
                    AppLogger.d(TAG, "缺少Shizuku API_V23权限，显示Shizuku向导卡片")
                    _uiState.value.showShizukuWizard.value = true
                }
            } else {
                _uiState.value.hasShizukuPermission.value = false
                _uiState.value.showShizukuWizard.value = true
            }

            // Check OperitTerminal status
            refreshNodejsPythonEnvironment()
            _uiState.value.isOperitTerminalInstalled.value =
                isNodejsPythonEnvironmentReady.value

            // 延迟300ms以确保UI能够刷新
            delay(300)
        } catch (e: Exception) {
            AppLogger.e(TAG, "刷新权限状态时出错: ${e.message}", e)
        } finally {
            _uiState.update { currentState ->
                currentState.copy(isRefreshing = mutableStateOf(false))
            }
        }
    }

    /**
     * 检查NodeJS和Python环境状态
     */
    suspend fun refreshNodejsPythonEnvironment() {
        environmentRefreshMutex.withLock {
            try {
                val status = probeNodejsPythonEnvironment()
                isPnpmInstalled.value = status.hasPnpm
                isPythonInstalled.value = status.hasPython && status.hasPip
                isNodejsPythonEnvironmentReady.value =
                    isPnpmInstalled.value && isPythonInstalled.value
                _uiState.value.isOperitTerminalInstalled.value =
                    isNodejsPythonEnvironmentReady.value

                AppLogger.d(
                    TAG,
                    "NodeJS环境检查 - pnpm: ${status.hasPnpm}, python: ${status.hasPython}, " +
                        "pip: ${status.hasPip}, python环境: ${isPythonInstalled.value}, " +
                        "整体ready: ${isNodejsPythonEnvironmentReady.value}"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查NodeJS和Python环境时出错", e)
                isPnpmInstalled.value = false
                isPythonInstalled.value = false
                isNodejsPythonEnvironmentReady.value = false
                _uiState.value.isOperitTerminalInstalled.value = false
            }
        }
    }

    private suspend fun probeNodejsPythonEnvironment(): NodejsPythonEnvironmentStatus =
        withContext(Dispatchers.IO) {
            val terminal = Terminal.getInstance(context)
            if (!terminal.isConnected() && !terminal.initialize()) {
                return@withContext NodejsPythonEnvironmentStatus()
            }

            val hasPnpm = terminal.commandExists("pnpm")
            val hasPython = terminal.commandExists("python") || terminal.commandExists("python3")
            val hasPip = hasPython && (terminal.commandExists("pip") || terminal.commandExists("pip3"))
            NodejsPythonEnvironmentStatus(hasPnpm = hasPnpm, hasPython = hasPython, hasPip = hasPip)
        }

    private suspend fun Terminal.commandExists(command: String): Boolean {
        val result =
            executeHiddenCommand(
                "command -v $command",
                executorKey = "demo-env-$command",
                timeoutMs = ENVIRONMENT_CHECK_TIMEOUT_MS,
                target = HostTerminalTarget.HOST
            )
        val output = listOf(result.output, result.error).joinToString("\n")
        return result.exitCode == 0 && output.contains(command)
    }

    private data class NodejsPythonEnvironmentStatus(
        val hasPnpm: Boolean = false,
        val hasPython: Boolean = false,
        val hasPip: Boolean = false
    )
}

/** 刷新应用权限和组件状态 */
suspend fun refreshPermissionsAndStatus(
    context: Context,
    updateShizukuInstalled: (Boolean) -> Unit,
    updateShizukuRunning: (Boolean) -> Unit,
    updateShizukuPermission: (Boolean) -> Unit,
    updateOperitTerminalInstalled: (Boolean) -> Unit,
    updateOperitTerminalRunning: (Boolean) -> Unit,
    updateStoragePermission: (Boolean) -> Unit,
    updateLocationPermission: (Boolean) -> Unit,
    updateOverlayPermission: (Boolean) -> Unit,
    updateBatteryOptimizationExemption: (Boolean) -> Unit,
    updateAccessibilityProviderInstalled: (Boolean) -> Unit,
    updateAccessibilityServiceEnabled: (Boolean) -> Unit
) {
    AppLogger.d(TAG, "刷新应用权限状态...")

    // 检查Shizuku安装、运行和权限状态
    val isShizukuInstalled = ShizukuAuthorizer.isShizukuInstalled(context)
    val isShizukuRunning = ShizukuAuthorizer.isShizukuServiceRunning()
    updateShizukuInstalled(isShizukuInstalled)
    updateShizukuRunning(isShizukuRunning)

    // Shizuku权限检查
    val hasShizukuPermission =
        if (isShizukuInstalled && isShizukuRunning) {
            ShizukuAuthorizer.hasShizukuPermission()
        } else {
            false
        }
    updateShizukuPermission(hasShizukuPermission)

    updateOperitTerminalInstalled(false)

    // 检查存储权限
    val hasStoragePermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    updateStoragePermission(hasStoragePermission)

    // 检查位置权限
    val hasLocationPermission =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    updateLocationPermission(hasLocationPermission)

    // 检查悬浮窗权限
    val hasOverlayPermission = Settings.canDrawOverlays(context)
    updateOverlayPermission(hasOverlayPermission)

    // 检查电池优化豁免
    val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    val hasBatteryOptimizationExemption =
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    updateBatteryOptimizationExemption(hasBatteryOptimizationExemption)

    // 检查无障碍服务提供者和服务的状态
    val isProviderInstalled = UIHierarchyManager.isProviderAppInstalled(context)
    updateAccessibilityProviderInstalled(isProviderInstalled)

    // 只有在提供者安装后才尝试绑定并检查服务状态
    if (isProviderInstalled) {
        // 确保服务已绑定
        UIHierarchyManager.bindToService(context)
    }

    val hasAccessibilityServiceEnabled =
        UIHierarchyManager.isAccessibilityServiceEnabled(context)
    updateAccessibilityServiceEnabled(hasAccessibilityServiceEnabled)
}

/** Data class to hold all UI state */
data class DemoScreenState(
        // Permission states
        val isShizukuInstalled: MutableState<Boolean> = mutableStateOf(false),
        val isShizukuRunning: MutableState<Boolean> = mutableStateOf(false),
        val hasShizukuPermission: MutableState<Boolean> = mutableStateOf(false),
        val isOperitTerminalInstalled: MutableState<Boolean> = mutableStateOf(false),
        val hasStoragePermission: MutableState<Boolean> = mutableStateOf(false),
        val hasOverlayPermission: MutableState<Boolean> = mutableStateOf(false),
        val hasBatteryOptimizationExemption: MutableState<Boolean> = mutableStateOf(false),
        val hasAccessibilityServiceEnabled: MutableState<Boolean> = mutableStateOf(false),
        val isAccessibilityProviderInstalled: MutableState<Boolean> = mutableStateOf(false),
        val hasLocationPermission: MutableState<Boolean> = mutableStateOf(false),
        val isDeviceRooted: MutableState<Boolean> = mutableStateOf(false),
        val hasRootAccess: MutableState<Boolean> = mutableStateOf(false),

        // UI states
        val isRefreshing: MutableState<Boolean> = mutableStateOf(false),
        val showHelp: MutableState<Boolean> = mutableStateOf(false),
        val permissionErrorMessage: MutableState<String?> = mutableStateOf(null),
        val showSampleCommands: MutableState<Boolean> = mutableStateOf(false),
        val showAdbCommandExecutor: MutableState<Boolean> = mutableStateOf(false),
        val showShizukuWizard: MutableState<Boolean> = mutableStateOf(false),
        val showOperitTerminalWizard: MutableState<Boolean> = mutableStateOf(false),
        val showRootWizard: MutableState<Boolean> = mutableStateOf(false),
        val showAccessibilityWizard: MutableState<Boolean> = mutableStateOf(false),
        val showResultDialogState: MutableState<Boolean> = mutableStateOf(false),

        // Command execution
        val commandText: MutableState<String> = mutableStateOf(""),
        val resultText: MutableState<String> = mutableStateOf(""),  // Will be set by context in usage
        val resultDialogTitle: MutableState<String> = mutableStateOf(""),
        val resultDialogContent: MutableState<String> = mutableStateOf(""),
        val isLoading: MutableState<Boolean> = mutableStateOf(false)
)

// Sample command lists that can be reused
fun getSampleAdbCommands(context: Context) =
        listOf(
                "getprop ro.build.version.release" to context.getString(R.string.demo_cmd_get_android_version),
                "pm list packages" to context.getString(R.string.demo_cmd_list_packages),
                "dumpsys battery" to context.getString(R.string.demo_cmd_check_battery),
                "settings list system" to context.getString(R.string.demo_cmd_list_settings),
                "am start -a android.intent.action.VIEW -d https://www.example.com" to context.getString(R.string.demo_cmd_open_webpage),
                "dumpsys activity activities" to context.getString(R.string.demo_cmd_list_activities),
                "service list" to context.getString(R.string.demo_cmd_list_services),
                "wm size" to context.getString(R.string.demo_cmd_check_resolution)
        )

// Predefined OperitTerminal commands (previously Termux)
fun getOperitTerminalSampleCommands(context: Context) =
        listOf(
                "echo 'Hello OperitTerminal'" to context.getString(R.string.demo_cmd_echo_hello),
                "ls -la" to context.getString(R.string.demo_cmd_list_files),
                "whoami" to context.getString(R.string.demo_cmd_show_user),
                "apt update" to context.getString(R.string.demo_cmd_update_package_manager),
                "apt install python3" to context.getString(R.string.demo_cmd_install_python),
                "ip addr" to context.getString(R.string.demo_cmd_show_network)
        )

// Root命令示例
fun getRootSampleCommands(context: Context) =
        listOf(
                "mount -o rw,remount /system" to context.getString(R.string.demo_cmd_remount_system),
                "cat /proc/version" to context.getString(R.string.demo_cmd_check_kernel),
                "ls -la /data" to context.getString(R.string.demo_cmd_list_data_dir),
                "getenforce" to context.getString(R.string.demo_cmd_check_selinux),
                "ps -A" to context.getString(R.string.demo_cmd_list_processes),
                "cat /proc/meminfo" to context.getString(R.string.demo_cmd_check_memory),
                "pm list features" to context.getString(R.string.demo_cmd_list_features),
                "dumpsys power" to context.getString(R.string.demo_cmd_check_power)
        )
