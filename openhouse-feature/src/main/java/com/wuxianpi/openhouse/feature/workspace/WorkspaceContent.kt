package com.wuxianpi.openhouse.feature.workspace

import android.view.View

/** Host-provided content that can live inside the shared OpenHouse Activity. */
interface WorkspaceContent {
    val view: View

    fun onResume() = Unit

    fun onPause() = Unit

    fun onBackPressed(): Boolean = false

    fun onTrimMemory(level: Int) = Unit

    fun destroy() = Unit
}
