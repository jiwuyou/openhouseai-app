package com.ai.assistance.operit.core.tools.packTool

import android.content.Context
import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.javascript.JsEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class ToolPkgManager(
    private val context: Context
) {
    internal val containersInternal = ConcurrentHashMap<String, ToolPkgContainerRuntime>()
    internal val subpackageByPackageNameInternal =
        ConcurrentHashMap<String, ToolPkgSubpackageRuntime>()

    private val runtimeChangeListeners =
        CopyOnWriteArrayList<PackageManager.ToolPkgRuntimeChangeListener>()
    private val executionEngines = ConcurrentHashMap<String, JsEngine>()

    fun isToolPkgContainer(packageName: String): Boolean {
        return containersInternal.containsKey(packageName.trim())
    }

    fun hasSubpackage(packageName: String): Boolean {
        return subpackageByPackageNameInternal.containsKey(packageName.trim())
    }

    fun getToolPkgContainerRuntimes(): List<ToolPkgContainerRuntime> {
        return containersInternal.values.sortedBy(ToolPkgContainerRuntime::packageName)
    }

    fun getToolPkgContainerRuntime(containerPackageName: String): ToolPkgContainerRuntime? {
        return containersInternal[containerPackageName.trim()]
    }

    fun canRegisterToolPkg(
        loadResult: ToolPkgLoadResult,
        availablePackages: Map<String, ToolPackage>
    ): Boolean {
        val containerName = loadResult.containerPackage.name.trim()
        if (
            containerName.isBlank() ||
                containersInternal.containsKey(containerName) ||
                availablePackages.containsKey(containerName)
        ) {
            return false
        }

        loadResult.subpackagePackages.forEach { subpackage ->
            val packageName = subpackage.name.trim()
            if (
                packageName.isBlank() ||
                    containersInternal.containsKey(packageName) ||
                    availablePackages.containsKey(packageName) ||
                    subpackageByPackageNameInternal.containsKey(packageName)
            ) {
                return false
            }
        }
        return true
    }

    fun registerToolPkg(loadResult: ToolPkgLoadResult): List<ToolPackage> {
        val containerName = loadResult.containerPackage.name
        containersInternal[containerName] = loadResult.containerRuntime
        loadResult.containerRuntime.subpackages.forEach { runtime ->
            subpackageByPackageNameInternal[runtime.packageName] = runtime
        }
        return loadResult.subpackagePackages
    }

    fun getEnabledToolPkgContainerRuntimes(
        enabledPackageNames: List<String>
    ): List<ToolPkgContainerRuntime> {
        val enabledSet = enabledPackageNames.toSet()
        return containersInternal.values
            .asSequence()
            .filter { runtime ->
                enabledSet.contains(runtime.packageName) ||
                    runtime.subpackages.any { subpackage ->
                        enabledSet.contains(subpackage.packageName)
                    }
            }
            .sortedBy(ToolPkgContainerRuntime::packageName)
            .toList()
    }

    fun addToolPkgRuntimeChangeListener(
        listener: PackageManager.ToolPkgRuntimeChangeListener,
        activeContainers: List<ToolPkgContainerRuntime>
    ) {
        if (!runtimeChangeListeners.contains(listener)) {
            runtimeChangeListeners.add(listener)
        }
        listener.onToolPkgRuntimeChanged(activeContainers)
    }

    fun removeToolPkgRuntimeChangeListener(listener: PackageManager.ToolPkgRuntimeChangeListener) {
        runtimeChangeListeners.remove(listener)
    }

    fun notifyToolPkgRuntimeChangeListeners(activeContainers: List<ToolPkgContainerRuntime>) {
        runtimeChangeListeners.forEach { listener ->
            listener.onToolPkgRuntimeChanged(activeContainers)
        }
    }

    fun getToolPkgExecutionEngine(contextKey: String): JsEngine {
        val normalizedKey = contextKey.trim().ifBlank { "toolpkg_main:default" }
        return executionEngines.computeIfAbsent(normalizedKey) { JsEngine(context) }
    }

    fun findToolPkgExecutionEngine(contextKey: String): JsEngine? {
        val normalizedKey = contextKey.trim()
        if (normalizedKey.isBlank()) {
            return null
        }
        return executionEngines[normalizedKey]
    }

    fun releaseToolPkgExecutionEngine(contextKey: String) {
        val normalizedKey = contextKey.trim()
        if (normalizedKey.isBlank()) {
            return
        }
        executionEngines.remove(normalizedKey)?.destroy()
    }

    fun cancelExecutionsForChat(chatId: String, reason: String): Boolean {
        var cancelledAny = false
        executionEngines.values.forEach { engine ->
            if (engine.cancelExecutionsForChat(chatId, reason)) {
                cancelledAny = true
            }
        }
        return cancelledAny
    }

    fun destroyDefaultToolPkgExecutionEngine(packageName: String) {
        val normalizedPackageName = packageName.trim()
        if (normalizedPackageName.isBlank()) {
            return
        }
        releaseToolPkgExecutionEngine("toolpkg_main:$normalizedPackageName")
        val providerPrefix = "toolpkg_provider:$normalizedPackageName:"
        executionEngines.keys
            .filter { key -> key.startsWith(providerPrefix) }
            .forEach { key -> releaseToolPkgExecutionEngine(key) }
    }

    fun clear() {
        containersInternal.clear()
        subpackageByPackageNameInternal.clear()
    }

    fun destroy() {
        executionEngines.values.forEach { engine -> engine.destroy() }
        executionEngines.clear()
    }
}
