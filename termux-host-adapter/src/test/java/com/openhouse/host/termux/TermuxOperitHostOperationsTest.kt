package com.openhouse.host.termux

import android.content.Context
import android.content.ContextWrapper
import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import com.ai.assistance.operit.host.OperitHostCommandResult
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryCache
import com.wuxianpi.openhouse.core.registry.RegistryCacheEntry
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.registry.RegistryRemoteResult
import com.wuxianpi.openhouse.core.registry.RegistryRepository
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxOperitHostOperationsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun termuxTargetUsesMatureTermuxEnvironment() {
        val fixture = termuxFixture()
        val result = fixture.executor.execute(
            "printf '%s' \"\$HOME|\$PREFIX|\$PATH|\$LD_LIBRARY_PATH|\$TMPDIR|\$OPERIT_HOST_TERMINAL_TARGET\"",
            HostTerminalTarget.TERMUX,
            5_000L,
        )
        assertTrue(result.error, result.isSuccess)
        val values = result.stdout.split('|')
        assertEquals(fixture.home.absolutePath, values[0])
        assertEquals(fixture.prefix.absolutePath, values[1])
        assertTrue(values[2].startsWith("${fixture.prefix}/bin:/system/bin"))
        assertTrue(values[3].startsWith("${fixture.prefix}/lib"))
        assertEquals("${fixture.prefix}/tmp", values[4])
        assertEquals("termux", values[5])
    }

    @Test
    fun androidTargetUsesIndependentSystemShellExecutor() {
        val executor = TermuxAndroidShellCommandExecutor(
            workingDirectory = temporaryFolder.root,
            shell = File("/bin/bash"),
        )
        val result = executor.execute("printf android", 5_000L)
        assertTrue(result.error, result.isSuccess)
        assertEquals("android", result.stdout)
    }

    @Test
    fun ubuntuUsesAbsoluteProotPathAndReadyRootfs() {
        val fixture = termuxFixture(ubuntuReady = true)
        val result = fixture.executor.execute(
            "printf '%s' \"\$OPERIT_HOST_TERMINAL_TARGET|\$OPERIT_UBUNTU_CONTAINER\"",
            HostTerminalTarget.UBUNTU,
            5_000L,
        )
        assertTrue(result.error, result.isSuccess)
        assertEquals("ubuntu|ubuntu", result.stdout)
        val invocations = fixture.prootLog.readLines()
        assertEquals(2, invocations.size)
        assertTrue(invocations.all { it == File(fixture.prefix, "bin/proot-distro").absolutePath })
    }

    @Test
    fun ubuntuRejectsMissingRootfsBeforeExecuting() {
        val fixture = termuxFixture(ubuntuReady = false)
        val result = fixture.executor.execute("true", HostTerminalTarget.UBUNTU, 5_000L)
        assertEquals(127, result.exitCode)
        assertTrue(result.error.contains("rootfs"))
        assertFalse(fixture.prootLog.exists())
    }

    @Test
    fun largeStdoutAndStderrAreDrainedConcurrently() {
        val fixture = termuxFixture()
        val result = fixture.executor.execute(
            "yes A | head -c 200000; yes B | head -c 200000 >&2",
            HostTerminalTarget.TERMUX,
            10_000L,
        )
        assertTrue(result.error, result.isSuccess)
        assertEquals(200_000, result.stdout.length)
        assertEquals(200_000, result.stderr.length)
    }

    @Test
    fun timeoutReturnsCapturedStreamsWithoutDeadlock() {
        val fixture = termuxFixture()
        val result = fixture.executor.execute(
            "printf before; printf problem >&2; sleep 2",
            HostTerminalTarget.TERMUX,
            80L,
        )
        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
        assertTrue(result.stdout.contains("before"))
        assertTrue(result.stderr.contains("problem"))
        assertTrue(result.error.contains("timed out"))
    }

    @Test
    fun embeddedTmuxTransportExecutesInternallyAndForwardsStdin() = runBlocking {
        val fixture = termuxFixture()
        executable(
            File(fixture.prefix, "bin/read-stdin"),
            "#!/bin/sh\nprintf '%s|' \"\$PREFIX\"\ncat\n",
        )
        val transport = EmbeddedTermuxSessionTransport(
            TermuxRuntimeLayout(fixture.prefix, fixture.home),
        )
        val result = transport.executeProgram(
            program = "read-stdin",
            arguments = emptyList(),
            stdin = "payload",
            timeoutMs = 5_000L,
        )
        assertEquals(0, result.exitCode)
        assertEquals(-1, result.errCode)
        assertEquals("${fixture.prefix.absolutePath}|payload", result.stdout)
    }

    @Test
    fun setupCommandPublishesStructuredStatus() {
        val result = setupOperationResult(
            operation = "wuxianpi_setup_status",
            action = "status",
            result = OperitHostCommandResult(
                command = "wuxianpi-setup status",
                exitCode = 0,
                stdout = "notice\n{\"state\":\"running\",\"tmuxReady\":true}\n",
                stderr = "",
                error = "",
                timedOut = false,
                durationMs = 3L,
            ),
        )

        assertTrue(result.success)
        assertEquals("running", result.details.getJSONObject("status").getString("state"))
        assertTrue(result.details.getJSONObject("status").getBoolean("tmuxReady"))
    }

    @Test
    fun setupCommandFailureDoesNotHideBackendError() {
        val result = setupOperationResult(
            operation = "prepare_persistent_termux",
            action = "prepare-tmux",
            result = OperitHostCommandResult(
                command = "wuxianpi-setup prepare-tmux",
                exitCode = 127,
                stdout = "",
                stderr = "tmux unavailable",
                error = "tmux unavailable",
                timedOut = false,
                durationMs = 4L,
            ),
        )

        assertFalse(result.success)
        assertEquals(127, result.details.getInt("exitCode"))
        assertTrue(result.error.orEmpty().contains("tmux unavailable"))
    }

    @Test
    fun embeddedHostDoesNotRequireExternalTermuxStages() = runBlocking {
        val context = TestContext(temporaryFolder.root)
        val operations = TermuxOperitHostOperations(context)

        val configure = operations.configureTermuxExternalApps()
        val verify = operations.verifyTermuxRunCommand()

        listOf(configure, verify).forEach { result ->
            assertTrue(result.success)
            assertTrue(result.details.getBoolean("skipped"))
            assertFalse(result.details.getBoolean("required"))
        }
    }

    @Test
    fun registryLoadsOffCallerThreadAndPublishesDynamicApiEntry() {
        val dynamic = RegistryManifest.fromManifestJson(dynamicManifest())
        val cache = RecordingCache()
        val loadThread = AtomicReference<String>()
        val pending = AtomicReference<(() -> Unit)>()
        val catalog = BackgroundRegistryCatalog(
            loadComponents = {
                loadThread.set(Thread.currentThread().name)
                RegistryRepository(
                    { RegistryRemoteResult(true, 200, "r1", listOf(dynamic), "") },
                    cache,
                ).load().components
            },
            startTask = { pending.set(it) },
        )

        assertTrue(catalog.components().none { it.id == "memo" })
        val task = pending.get()
        assertNotNull(task)
        Thread(task!!, "registry-worker-test").apply { start(); join() }

        assertEquals("registry-worker-test", loadThread.get())
        assertTrue(cache.saved)
        assertEquals("Memo", catalog.components().first { it.id == "memo" }.title)
    }

    @Test
    fun dynamicServiceControlRequestCarriesAllComponentBindings() {
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

    private fun termuxFixture(ubuntuReady: Boolean = false): TermuxFixture {
        val root = temporaryFolder.newFolder()
        val prefix = File(root, "usr").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        File(prefix, "tmp").mkdirs()
        File(prefix, "lib").mkdirs()
        val bin = File(prefix, "bin").apply { mkdirs() }
        executable(
            File(bin, "bash"),
            "#!/bin/sh\n" +
                "if [ \"\$1\" = \"-lc\" ]; then shift; exec /bin/bash -c \"\$@\"; fi\n" +
                "exec /bin/bash \"\$@\"\n",
        )
        val prootLog = File(root, "proot-invocations.log")
        executable(
            File(bin, "proot-distro"),
            "#!/bin/sh\nprintf '%s\\n' \"\$0\" >> '${prootLog.absolutePath}'\n" +
                "[ \"\$1\" = login ] && [ \"\$2\" = ubuntu ] && [ \"\$3\" = -- ] || exit 9\n" +
                "shift 3\nexec \"\$@\"\n",
        )
        if (ubuntuReady) File(prefix, "var/lib/proot-distro/containers/ubuntu/rootfs").mkdirs()
        return TermuxFixture(
            prefix,
            home,
            prootLog,
            TermuxHostCommandExecutor(TermuxRuntimeLayout(prefix, home), "http://127.0.0.1:20087"),
        )
    }

    private fun executable(file: File, contents: String) {
        file.writeText(contents)
        assertTrue(file.setExecutable(true))
    }

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

    private data class TermuxFixture(
        val prefix: File,
        val home: File,
        val prootLog: File,
        val executor: TermuxHostCommandExecutor,
    )

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = directory
        override fun getPackageName(): String = "com.termux"
    }

    private class RecordingCache : RegistryCache {
        var saved = false
        override fun load(): RegistryCacheEntry = RegistryCacheEntry.missing()
        override fun save(revision: String, savedAtEpochMillis: Long, manifests: List<RegistryManifest>) {
            saved = true
        }
        override fun clear() = Unit
    }
}
