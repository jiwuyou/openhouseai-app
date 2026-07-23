package com.ai.assistance.operit.pi

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.LegacyCloudModelBackup
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PiModelBinding
import com.wuxianpi.pi.PiModelApi
import com.wuxianpi.pi.PiModelApplyRequest
import com.wuxianpi.pi.PiModelDraftResult
import com.wuxianpi.pi.PiModelProviderDraft
import com.wuxianpi.pi.PiModelProviderPreset
import com.wuxianpi.pi.PiModelSetupConfig
import com.wuxianpi.pi.PiModelSetupState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelConfigMigrationTest {
    @Test
    fun `migration is idempotent and skips Android local engines`() = runBlocking {
        val cloud = ModelConfigData(
            id = "cloud",
            name = "Cloud",
            apiKey = "secret",
            apiEndpoint = "https://example.test/v1",
            modelName = "model-a,model-b",
            apiProviderType = ApiProviderType.OPENAI_GENERIC,
            apiProviderTypeId = ApiProviderType.OPENAI_GENERIC.name,
        )
        val local = ModelConfigData(
            id = "local",
            name = "Local",
            modelName = "local.gguf",
            apiProviderType = ApiProviderType.LLAMA_CPP,
            apiProviderTypeId = ApiProviderType.LLAMA_CPP.name,
        )
        val configs = FakeMigrationConfigs(mutableListOf(cloud, local))
        val states = FakeMigrationState()
        val gateway = MigrationGateway()
        val migration = PiModelConfigMigration(PiModelSetupRepository(gateway), configs, states)

        val first = migration.migrateIfNeeded()
        val second = migration.migrateIfNeeded()

        assertTrue(first.failures.toString(), first.complete)
        assertEquals(listOf("cloud"), first.migratedConfigIds)
        assertTrue("local" in first.skippedConfigIds)
        assertEquals(1, gateway.applyCount)
        assertEquals("model-a", configs.saved["cloud"]?.first?.modelId)
        assertEquals("secret", configs.saved["cloud"]?.second?.apiKey)
        assertTrue(second.complete)
        assertEquals(1, gateway.applyCount)
    }

    @Test
    fun `failed config is retryable and completion flag is not written early`() = runBlocking {
        val configs = FakeMigrationConfigs(
            mutableListOf(
                ModelConfigData(
                    id = "cloud",
                    name = "Cloud",
                    apiEndpoint = "https://example.test/v1",
                    modelName = "model-a",
                    apiProviderType = ApiProviderType.OPENAI_GENERIC,
                    apiProviderTypeId = ApiProviderType.OPENAI_GENERIC.name,
                )
            )
        )
        val state = FakeMigrationState()
        val gateway = MigrationGateway().apply { failApply = true }
        val migration = PiModelConfigMigration(PiModelSetupRepository(gateway), configs, state)

        val failed = migration.migrateIfNeeded()
        assertFalse(failed.complete)
        assertFalse(state.complete)
        assertTrue("cloud" in failed.failures)

        gateway.failApply = false
        val retried = migration.migrateIfNeeded()
        assertTrue(retried.complete)
        assertTrue(state.complete)
        assertEquals(2, gateway.applyCount)
    }

    @Test
    fun `different credentials for same preset migrate to independent stable providers`() = runBlocking {
        val configs = FakeMigrationConfigs(
            mutableListOf(
                legacyOpenAiConfig("coding", "key-a"),
                legacyOpenAiConfig("daily", "key-b"),
            )
        )
        val gateway = MigrationGateway(presets = listOf(openAiPreset()))
        val migration = PiModelConfigMigration(
            PiModelSetupRepository(gateway),
            configs,
            FakeMigrationState(),
        )

        val report = migration.migrateIfNeeded()

        assertTrue(report.failures.toString(), report.complete)
        val coding = requireNotNull(configs.saved["coding"]?.first)
        val daily = requireNotNull(configs.saved["daily"]?.first)
        assertTrue(coding.provider.startsWith("operit-"))
        assertTrue(daily.provider.startsWith("operit-"))
        assertTrue(coding.provider != daily.provider)
    }

    @Test
    fun `chat config snapshots Rescue before Runtime apply`() = runBlocking {
        val cloud = legacyOpenAiConfig("chat", "rescue-key")
        val configs = FakeMigrationConfigs(mutableListOf(cloud), rescueConfigId = "chat")
        val rescue = FakeMigrationRescueStore()
        val gateway = MigrationGateway().apply {
            beforeApply = { assertEquals("rescue-key", rescue.snapshot?.apiKey) }
        }
        val migration = PiModelConfigMigration(
            PiModelSetupRepository(gateway),
            configs,
            FakeMigrationState(),
            rescue,
        )

        val report = migration.migrateIfNeeded()

        assertTrue(report.failures.toString(), report.complete)
        assertEquals("chat", rescue.snapshot?.id)
        assertEquals(1, rescue.snapshotCount)
    }

    @Test
    fun `failed Rescue snapshot prevents migration completion and remains retryable`() = runBlocking {
        val configs = FakeMigrationConfigs(
            mutableListOf(legacyOpenAiConfig("chat", "rescue-key")),
            rescueConfigId = "chat",
        )
        val rescue = FakeMigrationRescueStore().apply { fail = true }
        val gateway = MigrationGateway()
        val state = FakeMigrationState()
        val migration = PiModelConfigMigration(
            PiModelSetupRepository(gateway),
            configs,
            state,
            rescue,
        )

        val failed = migration.migrateIfNeeded()
        assertFalse(failed.complete)
        assertEquals(0, gateway.applyCount)

        rescue.fail = false
        val retried = migration.migrateIfNeeded()
        assertTrue(retried.complete)
        assertEquals(1, gateway.applyCount)
    }
}

private fun legacyOpenAiConfig(id: String, key: String): ModelConfigData = ModelConfigData(
    id = id,
    name = id,
    apiKey = key,
    apiEndpoint = "https://api.openai.com/v1",
    modelName = "gpt-5",
    apiProviderType = ApiProviderType.OPENAI,
    apiProviderTypeId = ApiProviderType.OPENAI.name,
)

private fun openAiPreset(): PiModelProviderPreset = PiModelProviderPreset(
    id = "openai",
    aliases = listOf("openai"),
    label = "OpenAI",
    api = PiModelApi.OPENAI_COMPLETIONS,
    baseUrl = "https://api.openai.com/v1",
    recommendedModel = "gpt-5",
    recommendedModels = listOf("gpt-5"),
    requiresApiKey = true,
    category = "official",
    endpointCandidates = emptyList(),
    sourceTags = emptyList(),
    compat = mapOf("operitProviderType" to "OPENAI"),
)

private class FakeMigrationConfigs(
    private val values: MutableList<ModelConfigData>,
    private val rescueConfigId: String? = null,
) : PiModelMigrationConfigStore {
    val saved = linkedMapOf<String, Pair<PiModelBinding, LegacyCloudModelBackup>>()

    override suspend fun allConfigs(): List<ModelConfigData> = values.toList()
    override suspend fun rescueSourceConfigId(): String? = rescueConfigId

    override suspend fun saveBinding(
        configId: String,
        binding: PiModelBinding,
        backup: LegacyCloudModelBackup,
    ) {
        saved[configId] = binding to backup
        val index = values.indexOfFirst { it.id == configId }
        values[index] = values[index].copy(piModelBinding = binding, legacyCloudBackup = backup)
    }
}

private class FakeMigrationRescueStore : PiModelMigrationRescueStore {
    var snapshot: ModelConfigData? = null
    var snapshotCount = 0
    var fail = false

    override fun snapshotIfAbsent(config: ModelConfigData) {
        if (fail) error("snapshot failed")
        if (snapshot == null) {
            snapshot = config
            snapshotCount++
        }
    }
}

private class FakeMigrationState : PiModelMigrationStateStore {
    var complete = false
    private val completedConfigs = mutableSetOf<String>()

    override fun isComplete(): Boolean = complete
    override fun isConfigComplete(configId: String): Boolean = configId in completedConfigs
    override fun markConfigComplete(configId: String) { completedConfigs += configId }
    override fun recordFailure(configId: String, message: String) = Unit
    override fun markComplete() { complete = true }
}

private class MigrationGateway(
    presets: List<PiModelProviderPreset> = emptyList(),
) : PiModelSetupGateway {
    private var setup = PiModelSetupState(
        revision = "r1",
        presets = presets,
        config = PiModelSetupConfig(emptyMap()),
        providers = emptyList(),
        models = emptyList(),
        defaultModel = null,
    )
    var applyCount = 0
    var failApply = false
    var beforeApply: (() -> Unit)? = null

    override suspend fun getSetup(): PiModelSetupState = setup
    override suspend fun fetchModels(draft: PiModelProviderDraft): PiModelDraftResult = error("unused")
    override suspend fun testModel(draft: PiModelProviderDraft, modelId: String): PiModelDraftResult = error("unused")
    override suspend fun apply(request: PiModelApplyRequest): PiModelSetupState {
        beforeApply?.invoke()
        applyCount++
        if (failApply) error("runtime unavailable")
        setup = setup.copy(revision = "r${applyCount + 1}", config = request.config)
        return setup
    }
}
