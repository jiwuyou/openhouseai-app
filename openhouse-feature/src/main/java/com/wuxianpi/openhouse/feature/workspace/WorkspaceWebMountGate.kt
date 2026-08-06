package com.wuxianpi.openhouse.feature.workspace

/** Prevents lifecycle resume from reviving a detached page while endpoint resolution is pending. */
internal class WorkspaceWebMountGate {
    private var pendingGeneration: Long? = null

    val isPending: Boolean
        get() = pendingGeneration != null

    fun begin(generation: Long) {
        pendingGeneration = generation
    }

    fun complete(generation: Long): Boolean {
        if (pendingGeneration != generation) return false
        pendingGeneration = null
        return true
    }

    fun cancelPendingForBack(): Boolean {
        if (pendingGeneration == null) return false
        pendingGeneration = null
        return true
    }

    fun cancel() {
        pendingGeneration = null
    }
}
