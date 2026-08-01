package com.openhouse.host.nativeapp.browser

import com.openhouse.host.nativeapp.NativeOpenHouseHost
import com.openhouse.host.nativeapp.queryServiceEndpoints

internal class NativeBrowserRuntimeEndpointResolver(
    private val host: NativeOpenHouseHost,
) {
    fun resolve(): String? {
        val status = queryServiceEndpoints(
            host.runtimeConnection(),
            com.wuxianpi.openhouse.core.service.UrlConnectionHttpTransport(),
            SERVICE_ID,
        )
        return status.url.takeIf { status.success && it.isNotBlank() }
    }

    companion object {
        const val SERVICE_ID = "yuanshengwuxianpi"
    }
}
