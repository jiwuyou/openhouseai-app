package com.openhouse.host.nativeapp

import android.content.Context
import android.content.ContextWrapper
import com.ai.assistance.operit.host.OperitHostProvider
import com.wuxianpi.openhouse.core.registry.OpenHouseComponentParser
import com.wuxianpi.openhouse.core.registry.RegistryCache
import com.wuxianpi.openhouse.core.registry.RegistryCacheEntry
import com.wuxianpi.openhouse.core.registry.RegistryManifest
import com.wuxianpi.openhouse.core.registry.RegistryRemoteResult
import com.wuxianpi.openhouse.core.registry.RegistryRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeOperitHostOperationsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun androidShellExecutorRemainsIndependent() {
        val executor = AndroidShellCommandExecutor(
            shell = File("/bin/bash"),
            workingDirectory = temporaryFolder.root,
        )
        val result = executor.execute("printf android", 5_000L)
        assertTrue(result.error, result.isSuccess)
        assertEquals("android", result.stdout)
    }

    @Test
    fun termuxTargetUsesExternalTermuxBash() {
        val request = buildNativeTermuxCommandRequest(
            "printf native",
            ExternalTermuxCommandTarget.TERMUX,
        )
        assertEquals("/data/data/com.termux/files/usr/bin/bash", request.executable)
        assertEquals(listOf("-lc", "printf native"), request.arguments)
        assertEquals("/data/data/com.termux/files/home", request.workingDirectory)
    }

    @Test
    fun ubuntuUsesExternalTermuxProotDistro() {
        val request = buildNativeTermuxCommandRequest("id", ExternalTermuxCommandTarget.UBUNTU)
        assertEquals("/data/data/com.termux/files/usr/bin/proot-distro", request.executable)
        assertEquals(
            listOf("login", "ubuntu", "--", "bash", "-lc", "id"),
            request.arguments,
        )
        assertEquals("/data/data/com.termux/files/home", request.workingDirectory)
    }

    @Test
    fun executorMapsSuccessfulRunCommandResult() = runBlocking {
        val executor = ExternalTermuxCommandExecutor(
            NativeTermuxCommandTransport { request, timeoutMs ->
                assertEquals("printf native", request.command)
                assertEquals(5_000L, timeoutMs)
                NativeTermuxCommandResponse(
                    stdout = "native",
                    stderr = "",
                    exitCode = 0,
                    errorCode = TERMUX_SUCCESS_ERROR_CODE,
                    errorMessage = "",
                )
            },
        )
        val result = executor.execute("printf native", ExternalTermuxCommandTarget.TERMUX, 5_000L)
        assertTrue(result.error, result.isSuccess)
        assertEquals("native", result.stdout)
    }

    @Test
    fun executorMapsRunCommandTimeout() = runBlocking {
        val executor = ExternalTermuxCommandExecutor(
            NativeTermuxCommandTransport { _, _ ->
                NativeTermuxCommandResponse(
                    stdout = "before",
                    stderr = "problem",
                    exitCode = 124,
                    errorCode = 1,
                    errorMessage = "Timed out waiting for Termux command result after 80ms",
                    timedOut = true,
                )
            },
        )
        val result = executor.execute("sleep 2", ExternalTermuxCommandTarget.TERMUX, 80L)
        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
        assertTrue(result.stdout.contains("before"))
        assertTrue(result.stderr.contains("problem"))
        assertTrue(result.error.contains("Timed out"))
    }

    @Test
    fun managedRunCommandTimeoutTerminatesAndConfirmsRemoteExecution() = runBlocking {
        val requests = mutableListOf<NativeTermuxCommandRequest>()
        val timeouts = mutableListOf<Long>()
        val token = "timeout_execution"
        val transport = NativeTermuxManagedCommandTransport(
            rawTransport = NativeTermuxRawCommandTransport { request, timeoutMs ->
                requests += request
                timeouts += timeoutMs
                if (requests.size == 1) {
                    null
                } else {
                    successfulRawResponse("OPERIT_REMOTE_TERMINATED:$token:confirmed:143\n")
                }
            },
            tokenFactory = { token },
            terminationTimeoutMs = 4_000L,
        )

        val result = transport.execute(
            buildNativeTermuxCommandRequest("sleep 30", ExternalTermuxCommandTarget.TERMUX),
            80L,
        )

        assertTrue(result.timedOut)
        assertTrue(result.errorMessage.contains("termination confirmed"))
        assertEquals(listOf(80L, 4_000L), timeouts)
        assertEquals(2, requests.size)
        assertTrue(requests[0].arguments.single { it.contains("OPERIT_RUN_COMMAND_ID") }.contains("set -m"))
        val terminationScript = requests[1].arguments.single { it.contains("REMOTE_TERMINATED") }
        assertTrue(terminationScript.contains("token='$token'"))
        assertTrue(terminationScript.contains("pid_file=\"\$control_dir/\$token.pid\""))
        assertTrue(terminationScript.contains("kill -TERM -- \"-\$command_pgid\""))
        assertTrue(terminationScript.contains("/proc/\$command_pid/environ"))
        assertFalse(terminationScript.contains("pkill"))
        assertFalse(terminationScript.contains("killall"))
        assertFalse(terminationScript.contains("kill-server"))
    }

    @Test
    fun managedRunCommandCancellationTerminatesBeforePropagatingCancellation() = runBlocking {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val terminationCompleted = CompletableDeferred<Unit>()
        val requests = mutableListOf<NativeTermuxCommandRequest>()
        val token = "cancel_execution"
        val transport = NativeTermuxManagedCommandTransport(
            rawTransport = NativeTermuxRawCommandTransport { request, _ ->
                requests += request
                if (requests.size == 1) {
                    firstRequestStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    terminationCompleted.complete(Unit)
                    successfulRawResponse("OPERIT_REMOTE_TERMINATED:$token:gone\n")
                }
            },
            tokenFactory = { token },
            terminationTimeoutMs = 4_000L,
        )
        val execution = async {
            transport.execute(
                buildNativeTermuxCommandRequest("sleep 30", ExternalTermuxCommandTarget.UBUNTU),
                30_000L,
            )
        }

        firstRequestStarted.await()
        execution.cancel()
        try {
            execution.await()
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            Unit
        }

        assertTrue(terminationCompleted.isCompleted)
        assertEquals(2, requests.size)
        assertTrue(requests[1].command.contains(token))
        val terminationScript = requests[1].arguments.single { it.contains("REMOTE_TERMINATED") }
        assertTrue(terminationScript.contains("token='$token'"))
        assertTrue(terminationScript.contains("cancel_file=\"\$control_dir/\$token.cancel\""))
    }

    @Test
    fun tmuxSessionTransportUsesRunCommandProgramAndStdin() = runBlocking {
        var capturedRequest: NativeTermuxCommandRequest? = null
        val transport = NativeTermuxSessionTransport(
            NativeTermuxCommandTransport { request, timeoutMs ->
                capturedRequest = request
                assertEquals(9_000L, timeoutMs)
                NativeTermuxCommandResponse(
                    stdout = "tmux 3.5",
                    stderr = "",
                    exitCode = 0,
                    errorCode = TERMUX_SUCCESS_ERROR_CODE,
                    errorMessage = "",
                )
            },
        )
        val result = transport.executeProgram(
            program = "tmux",
            arguments = listOf("load-buffer", "-"),
            stdin = "echo ready",
            timeoutMs = 9_000L,
        )

        assertEquals("/data/data/com.termux/files/usr/bin/tmux", capturedRequest!!.executable)
        assertEquals(listOf("load-buffer", "-"), capturedRequest!!.arguments)
        assertEquals("echo ready", capturedRequest!!.stdin)
        assertEquals("tmux 3.5", result.stdout)
        assertEquals(TERMUX_SUCCESS_ERROR_CODE, result.errCode)
    }

    @Test
    fun tmuxSessionTransportReportsUnavailableExternalTermux() = runBlocking {
        val transport = NativeTermuxSessionTransport(
            NativeTermuxCommandTransport { _, _ ->
                throw NativeTermuxTransportException(127, "Termux package com.termux is not installed")
            },
        )
        val result = transport.executeProgram("tmux", listOf("-V"), null, 5_000L)
        assertEquals(127, result.exitCode)
        assertEquals(1, result.errCode)
        assertTrue(result.errorMessage.contains("not installed"))
    }

    @Test
    fun callbackRegistryCleansUpOnResultAndCancel() {
        val registry = NativeTermuxCallbackRegistry<String>()
        val cleanupCount = AtomicInteger()
        var delivered = ""
        registry.register(7, { delivered = it }, { cleanupCount.incrementAndGet() })
        assertTrue(registry.complete(7, "done"))
        assertEquals("done", delivered)
        assertEquals(1, cleanupCount.get())
        assertEquals(0, registry.size())
        assertFalse(registry.complete(7, "duplicate"))

        registry.register(8, { delivered = it }, { cleanupCount.incrementAndGet() })
        assertTrue(registry.cancel(8))
        assertEquals(2, cleanupCount.get())
        assertEquals(0, registry.size())
        assertFalse(registry.cancel(8))
    }

    @Test
    fun receiverSelectionCoversEveryNativeUiProcess() {
        val packageName = "com.wuxianpi"
        assertEquals(
            NativeDefaultTermuxCommandResultReceiver::class.java,
            NativeTermuxReceiverSelector.receiverClassFor(packageName, packageName),
        )
        assertEquals(
            NativeOpenHouseTermuxCommandResultReceiver::class.java,
            NativeTermuxReceiverSelector.receiverClassFor("$packageName:openhouse", packageName),
        )
        assertEquals(
            NativeOperitTermuxCommandResultReceiver::class.java,
            NativeTermuxReceiverSelector.receiverClassFor("$packageName:operit", packageName),
        )
        assertEquals(
            NativeRescueTermuxCommandResultReceiver::class.java,
            NativeTermuxReceiverSelector.receiverClassFor("$packageName:rescue_ui", packageName),
        )
        assertEquals(
            NativeAdvancedUiTermuxCommandResultReceiver::class.java,
            NativeTermuxReceiverSelector.receiverClassFor("$packageName:advanced_ui", packageName),
        )
    }

    private fun successfulRawResponse(stdout: String) = NativeTermuxCommandResponse(
        stdout = stdout,
        stderr = "",
        exitCode = 0,
        errorCode = TERMUX_SUCCESS_ERROR_CODE,
        errorMessage = "",
    )

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
