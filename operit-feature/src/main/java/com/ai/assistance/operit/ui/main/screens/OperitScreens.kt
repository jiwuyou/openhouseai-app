package com.ai.assistance.operit.ui.main.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.common.composedsl.ToolPkgComposeDslToolScreen
import com.ai.assistance.operit.ui.features.chat.screens.AIChatScreen
import com.ai.assistance.operit.ui.features.settings.screens.ModelConfigScreen
import com.ai.assistance.operit.ui.features.settings.screens.SettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ThemeSettingsScreen
import com.ai.assistance.operit.ui.features.token.TokenConfigWebViewScreen

typealias ScreenNavigationHandler = (Screen) -> Unit

/** Screens compiled into the BASIC and RESCUE host. */
sealed class Screen(
    open val navItem: NavItem? = null,
    open val titleRes: Int? = null,
    open val participatesInCrossfadeTransition: Boolean = true,
    open val keepAlive: Boolean = false,
) {
    open fun stableScreenKey(): String? = null

    @Composable
    open fun Content(
        navController: NavController,
        navigateTo: ScreenNavigationHandler,
        onGoBack: () -> Unit,
        hasBackgroundImage: Boolean,
        onLoading: (Boolean) -> Unit,
        onError: (String) -> Unit,
        onGestureConsumed: (Boolean) -> Unit,
    ) = Unit

    data object AiChat : Screen(navItem = NavItem.AiChat) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit,
        ) {
            AIChatScreen(
                padding = PaddingValues(0.dp),
                viewModel = null,
                isFloatingMode = false,
                hasBackgroundImage = hasBackgroundImage,
                onNavigateToTokenConfig = { navigateTo(TokenConfig) },
                onNavigateToSettings = { navigateTo(Settings) },
                onNavigateToModelConfig = { navigateTo(ModelConfig) },
                onNavigateToModelPrompts = { navigateTo(Settings) },
                onNavigateToPackageManager = {},
                onLoading = onLoading,
                onError = onError,
                onGestureConsumed = onGestureConsumed,
            )
        }
    }

    data object Settings : Screen(
        navItem = NavItem.Settings,
        titleRes = R.string.nav_settings,
    ) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit,
        ) {
            SettingsScreen(
                navigateToModelConfig = { navigateTo(ModelConfig) },
                navigateToThemeSettings = { navigateTo(ThemeSettings) },
            )
        }
    }

    data object ModelConfig : Screen(
        navItem = NavItem.Settings,
        titleRes = R.string.screen_title_model_config,
    ) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit,
        ) {
            ModelConfigScreen(onBackPressed = onGoBack, navigateToMnnModelDownload = null)
        }
    }

    data object ThemeSettings : Screen(
        navItem = NavItem.Settings,
        titleRes = R.string.screen_title_theme_settings,
    ) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit,
        ) {
            ThemeSettingsScreen()
        }
    }

    data object TokenConfig : Screen(navItem = NavItem.Settings, titleRes = R.string.token_config) {
        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit,
        ) {
            TokenConfigWebViewScreen(onNavigateBack = onGoBack)
        }
    }

    data class ToolPkgComposeDsl(
        val containerPackageName: String,
        val uiModuleId: String,
        val title: String,
        override val keepAlive: Boolean = false,
    ) : Screen() {
        override fun stableScreenKey(): String =
            "toolpkg_keepalive:$containerPackageName:$uiModuleId"

        @Composable
        override fun Content(
            navController: NavController,
            navigateTo: ScreenNavigationHandler,
            onGoBack: () -> Unit,
            hasBackgroundImage: Boolean,
            onLoading: (Boolean) -> Unit,
            onError: (String) -> Unit,
            onGestureConsumed: (Boolean) -> Unit,
        ) {
            ToolPkgComposeDslToolScreen(
                navController = navController,
                routeInstanceId = "screen:$containerPackageName:$uiModuleId",
                containerPackageName = containerPackageName,
                uiModuleId = uiModuleId,
                fallbackTitle = title,
            )
        }

        @Composable
        override fun getTitle(): String = title
    }

    data class ToolPkgPluginConfig(
        val containerPackageName: String,
        val uiModuleId: String,
        val title: String,
        override val keepAlive: Boolean = false,
    ) : Screen() {
        override fun stableScreenKey(): String =
            "toolpkg_keepalive:$containerPackageName:$uiModuleId"

        @Composable
        override fun getTitle(): String = title
    }

    @Composable
    open fun getTitle(): String = titleRes?.let { stringResource(it) } ?: ""
}

object GestureStateHolder {
    var isChatScreenGestureConsumed: Boolean = false
}
