package com.ai.assistance.operit.pi

import android.content.Context
import com.ai.assistance.operit.data.model.PiModelBinding
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.wuxianpi.pi.PiModelDraftResult
import com.wuxianpi.pi.PiModelSetupState
import com.wuxianpi.pi.PiServiceConfig
import com.wuxianpi.pi.WuxianPiClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android-facing adapter for the Pi-owned model setup API. */
class PiModelSettingsAdapter private constructor() {
    private val client = WuxianPiClient(PiServiceConfig(OPERIT_PI_RUNTIME_URL.toHttpUrl()))
    internal val repository = PiModelSetupRepository(client.models)
    private val migrationMutex = Mutex()

    companion object {
        val instance: PiModelSettingsAdapter by lazy { PiModelSettingsAdapter() }
    }

    suspend fun setup(): PiModelSetupState = repository.setup()

    suspend fun fetch(draft: PiModelEditorDraft): PiModelDraftResult = repository.fetch(draft)

    suspend fun test(draft: PiModelEditorDraft): PiModelDraftResult = repository.test(draft)

    suspend fun testSavedBinding(binding: PiModelBinding): PiModelDraftResult =
        repository.testSavedBinding(repository.setup(), binding)

    suspend fun apply(
        setup: PiModelSetupState,
        draft: PiModelEditorDraft,
        setGlobalDefault: Boolean,
    ): PiModelApplyOutcome = repository.apply(setup, draft, setGlobalDefault)

    suspend fun migrateLegacyConfigs(
        context: Context,
        manager: ModelConfigManager,
    ): PiModelMigrationReport = migrationMutex.withLock {
        PiModelConfigMigration(
            repository = repository,
            configs = AndroidPiModelMigrationConfigStore(manager),
            state = AndroidPiModelMigrationStateStore(context.applicationContext),
            rescueStore = AndroidPiModelMigrationRescueStore(context.applicationContext),
        ).migrateIfNeeded()
    }

    suspend fun ensureLegacyConfigsMigrated(
        context: Context,
        manager: ModelConfigManager,
    ) {
        val report = migrateLegacyConfigs(context, manager)
        check(report.complete) {
            "旧模型配置迁移失败，可重试：${report.failures.entries.joinToString { "${it.key}: ${it.value}" }}"
        }
    }

    suspend fun defaultModelDisplay(): Pair<String, String> {
        val setup = repository.setup()
        val selected = setup.defaultModel
            ?: throw IllegalStateException(setup.availabilityError ?: "Pi 尚未设置默认模型")
        val model = setup.models.firstOrNull {
            it.provider == selected.provider && it.id == selected.modelId
        }
        return selected.provider to (model?.name ?: selected.modelId)
    }

    suspend fun bindingDisplay(binding: PiModelBinding): Pair<String, String> {
        val setup = repository.setup()
        val model = setup.models.firstOrNull {
            it.provider == binding.provider && it.id == binding.modelId
        }
        return binding.provider to (model?.name ?: binding.modelId)
    }
}
