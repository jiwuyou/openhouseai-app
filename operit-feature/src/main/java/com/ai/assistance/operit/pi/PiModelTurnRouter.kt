package com.ai.assistance.operit.pi

import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.model.usesAndroidLocalModelEngine

internal enum class ModelTurnBackend {
    RESCUE,
    ANDROID_LOCAL,
    PI_RUNTIME,
}

internal data class ModelTurnRoute(
    val backend: ModelTurnBackend,
    val config: ModelConfigData,
    val binding: PiModelBinding? = null,
)

internal suspend fun resolveModelTurnRoute(
    isRescueRuntime: Boolean,
    initialConfig: ModelConfigData,
    ensureMigrated: suspend () -> Unit,
    reloadConfig: suspend () -> ModelConfigData,
): ModelTurnRoute {
    if (isRescueRuntime) {
        return ModelTurnRoute(ModelTurnBackend.RESCUE, initialConfig)
    }
    if (initialConfig.usesAndroidLocalModelEngine()) {
        return ModelTurnRoute(ModelTurnBackend.ANDROID_LOCAL, initialConfig)
    }

    ensureMigrated()
    val migratedConfig = reloadConfig()
    if (migratedConfig.usesAndroidLocalModelEngine()) {
        return ModelTurnRoute(ModelTurnBackend.ANDROID_LOCAL, migratedConfig)
    }
    val binding = requireNotNull(migratedConfig.piModelBinding) {
        "当前命名配置尚未绑定 Pi 模型，请先在模型设置中保存并启用"
    }
    return ModelTurnRoute(ModelTurnBackend.PI_RUNTIME, migratedConfig, binding)
}
