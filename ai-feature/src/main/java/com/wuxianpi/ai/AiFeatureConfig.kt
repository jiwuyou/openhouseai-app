package com.wuxianpi.ai

enum class RuntimeMode {
    EXTERNAL_TERMUX,
    BUNDLED_TERMUX,
}

data class BundledRuntimeCredentials(
    val adminUrl: String,
    val token: String,
    val clientId: String,
) {
    init {
        require(adminUrl.startsWith("http://127.0.0.1:") || adminUrl.startsWith("http://localhost:")) {
            "Bundled runtime must expose a loopback admin URL"
        }
        require(token.length >= 24) { "Bundled runtime token is too short" }
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
            BundledRuntimeCredentials(adminUrl, token, clientId),
        )
    }
}
