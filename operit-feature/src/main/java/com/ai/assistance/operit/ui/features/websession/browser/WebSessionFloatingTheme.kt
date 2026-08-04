package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.runtime.Composable

/** Browser sessions use the host theme now that floating-window theming is unavailable. */
@Composable
internal fun WebSessionFloatingTheme(content: @Composable () -> Unit) = content()
