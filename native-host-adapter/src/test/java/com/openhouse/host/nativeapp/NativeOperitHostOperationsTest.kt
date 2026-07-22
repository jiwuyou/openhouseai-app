package com.openhouse.host.nativeapp

import android.content.Context
import android.content.ContextWrapper
import com.ai.assistance.operit.host.OperitHostProvider
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryCache
import com.wuxianpi.openhouse.core.registry.RegistryCacheEntry
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.registry.RegistryRemoteResult
import com.wuxianpi.openhouse.core.registry.RegistryRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeOperitHostOperationsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun autoAndHostExecuteThroughAndroidShell() {
        val executor = executor()
        listOf(HostTerminalTarget.AUTO, HostTerminalTarget.HOST).forEach { target ->
            val result = executor.execute("printf native", target, 5_000L)
            assertTrue(result.error, result.isSuccess)
            assertEquals("native", result.stdout)
            assertEquals("", result.stderr)
        }
    }

    @Test
    fun ubuntuIsExplicitlyUnsupported() {
        val result = executor().execute("true", HostTerminalTarget.UBUNTU, 5_000L)
        assertEquals(127, result.exitCode)
        assertTrue(result.error.contains("unavailable"))
    }

    @Test
    fun largeStdoutAndStderrAreDrainedConcurrently() {
        val result = executor().execute(
            "yes A | head -c 200000; yes B | head -c 200000 >&2",
            HostTerminalTarget.HOST,
            10_000L,
        )
        assertTrue(result.error, result.isSuccess)
        assertEquals(200_000, result.stdout.length)
        assertEquals(200_000, result.stderr.length)
    }

    @Test
    fun timeoutPreservesBothStreams() {
        val result = executor().execute(
            "printf before; printf problem >&2; sleep 2",
            HostTerminalTarget.AUTO,
            80L,
        )
        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
        assertTrue(result.stdout.contains("before"))
        assertTrue(result.stderr.contains("problem"))
    }

    @Test
    fun backgroundRegistryPreservesCacheFallbackAndDynamicEntry() {
        val dynamic = RegistryManifest.fromManifestJson(dynamicManifest())
        val cache = FixedCache(RegistryCacheEntry.valid("cached", 1L, listOf(dynamic)))
        val catalog = BackgroundRegistryCatalog(
            loadComponents = {
                RegistryRepository(
                    { RegistryRemoteResult(false, 503, "", null, "offline") },
                    cache,
                ).load().components
            },
            startTask = { it() },
        )

        val components = catalog.components()
        assertEquals("Memo", components.first { it.id == "memo" }.title)
        assertFalse(cache.saved)
    }

    @Test
    fun dynamicServiceControlRequestCarriesNamesRefsTitleAndUrl() {
        val component = OpenHouseComponentParser().parse(
            RegistryManifest.fromManifestJson(dynamicManifest()),
            "test",
        )
        val request = serviceControlRequestFor(component)
        assertEquals("memo", request.componentId)
        assertEquals("Memo Control", request.title)
        assertEquals("http://127.0.0.1:23110/", request.componentEndpoint)
        assertEquals(listOf("memo-api", "memo-worker"), request.serviceIds)
        assertFalse(request.showAllServices)
    }

    @Test
    fun dynamicServiceControlEntriesRouteToTypedRequest() {
        listOf(
            serviceControlManifest("{\"type\":\"service-control\"}"),
            serviceControlManifest("{\"type\":\"native-page\",\"page\":\"service_control\"}"),
            controlOnlyManifest(),
        ).forEach { manifest ->
            val component = OpenHouseComponentParser().parse(
                RegistryManifest.fromManifestJson(manifest),
                "test",
            )
            val request = serviceControlRequestForDynamicComponent(component)
            assertNotNull(request)
            assertEquals("memo", request!!.componentId)
            assertEquals("Memo Control", request.title)
            assertEquals(listOf("memo-api", "memo-worker"), request.serviceIds)
            assertFalse(request.showAllServices)
        }
    }

    @Test
    fun productHostInstallsFullContractAndOperations() {
        val context = TestContext(temporaryFolder.newFolder())
        NativeProductHost(context).install()
        assertTrue(OperitHostProvider.currentOrNull() is NativeOperitHostContract)
        assertTrue(OperitHostProvider.currentOperationsOrNull() is NativeOperitHostOperations)
        assertSame(context, OperitHostProvider.currentOrNull()!!.applicationContext)
    }

    private fun executor() = NativeHostCommandExecutor(
        shell = File("/bin/bash"),
        workingDirectory = temporaryFolder.root,
    )

    private fun dynamicManifest() = """
        {
          "id": "memo",
          "shellMenu": {
            "title": "Memo",
            "entry": {"type": "webview", "url": "http://127.0.0.1:23110/"},
            "controlEntry": {
              "type": "service-control",
              "title": "Memo Control",
              "serviceNames": ["memo-api"],
              "serviceRefs": ["service-manager://services/memo-worker"]
            }
          }
        }
    """.trimIndent()

    private fun serviceControlManifest(entry: String) = """
        {
          "id": "memo",
          "shellMenu": {
            "title": "Memo",
            "entry": $entry,
            "controlEntry": {
              "type": "service-control",
              "title": "Memo Control",
              "serviceNames": ["memo-api"],
              "serviceRefs": ["service-manager://services/memo-worker"]
            }
          }
        }
    """.trimIndent()

    private fun controlOnlyManifest() = """
        {
          "id": "memo",
          "shellMenu": {
            "title": "Memo",
            "controlEntry": {
              "type": "service-control",
              "title": "Memo Control",
              "serviceNames": ["memo-api"],
              "serviceRefs": ["service-manager://services/memo-worker"]
            }
          }
        }
    """.trimIndent()

    private class FixedCache(private val entry: RegistryCacheEntry) : RegistryCache {
        var saved = false
        override fun load(): RegistryCacheEntry = entry
        override fun save(revision: String, savedAtEpochMillis: Long, manifests: List<RegistryManifest>) {
            saved = true
        }
        override fun clear() = Unit
    }

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = directory
        override fun getPackageName(): String = "com.openhouse.test"
    }
}
