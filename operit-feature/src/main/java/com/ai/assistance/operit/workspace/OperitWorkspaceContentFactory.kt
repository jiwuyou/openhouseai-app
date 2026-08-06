package com.ai.assistance.operit.workspace

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.preferences.ModelConfigStorageScope
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.startup.screens.LocalPluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingState
import com.ai.assistance.operit.ui.main.DEFAULT_HOSTED_CLOSE_LABEL
import com.ai.assistance.operit.ui.main.OperitApp
import com.ai.assistance.operit.ui.main.OperitHostMode
import com.ai.assistance.operit.ui.theme.OperitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runtime identity for one Operit surface hosted inside the shared OpenHouse workspace. */
data class OperitWorkspaceIdentity(
    val hostMode: OperitHostMode,
    val runtimeSlot: ChatRuntimeSlot,
    val modelConfigStorageScope: ModelConfigStorageScope,
    val chatViewModelKey: String,
)

fun OperitHostMode.workspaceIdentity(): OperitWorkspaceIdentity =
    when (this) {
        OperitHostMode.RESCUE ->
            OperitWorkspaceIdentity(
                hostMode = this,
                runtimeSlot = ChatRuntimeSlot.RESCUE,
                modelConfigStorageScope = ModelConfigStorageScope.RESCUE,
                chatViewModelKey = "operit-chat-rescue",
            )
        OperitHostMode.BASIC, OperitHostMode.STANDALONE ->
            OperitWorkspaceIdentity(
                hostMode = this,
                runtimeSlot = ChatRuntimeSlot.MAIN,
                modelConfigStorageScope = ModelConfigStorageScope.MAIN,
                chatViewModelKey = "operit-chat-main",
            )
    }

val LocalOperitWorkspaceIdentity =
    androidx.compose.runtime.compositionLocalOf {
        OperitHostMode.STANDALONE.workspaceIdentity()
    }

/**
 * Complete input needed to render one Operit surface.
 *
 * [hostMode] is deliberately required. Embedded callers must never infer Rescue mode from an
 * Activity class because BASIC and RESCUE can coexist in the same OpenHouse Activity.
 */
data class OperitWorkspaceSpec(
    val hostMode: OperitHostMode,
    val initialNavItem: NavItem = NavItem.AiChat,
    val toolHandler: AIToolHandler? = null,
    val shortcutNavRequest: NavItem? = null,
    val shortcutNavRequestId: Long = 0L,
    val routeNavRequest: String? = null,
    val routeNavArgs: Map<String, Any?> = emptyMap(),
    val routeNavRequestId: Long = 0L,
    val pluginLoadingState: PluginLoadingState? = null,
    val embeddedInWorkspace: Boolean = false,
    val onReturnToHostMainMenu: () -> Unit = {},
    val onCloseHostedOperit: () -> Unit = {},
    val hostedCloseLabel: String = DEFAULT_HOSTED_CLOSE_LABEL,
    val onShortcutNavHandled: (Long) -> Unit = {},
    val onCurrentNavItemChanged: (NavItem) -> Unit = {},
    val onRouteNavHandled: (Long) -> Unit = {},
) {
    val identity: OperitWorkspaceIdentity = hostMode.workspaceIdentity()
}

/** Module-local content contract that Host adapters can wrap without a feature-module dependency. */
interface OperitWorkspaceContent {
    val view: View
    val hostMode: OperitHostMode

    fun onResume()
    fun onPause()
    fun onBackPressed(): Boolean
    fun destroy()
}

/**
 * Own dispatcher for an embedded Compose tree. This lets nested Compose BackHandlers consume the
 * event without dispatching it through the containing Activity and recursively re-entering the
 * OpenHouse back handler.
 */
internal class EmbeddedBackDispatcher {
    private var reachedFallback = false

    val dispatcher = OnBackPressedDispatcher { reachedFallback = true }

    fun dispatch(): Boolean {
        reachedFallback = false
        dispatcher.onBackPressed()
        return !reachedFallback
    }
}

/** Creates the same Operit root for standalone Activities and embedded OpenHouse destinations. */
object OperitWorkspaceContentFactory {
    @Composable
    fun Content(
        spec: OperitWorkspaceSpec,
        applyTheme: Boolean = true,
    ) {
        val body: @Composable () -> Unit = {
            CompositionLocalProvider(LocalOperitWorkspaceIdentity provides spec.identity) {
                val app = @Composable {
                    OperitApp(
                        initialNavItem = spec.initialNavItem,
                        toolHandler = spec.toolHandler,
                        shortcutNavRequest = spec.shortcutNavRequest,
                        shortcutNavRequestId = spec.shortcutNavRequestId,
                        routeNavRequest = spec.routeNavRequest,
                        routeNavArgs = spec.routeNavArgs,
                        routeNavRequestId = spec.routeNavRequestId,
                        isHostedMode = spec.hostMode.isHosted,
                        hostMode = spec.hostMode,
                        onReturnToHostMainMenu = spec.onReturnToHostMainMenu,
                        onCloseHostedOperit = spec.onCloseHostedOperit,
                        hostedCloseLabel = spec.hostedCloseLabel,
                        showHostedLifecycleActions = !spec.embeddedInWorkspace,
                        onShortcutNavHandled = spec.onShortcutNavHandled,
                        onCurrentNavItemChanged = spec.onCurrentNavItemChanged,
                        onRouteNavHandled = spec.onRouteNavHandled,
                    )
                }
                spec.pluginLoadingState?.let { loadingState ->
                    CompositionLocalProvider(LocalPluginLoadingState provides loadingState) { app() }
                } ?: app()
            }
        }

        if (applyTheme) {
            OperitTheme { body() }
        } else {
            body()
        }
    }

    /**
     * Builds an embeddable view. Initialization is asynchronous and failure stays inside this
     * content surface, so a bad Operit startup cannot crash the OpenHouse workspace shell.
     */
    fun create(
        activity: ComponentActivity,
        spec: OperitWorkspaceSpec,
    ): OperitWorkspaceContent {
        OperitApplication.initializeUiProcess(activity.applicationContext)
        return EmbeddedOperitWorkspaceContent(activity, spec.copy(embeddedInWorkspace = true))
    }
}

private sealed interface EmbeddedInitializationState {
    data object Loading : EmbeddedInitializationState
    data object Ready : EmbeddedInitializationState
    data class Failed(val message: String) : EmbeddedInitializationState
}

private class EmbeddedOperitWorkspaceContent(
    private val activity: ComponentActivity,
    private val spec: OperitWorkspaceSpec,
) : OperitWorkspaceContent, LifecycleOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val embeddedBackDispatcher = EmbeddedBackDispatcher()
    private val initializationState = mutableStateOf<EmbeddedInitializationState>(EmbeddedInitializationState.Loading)
    private var destroyed = false
    private var resumed = false
    private var initializationJob: Job? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = embeddedBackDispatcher.dispatcher

    override val hostMode: OperitHostMode
        get() = spec.hostMode

    override val view: ComposeView =
        ComposeView(activity).apply {
            id = View.generateViewId()
            setViewTreeLifecycleOwner(this@EmbeddedOperitWorkspaceContent)
            setViewTreeViewModelStoreOwner(this@EmbeddedOperitWorkspaceContent)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeOnBackPressedDispatcherOwner(this@EmbeddedOperitWorkspaceContent)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { EmbeddedContent(initializationState, spec) }
        }

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        initializationJob = activity.lifecycleScope.launch {
            initializationState.value =
                runCatching {
                    withContext(Dispatchers.Default) {
                        OperitApplication.initializeMainApplication(activity.applicationContext)
                    }
                    EmbeddedInitializationState.Ready
                }.getOrElse { error ->
                    EmbeddedInitializationState.Failed(
                        error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
                    )
                }
        }
    }

    override fun onResume() {
        if (destroyed || resumed) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        view.visibility = View.VISIBLE
        resumed = true
    }

    override fun onPause() {
        if (destroyed || !resumed) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        view.visibility = View.GONE
        resumed = false
    }

    override fun onBackPressed(): Boolean {
        if (destroyed || !resumed) return false
        return embeddedBackDispatcher.dispatch()
    }

    override fun destroy() {
        if (destroyed) return
        onPause()
        destroyed = true
        initializationJob?.cancel()
        initializationJob = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view.disposeComposition()
        viewModelStore.clear()
    }
}

@Composable
private fun EmbeddedContent(
    state: State<EmbeddedInitializationState>,
    spec: OperitWorkspaceSpec,
) {
    when (val current = state.value) {
        EmbeddedInitializationState.Loading ->
            OperitTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        EmbeddedInitializationState.Ready -> OperitWorkspaceContentFactory.Content(spec)
        is EmbeddedInitializationState.Failed ->
            OperitTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Operit startup failed: ${current.message}")
                }
            }
    }
}
