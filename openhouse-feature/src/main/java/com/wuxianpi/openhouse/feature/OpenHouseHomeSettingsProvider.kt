package com.wuxianpi.openhouse.feature

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.wuxianpi.openhouse.core.workspace.OpenHouseHomeSettingsProtocol
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalog
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the OpenHouse startup destination for the Rescue process.
 *
 * The provider is private to this application. It is nevertheless kept behind
 * a small wire contract because RescueActivity lives in :rescue_ui while this
 * provider and StartupRouteStore live in :openhouse.
 */
class OpenHouseHomeSettingsProvider : ContentProvider() {
    private lateinit var appContext: android.content.Context
    private lateinit var host: OpenHouseFeatureHost

    override fun onCreate(): Boolean {
        appContext = requireNotNull(context).applicationContext
        host = OpenHouseFeatureHosts.from(appContext)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = synchronized(this) {
        checkCaller()
        when (method) {
            OpenHouseHomeSettingsProtocol.METHOD_GET_HOME -> getHome()
            OpenHouseHomeSettingsProtocol.METHOD_LIST_CANDIDATES -> listCandidates()
            OpenHouseHomeSettingsProtocol.METHOD_SET_HOME -> {
                setHome(extras?.getString(OpenHouseHomeSettingsProtocol.ARG_COMPONENT_ID))
            }
            else -> failure("unknown_method", "Unknown OpenHouse home method: $method")
        }
    }

    private fun getHome(): Bundle {
        val entries = candidateEntries()
        val store = StartupRouteStore(appContext)
        val destination = store.resolveDestination(
            components = host.desktopComponents(),
            capabilities = host.capabilities(),
        )
        val component = (destination as? WorkspaceDestination.Component)
            ?.let { target -> entries.firstOrNull { it.destination == target }?.component }
        val home = JSONObject()
            .put("destination", destination.stableKey)
            .put("type", if (destination is WorkspaceDestination.Component) "component" else "desktop")
            .put("componentId", (destination as? WorkspaceDestination.Component)?.normalizedComponentId ?: JSONObject.NULL)
            .put("title", component?.let { it.title.ifBlank { it.id } } ?: if (destination == WorkspaceDestination.Desktop) "桌面" else "未知")
            .put("hasExplicitSelection", store.hasExplicitSelection())
        return success().apply {
            putString(OpenHouseHomeSettingsProtocol.EXTRA_HOME_JSON, home.toString())
        }
    }

    private fun listCandidates(): Bundle {
        val candidates = JSONArray()
        candidateEntries().forEach { entry ->
            val component = entry.component
            candidates.put(
                JSONObject()
                    .put("id", componentId(entry))
                    .put("title", entry.title)
                    .put("subtitle", entry.subtitle)
                    .put("section", entry.section)
                    .put("order", entry.order)
                    .put("entryType", component.entryType?.name ?: JSONObject.NULL)
                    .put("available", true),
            )
        }
        return success().apply {
            putString(OpenHouseHomeSettingsProtocol.EXTRA_CANDIDATES_JSON, candidates.toString())
        }
    }

    private fun setHome(rawComponentId: String?): Bundle {
        val normalizedId = WorkspaceDestination.normalizeId(rawComponentId)
        if (normalizedId.isBlank()) {
            return failure("invalid_component_id", "componentId must not be blank")
        }
        val entry = candidateEntries().firstOrNull {
            componentId(it) == normalizedId
        } ?: return failure(
            "component_unavailable",
            "目标小 App 尚未注册或当前不可用: $normalizedId",
        )

        val destination = WorkspaceDestination.Component(componentId(entry))
        val persisted = StartupRouteStore(appContext).setHomeDestinationBlocking(destination)
        if (!persisted) {
            return failure("persist_failed", "无法保存 OpenHouse 主页设置")
        }
        val effective = StartupRouteStore(appContext).resolveDestination(
            components = host.desktopComponents(),
            capabilities = host.capabilities(),
        )
        if (effective != destination) {
            return failure("readback_mismatch", "主页设置写入后校验失败")
        }
        return success().apply {
            putString(
                OpenHouseHomeSettingsProtocol.EXTRA_HOME_JSON,
                JSONObject()
                    .put("destination", effective.stableKey)
                    .put("type", "component")
                    .put("componentId", componentId(entry))
                    .put("title", entry.title)
                    .toString(),
            )
        }
    }

    private fun componentId(entry: com.wuxianpi.openhouse.core.workspace.WorkspaceCatalogEntry): String =
        (entry.destination as WorkspaceDestination.Component).normalizedComponentId

    private fun candidateEntries() = WorkspaceCatalog.applications(
        dynamicComponents = host.desktopComponents(),
        capabilities = host.capabilities(),
    ).filter { it.destination is WorkspaceDestination.Component }

    private fun success(): Bundle = Bundle().apply {
        putBoolean(OpenHouseHomeSettingsProtocol.EXTRA_SUCCESS, true)
    }

    private fun failure(code: String, message: String): Bundle = Bundle().apply {
        putBoolean(OpenHouseHomeSettingsProtocol.EXTRA_SUCCESS, false)
        putString(OpenHouseHomeSettingsProtocol.EXTRA_ERROR_CODE, code)
        putString(OpenHouseHomeSettingsProtocol.EXTRA_MESSAGE, message)
    }

    private fun checkCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("OpenHouse home settings are private to this application")
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = unsupported()

    private fun <T> unsupported(): T = throw UnsupportedOperationException("This provider only supports call()")
}
