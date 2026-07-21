package com.wuxianpi.openhouse.feature

data class AdvancedUiEndpoints(
    val aionUiUrl: String,
    val aiWebUiUrl: String,
) {
    companion object {
        @JvmStatic
        fun defaults() = AdvancedUiEndpoints(
            aionUiUrl = "http://127.0.0.1:25808/",
            aiWebUiUrl = "http://127.0.0.1:8765/",
        )
    }
}
