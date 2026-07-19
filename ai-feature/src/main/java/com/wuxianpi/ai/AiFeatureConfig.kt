package com.wuxianpi.ai

enum class RuntimeMode {
    EXTERNAL_TERMUX,
    BUNDLED_TERMUX,
}

data class BundledRuntimeCredentials(
    val serviceUrl: String,
    val clientId: String,
) {
    init {
        require(serviceUrl.startsWith("http://127.0.0.1:") || serviceUrl.startsWith("http://localhost:")) {
            "Bundled runtime must expose a loopback service URL"
        }
        require(clientId.isNotBlank()) { "Bundled runtime clientId is required" }
    }
}

data class AiFeatureConfig(
    val runtimeMode: RuntimeMode,
    val bundledRuntime: BundledRuntimeCredentials? = null,
) {
    init {
        require((runtimeMode == RuntimeMode.BUNDLED_TERMUX) == (bundledRuntime != null)) {
            "BUNDLED_TERMUX requires host-provided runtime credentials; EXTERNAL_TERMUX must pair"
        }
    }

    companion object {
        fun externalTermux() = AiFeatureConfig(RuntimeMode.EXTERNAL_TERMUX)

        fun bundledTermux(adminUrl: String, token: String, clientId: String) = AiFeatureConfig(
            RuntimeMode.BUNDLED_TERMUX,
            BundledRuntimeCredentials(adminUrl, clientId),
        )

        fun bundledTermux(serviceUrl: String, clientId: String) = AiFeatureConfig(
            RuntimeMode.BUNDLED_TERMUX,
            BundledRuntimeCredentials(serviceUrl, clientId),
        )
    }
}
