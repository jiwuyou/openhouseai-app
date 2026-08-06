package com.wuxianpi.openhouse.feature

import android.content.Context
import android.content.Intent
import com.wuxianpi.openhouse.core.registry.OpenHouseComponent
import com.wuxianpi.openhouse.core.workspace.HttpUrlNormalizer
import com.wuxianpi.openhouse.core.workspace.WorkspaceCatalog

data class ComponentWebLaunchArgs(
    val componentId: String,
    val title: String,
    val fallbackUrl: String,
    val resolvedUrl: String,
    val controlTitle: String,
    val serviceNames: List<String>,
    val serviceRefs: List<String>,
    val catalogFingerprint: String = "",
) {
    val hasControlEntry: Boolean
        get() = serviceNames.isNotEmpty() || serviceRefs.isNotEmpty()

    /** Service-backed components must not silently fall back to a stale manifest port. */
    val loadUrl: String
        get() = HttpUrlNormalizer.normalize(resolvedUrl).orEmpty().ifEmpty {
            if (hasControlEntry) "" else HttpUrlNormalizer.normalize(fallbackUrl).orEmpty()
        }

    fun createIntent(context: Context): Intent =
        Intent(context, OpenHouseComponentWebActivity::class.java).apply {
            putExtra(EXTRA_COMPONENT_ID, componentId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_FALLBACK_URL, fallbackUrl)
            putExtra(EXTRA_RESOLVED_URL, resolvedUrl)
            putExtra(EXTRA_CONTROL_TITLE, controlTitle)
            putStringArrayListExtra(EXTRA_SERVICE_NAMES, ArrayList(serviceNames))
            putStringArrayListExtra(EXTRA_SERVICE_REFS, ArrayList(serviceRefs))
            putExtra(EXTRA_CATALOG_FINGERPRINT, catalogFingerprint)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        internal const val EXTRA_COMPONENT_ID = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_ID"
        internal const val EXTRA_TITLE = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_TITLE"
        internal const val EXTRA_FALLBACK_URL = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_FALLBACK_URL"
        internal const val EXTRA_RESOLVED_URL = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_RESOLVED_URL"
        internal const val EXTRA_CONTROL_TITLE = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_CONTROL_TITLE"
        internal const val EXTRA_SERVICE_NAMES = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_SERVICE_NAMES"
        internal const val EXTRA_SERVICE_REFS = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_SERVICE_REFS"
        internal const val EXTRA_CATALOG_FINGERPRINT = "com.wuxianpi.openhouse.feature.COMPONENT_WEB_CATALOG_FINGERPRINT"

        @JvmStatic
        fun from(component: OpenHouseComponent, resolvedUrl: String): ComponentWebLaunchArgs =
            ComponentWebLaunchArgs(
                componentId = component.id,
                title = component.title,
                fallbackUrl = component.url,
                resolvedUrl = resolvedUrl,
                controlTitle = component.controlTitle,
                serviceNames = component.serviceNames,
                serviceRefs = component.serviceRefs,
                catalogFingerprint = WorkspaceCatalog.componentFingerprint(component),
            )

        @JvmStatic
        fun fromIntent(intent: Intent?): ComponentWebLaunchArgs? {
            if (intent == null) return null
            val componentId = intent.getStringExtra(EXTRA_COMPONENT_ID).orEmpty().trim()
            if (componentId.isEmpty()) return null
            return ComponentWebLaunchArgs(
                componentId = componentId,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty().trim().ifEmpty { componentId },
                fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_URL).orEmpty().trim(),
                resolvedUrl = intent.getStringExtra(EXTRA_RESOLVED_URL).orEmpty().trim(),
                controlTitle = intent.getStringExtra(EXTRA_CONTROL_TITLE).orEmpty().trim(),
                serviceNames = intent.getStringArrayListExtra(EXTRA_SERVICE_NAMES).orEmpty(),
                serviceRefs = intent.getStringArrayListExtra(EXTRA_SERVICE_REFS).orEmpty(),
                catalogFingerprint = intent.getStringExtra(EXTRA_CATALOG_FINGERPRINT).orEmpty().trim(),
            )
        }
    }
}

internal enum class ComponentWebLoadPhase {
    IDLE,
    LOADING,
    CONNECTED,
    FAILED,
}

internal data class ComponentWebPageState(
    val url: String,
    val phase: ComponentWebLoadPhase = ComponentWebLoadPhase.IDLE,
) {
    fun loading(nextUrl: String = url): ComponentWebPageState = copy(
        url = nextUrl,
        phase = ComponentWebLoadPhase.LOADING,
    )

    fun connected(nextUrl: String = url): ComponentWebPageState = copy(
        url = nextUrl,
        phase = ComponentWebLoadPhase.CONNECTED,
    )

    fun failed(nextUrl: String = url): ComponentWebPageState = copy(
        url = nextUrl,
        phase = ComponentWebLoadPhase.FAILED,
    )
}
