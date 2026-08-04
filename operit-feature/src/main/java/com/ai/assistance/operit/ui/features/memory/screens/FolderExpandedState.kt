package com.ai.assistance.operit.ui.features.memory.screens

import kotlinx.serialization.Serializable

@Serializable
data class FolderExpandedState(
    val expandedPaths: Set<String> = emptySet()
)
