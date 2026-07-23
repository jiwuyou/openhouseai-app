package com.ai.assistance.operit.pi

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PiModelBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelTurnRouterTest {
    @Test
    fun `rescue and Android local engines never route to Pi`() = runBlocking {
        var migrationCalled = false
        val local = config(ApiProviderType.MNN)

        val rescueRoute = resolveModelTurnRoute(true, local, { migrationCalled = true }, { local })
        val localRoute = resolveModelTurnRoute(false, local, { migrationCalled = true }, { local })

        assertEquals(ModelTurnBackend.RESCUE, rescueRoute.backend)
        assertEquals(ModelTurnBackend.ANDROID_LOCAL, localRoute.backend)
        assertFalse(migrationCalled)
    }

    @Test
    fun `first cloud chat ensures migration before reading binding`() = runBlocking {
        val legacy = config(ApiProviderType.OPENAI)
        val migrated = legacy.copy(piModelBinding = PiModelBinding("openai", "gpt-5"))
        var migratedBeforeReload = false

        val route = resolveModelTurnRoute(
            isRescueRuntime = false,
            initialConfig = legacy,
            ensureMigrated = { migratedBeforeReload = true },
            reloadConfig = {
                assertTrue(migratedBeforeReload)
                migrated
            },
        )

        assertEquals(ModelTurnBackend.PI_RUNTIME, route.backend)
        assertEquals(PiModelBinding("openai", "gpt-5"), route.binding)
    }

    @Test
    fun `migration failure is explicit and reload is not attempted`() {
        var reloaded = false
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                resolveModelTurnRoute(
                    isRescueRuntime = false,
                    initialConfig = config(ApiProviderType.OPENAI),
                    ensureMigrated = { error("migration unavailable") },
                    reloadConfig = { reloaded = true; config(ApiProviderType.OPENAI) },
                )
            }
        }
        assertFalse(reloaded)
    }
}

private fun config(provider: ApiProviderType) = ModelConfigData(
    id = "chat",
    name = "Chat",
    apiProviderType = provider,
    apiProviderTypeId = provider.name,
)
