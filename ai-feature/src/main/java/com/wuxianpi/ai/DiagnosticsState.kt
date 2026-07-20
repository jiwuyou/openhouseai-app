package com.wuxianpi.ai

data class DiagnosticsState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val connectionId: String? = null,
    val eventAckAvailable: Boolean = false,
    val persistentNodeDiagnostics: Boolean = false,
    val detailedUntilMillis: Long = 0,
    val androidDroppedEntries: Long = 0,
    val nodeSize: Long? = null,
    val message: String? = null,
    val error: String? = null,
    val exportPath: String? = null,
) {
    val detailedModeActive: Boolean get() = detailedUntilMillis > System.currentTimeMillis()
}
