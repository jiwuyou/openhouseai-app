package com.ai.assistance.operit.ui.features.chat.viewmodel

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.ui.floating.FloatingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Disabled floating-window capability retained as a non-throwing chat compatibility seam. */
class FloatingWindowDelegate(
    context: Context,
    coroutineScope: CoroutineScope,
    inputProcessingState: StateFlow<InputProcessingState>,
) {
    private val floating = MutableStateFlow(false)
    private val moveToBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val isFloatingMode: StateFlow<Boolean> = floating.asStateFlow()
    val moveTaskToBackEvents: SharedFlow<Unit> = moveToBack.asSharedFlow()

    fun toggleFloatingMode(colorScheme: ColorScheme? = null, typography: Typography? = null) = Unit

    fun launchInMode(
        mode: FloatingMode,
        colorScheme: ColorScheme? = null,
        typography: Typography? = null,
        moveTaskToBackOnReady: Boolean = false,
    ) = Unit

    fun cleanup() = Unit
}
