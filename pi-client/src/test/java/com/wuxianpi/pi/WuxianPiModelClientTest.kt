package com.wuxianpi.pi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class WuxianPiModelClientTest {
    @Test
    fun `apiType is inbound compatibility only and absent from public writable DTOs and requests`() {
        assertTrue(PiModelProviderDraft::class.java.declaredFields.none { it.name == "apiType" })
        assertTrue(PiModelProviderPreset::class.java.declaredFields.none { it.name == "apiType" })

        val request = PiModelSetupJson.draftRequest(
            PiModelProviderDraft(providerId = "custom", api = PiModelApi.OPENAI_COMPLETIONS),
        ).getJSONObject("draft")
        assertEquals("openai-completions", request.getString("api"))
        assertFalse(request.has("apiType"))
    }

    @Test
    fun `model headers redact authentication values while keeping custom headers visible`() {
        val headers = PiModelHeaders.of(
            mapOf(
                "Authorization" to "Bearer auth-secret",
                "X-Api-Key" to "x-api-secret",
                "api-key" to "api-secret",
                "X-Custom" to "custom-visible",
            ),
        )
        val rendered = PiModelProviderConfig(headers = headers).toString()

        assertEquals("Bearer auth-secret", headers["Authorization"])
        assertTrue(rendered.contains("Authorization=[REDACTED]"))
        assertTrue(rendered.contains("X-Api-Key=[REDACTED]"))
        assertTrue(rendered.contains("api-key=[REDACTED]"))
        assertTrue(rendered.contains("X-Custom=custom-visible"))
        for (secret in listOf("auth-secret", "x-api-secret", "api-secret")) {
            assertFalse(rendered.contains(secret))
        }
    }

    @Test
    fun `setup parses presets config auth models and strips api key fields`() = runBlocking {
        val transport = StubTransport(
            responseBody = success(
                """
                {
                  "revision":"rev-1",
                  "presets":[{
                    "id":"openai","aliases":["gpt"],"label":"OpenAI",
                    "apiType":"openai-completions",
                    "baseUrl":"https://api.openai.com/v1","recommendedModel":"gpt-5",
                    "recommendedModels":["gpt-5"],"requiresApiKey":true,
                    "category":"official","endpointCandidates":["https://api.openai.com/v1/models"],
                    "sourceTags":["pi-web"],"compat":{"operitProviderType":"OPENAI"}
                  }],
                  "config":{"providers":{"openai":{
                    "baseUrl":"https://api.openai.com/v1","apiType":"openai-completions",
                    "apiKey":"must-not-enter-model","customFlag":true,
                    "nested":{"apiKey":"nested-secret","value":"kept"},
                    "headers":{
                      "X-App":"operit",
                      "Authorization":"Bearer setup-auth-secret",
                      "X-Api-Key":"setup-x-api-secret",
                      "api-key":"setup-api-secret"
                    },
                    "models":[{"id":"gpt-5","name":"GPT 5","reasoning":true,"custom":7,"apiKey":"hidden"}]
                  }}},
                  "providers":[{"id":"openai","name":"OpenAI","authenticated":true,"authSource":"auth.json"}],
                  "models":[{"provider":"openai","id":"gpt-5","name":"GPT 5","available":true,"reasoning":true,"input":["text"],"contextWindow":200000,"maxTokens":8192}],
                  "defaultModel":{"provider":"openai","modelId":"gpt-5"}
                }
                """.trimIndent(),
            ),
        )

        val setup = client(transport).getSetup()

        assertEquals("/api/web/v1/models/setup", transport.singleRequest().url.encodedPath)
        assertEquals("GET", transport.singleRequest().method)
        assertEquals("rev-1", setup.revision)
        assertEquals(PiModelApi.OPENAI_COMPLETIONS, setup.presets.single().api)
        assertEquals("OPENAI", setup.presets.single().compat["operitProviderType"])
        assertTrue(setup.providers.single().authenticated)
        assertEquals(200000L, setup.models.single().contextWindow)
        assertEquals(PiModelRef("openai", "gpt-5"), setup.defaultModel)

        val provider = setup.config.providers.getValue("openai")
        assertEquals(PiModelApi.OPENAI_COMPLETIONS, provider.api)
        assertEquals("operit", provider.headers["X-App"])
        assertEquals("Bearer setup-auth-secret", provider.headers["Authorization"])
        assertTrue(provider.headers.toString().contains("X-App=operit"))
        assertTrue(provider.headers.toString().contains("Authorization=[REDACTED]"))
        for (secret in listOf("setup-auth-secret", "setup-x-api-secret", "setup-api-secret")) {
            assertFalse(provider.toString().contains(secret))
            assertFalse(setup.config.toString().contains(secret))
        }
        assertEquals(true, provider.additionalProperties["customFlag"])
        assertFalse(provider.additionalProperties.keys.any(::isApiKeyField))
        val nested = provider.additionalProperties["nested"] as Map<*, *>
        assertEquals("kept", nested["value"])
        assertFalse(nested.keys.filterIsInstance<String>().any(::isApiKeyField))
        assertEquals(7, provider.models.single().additionalProperties["custom"])
        assertFalse(provider.models.single().additionalProperties.keys.any(::isApiKeyField))
        assertFalse(setup.config.toString().contains("must-not-enter-model"))
        assertFalse(setup.config.toString().contains("nested-secret"))

        val roundTrip = PiModelSetupJson.applyRequest(
            PiModelApplyRequest(revision = setup.revision, config = setup.config, changes = emptyList()),
        ).getJSONObject("config").getJSONObject("providers").getJSONObject("openai")
        assertEquals("https://api.openai.com/v1", roundTrip.getString("baseUrl"))
        assertEquals("openai-completions", roundTrip.getString("api"))
        assertFalse(roundTrip.has("apiType"))
        assertEquals("kept", roundTrip.getJSONObject("nested").getString("value"))
        assertEquals("gpt-5", roundTrip.getJSONArray("models").getJSONObject(0).getString("id"))
    }

    @Test
    fun `auto fetch returns deduplicated model list with sources and partial mode failures`() = runBlocking {
        val transport = StubTransport(
            responseBody = success(
                """
                {
                  "ok":true,
                  "models":[
                    {"id":"shared","name":"Shared","sources":["anthropic-messages","openai-completions"]},
                    {"id":"gpt-only","ownedBy":"openai","sources":["openai-completions"]}
                  ],
                  "recommendedModel":"shared",
                  "candidates":["https://example.test/v1/models"],
                  "message":"2 of 4 modes returned model lists",
                  "modeResults":[
                    {
                      "api":"anthropic-messages","label":"Claude / Anthropic","ok":true,"modelCount":1,
                      "models":[{"id":"shared","name":"Shared"}],"candidates":["https://example.test/v1/models"],
                      "latencyMs":120,"status":200
                    },
                    {
                      "api":"openai-responses","label":"GPT","ok":false,"modelCount":0,
                      "models":[],"candidates":["https://example.test/v1/models"],"latencyMs":130,
                      "status":404,"error":"not found","hint":"check URL"
                    },
                    {
                      "api":"openai-completions","label":"OpenAI","ok":true,"modelCount":2,
                      "models":[{"id":"shared"},{"id":"gpt-only"}],"candidates":[],"latencyMs":90,"status":200
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val secret = "sk-auto-secret"
        val draft = PiModelProviderDraft(
            providerId = "custom",
            presetId = "custom",
            baseUrl = "https://example.test/v1",
            api = PiModelApi.AUTO,
            headers = PiModelHeaders.of(mapOf("X-Client" to "android")),
            apiKey = PiModelApiKey.of(secret),
            timeoutMs = 42_000,
        )

        val result = client(transport).fetchModels(draft)

        assertTrue(result.ok)
        assertNull(result.resolvedApi)
        assertEquals(listOf("gpt-only", "shared"), result.models.map { it.id }.sorted())
        assertEquals(
            setOf(PiModelApi.ANTHROPIC_MESSAGES, PiModelApi.OPENAI_COMPLETIONS),
            result.models.first { it.id == "shared" }.sources.toSet(),
        )
        assertEquals(3, result.modeResults.size)
        assertEquals(404, result.modeResults.first { !it.ok }.status)
        assertEquals("check URL", result.modeResults.first { !it.ok }.hint)

        val request = transport.singleRequest()
        assertEquals(WuxianPiModelClient.MODELS_FETCH_PATH, request.url.encodedPath)
        val requestJson = request.jsonBody()
        assertEquals(42_000L, requestJson.getLong("timeoutMs"))
        val requestDraft = requestJson.getJSONObject("draft")
        assertEquals("auto", requestDraft.getString("api"))
        assertFalse(requestDraft.has("apiType"))
        assertEquals(secret, requestDraft.getString("apiKey"))
        assertFalse(draft.toString().contains(secret))
        assertFalse(draft.apiKey.toString().contains(secret))
    }

    @Test
    fun `draft test preserves resolved api status and response text`() = runBlocking {
        val transport = StubTransport(
            responseBody = success(
                """
                {
                  "ok":true,"provider":"custom","modelId":"gpt-5","latencyMs":321,
                  "status":200,"text":"OK"
                }
                """.trimIndent(),
            ),
        )
        val result = client(transport).testModel(
            PiModelProviderDraft(
                providerId = "custom",
                baseUrl = "https://example.test/v1",
                api = PiModelApi.OPENAI_RESPONSES,
                apiKey = PiModelApiKey.of("secret"),
            ),
            modelId = "gpt-5",
        )

        assertEquals(PiModelApi.OPENAI_RESPONSES, result.resolvedApi)
        assertTrue(result.modeResults.isEmpty())
        assertEquals("custom", result.provider)
        assertEquals("gpt-5", result.modelId)
        assertEquals(321L, result.latencyMs)
        assertEquals("OK", result.responseText)
        val request = transport.singleRequest()
        assertEquals(WuxianPiModelClient.MODELS_TEST_PATH, request.url.encodedPath)
        val requestBody = request.jsonBody()
        assertEquals(WuxianPiModelClient.DEFAULT_TEST_TIMEOUT_MS, requestBody.getLong("timeoutMs"))
        val requestDraft = requestBody.getJSONObject("draft")
        assertEquals("openai-responses", requestDraft.getString("api"))
        assertFalse(requestDraft.has("apiType"))
        assertEquals("gpt-5", requestDraft.getJSONObject("model").getString("id"))
    }

    @Test
    fun `apply serializes revision provider changes and credential actions`() = runBlocking {
        val transport = StubTransport(responseBody = success(emptySetup("rev-2")))
        val key = PiModelApiKey.of("apply-secret")
        val provider = PiModelProviderConfig(
            baseUrl = "https://example.test/v1",
            api = PiModelApi.OPENAI_COMPLETIONS,
            headers = PiModelHeaders.of(
                mapOf(
                    "Authorization" to "Bearer apply-header-secret",
                    "X-Custom" to "custom-visible",
                ),
            ),
            models = listOf(PiConfiguredModel(id = "model-a")),
            additionalProperties = mapOf("custom" to "kept", "apiType" to "google-generative-ai"),
        )
        val request = PiModelApplyRequest(
            revision = "rev-1",
            config = PiModelSetupConfig(mapOf("set-provider" to provider, "keep-provider" to provider)),
            changes = listOf(
                PiModelProviderChange.Upsert("set-provider", provider, PiModelCredentialMutation.Set(key)),
                PiModelProviderChange.Upsert("keep-provider", provider, PiModelCredentialMutation.Keep),
                PiModelProviderChange.Remove("remove-provider", PiModelCredentialMutation.Remove),
            ),
            defaultModel = PiModelRef("set-provider", "model-a"),
            setGlobalDefault = true,
        )

        val setup = client(transport).apply(request)

        assertEquals("rev-2", setup.revision)
        val sent = transport.singleRequest().jsonBody()
        assertEquals(WuxianPiModelClient.MODELS_APPLY_PATH, transport.singleRequest().url.encodedPath)
        assertEquals("rev-1", sent.getString("revision"))
        assertTrue(sent.getBoolean("setGlobalDefault"))
        assertEquals("model-a", sent.getJSONObject("defaultModel").getString("modelId"))
        val providers = sent.getJSONObject("config").getJSONObject("providers")
        assertFalse(providers.getJSONObject("set-provider").has("apiKey"))
        assertFalse(providers.getJSONObject("set-provider").has("apiType"))
        assertEquals("openai-completions", providers.getJSONObject("set-provider").getString("api"))
        assertEquals("kept", providers.getJSONObject("set-provider").getString("custom"))
        assertEquals(
            "Bearer apply-header-secret",
            providers.getJSONObject("set-provider").getJSONObject("headers").getString("Authorization"),
        )
        assertFalse(request.toString().contains("apply-header-secret"))
        assertTrue(request.toString().contains("custom-visible"))
        val changes = sent.getJSONArray("changes")
        assertFalse(changes.getJSONObject(0).getJSONObject("provider").has("apiType"))
        assertEquals(
            "openai-completions",
            changes.getJSONObject(0).getJSONObject("provider").getString("api"),
        )
        assertEquals("apply-secret", changes.getJSONObject(0).getJSONObject("credential").getString("apiKey"))
        assertEquals("keep", changes.getJSONObject(1).getJSONObject("credential").getString("action"))
        assertEquals("remove", changes.getJSONObject(2).getString("action"))
        assertFalse(request.toString().contains("apply-secret"))
    }

    @Test
    fun `revision conflict keeps structured code message and details`() = runBlocking {
        val transport = StubTransport(
            code = 409,
            responseBody = """
                {"ok":false,"error":{"code":"model_revision_conflict","message":"configuration changed","details":{"expected":"rev-old","actual":"rev-new"}}}
            """.trimIndent(),
        )

        try {
            client(transport).apply(
                PiModelApplyRequest(
                    revision = "rev-old",
                    config = PiModelSetupConfig(emptyMap()),
                    changes = emptyList(),
                ),
            )
            fail("Expected WuxianPiModelApiException")
        } catch (error: WuxianPiModelApiException) {
            assertEquals(409, error.statusCode)
            assertEquals("model_revision_conflict", error.code)
            assertEquals("configuration changed", error.message)
            assertEquals("rev-old", error.expectedRevision)
            assertEquals("rev-new", error.actualRevision)
        }
    }

    @Test
    fun `discovery failure keeps per mode diagnostics in structured error`() = runBlocking {
        val apiKey = "server-secret"
        val authorization = "Bearer header-secret"
        val secondaryHeader = "secondary-secret"
        val transport = StubTransport(
            code = 400,
            responseBody = """
                {
                  "ok":false,
                  "error":{
                    "code":"model_discovery_failed",
                    "message":"$apiKey was rejected with $authorization and $secondaryHeader",
                    "details":{
                      "hint":"Check $secondaryHeader for client-visible-marker",
                      "nested":{"echo":"$apiKey / header-secret"},
                      "modeResults":[{
                        "api":"google-generative-ai","label":"Gemini","ok":false,"modelCount":0,
                        "models":[],"candidates":["https://example.test/v1beta/models"],
                        "latencyMs":15000,"status":401,
                        "error":"unauthorized $apiKey header-secret","hint":"check $secondaryHeader"
                      }]
                    }
                  }
                }
            """.trimIndent(),
        )

        try {
            client(transport).fetchModels(
                PiModelProviderDraft(
                    providerId = "custom",
                    baseUrl = "https://example.test",
                    api = PiModelApi.AUTO,
                    headers = PiModelHeaders.of(
                        mapOf(
                            "Authorization" to authorization,
                            "X-Api-Key" to secondaryHeader,
                            "X-Client" to "client-visible-marker",
                        ),
                    ),
                    apiKey = PiModelApiKey.of(apiKey),
                ),
            )
            fail("Expected WuxianPiModelApiException")
        } catch (error: WuxianPiModelApiException) {
            assertEquals("model_discovery_failed", error.code)
            assertEquals(1, error.modeResults.size)
            assertEquals(PiModelApi.GOOGLE_GENERATIVE_AI, error.modeResults.single().api)
            assertEquals(401, error.modeResults.single().status)
            assertEquals(listOf("https://example.test/v1beta/models"), error.modeResults.single().candidates)
            for (secret in listOf(apiKey, authorization, "header-secret", secondaryHeader)) {
                assertFalse(error.message.orEmpty().contains(secret))
                assertFalse(error.details.toString().contains(secret))
                assertFalse(error.toString().contains(secret))
            }
            assertTrue(error.details.toString().contains("client-visible-marker"))
            assertTrue(error.message.orEmpty().contains("[REDACTED]"))
        }
    }

    @Test
    fun `default and attached model clients reuse the shared okhttp transport`() {
        val config = PiServiceConfig("http://127.0.0.1:8765".toHttpUrl())
        val standalone = WuxianPiModelClient(config)
        assertSame(PiHttpTransport.sharedClient, standalone.http)

        val main = WuxianPiClient(config)
        try {
            assertSame(PiHttpTransport.sharedClient, main.models.http)
        } finally {
            main.close()
        }

        val injected = OkHttpClient()
        val injectedMain = WuxianPiClient(config, injected)
        try {
            assertSame(injected, injectedMain.models.http)
        } finally {
            injectedMain.close()
            injected.dispatcher.executorService.shutdown()
            injected.connectionPool.evictAll()
        }
    }

    @Test
    fun `response body is consumed away from the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        val responseBody = ThreadRecordingResponseBody(success(emptySetup("io-thread")))
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody)
                    .build()
            }
            .build()

        try {
            assertEquals("io-thread", WuxianPiModelClient(serviceConfig(), http).getSetup().revision)
            assertTrue(responseBody.readThread.get() != null)
            assertFalse(callerThread === responseBody.readThread.get())
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `response closes when cancelled after delivery but before parsing`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val responseBody = CloseTrackingResponseBody(success(emptySetup("never-parsed")))
        val response =
            Response.Builder()
                .request(Request.Builder().url("http://127.0.0.1/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody)
                .build()

        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            response.consumeOn(dispatcher) { delivered ->
                delivered.body?.string()
            }
        }
        assertTrue(dispatcher.dispatched.await(2, TimeUnit.SECONDS))

        request.cancel()
        dispatcher.runPending()
        request.join()

        assertTrue(request.isCancelled)
        assertTrue(responseBody.closed.await(2, TimeUnit.SECONDS))
        assertFalse(responseBody.readStarted.get())
    }

    @Test
    fun `coroutine cancellation cancels the real okhttp call`() = runBlocking {
        val interceptor = CancelAwareInterceptor()
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = WuxianPiModelClient(serviceConfig(), http)
        val request = launch(Dispatchers.IO) { client.getSetup() }

        assertTrue(interceptor.started.await(2, TimeUnit.SECONDS))
        request.cancelAndJoin()

        assertTrue(request.isCancelled)
        assertTrue(interceptor.canceled.await(2, TimeUnit.SECONDS))
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun `okhttp call timeout becomes a structured transport timeout`() = runBlocking {
        val interceptor = TimeoutAwareInterceptor()
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = WuxianPiModelClient(
            config = serviceConfig(),
            http = http,
            timeouts = WuxianPiModelClientTimeouts(setupRequestMs = 100, applyRequestMs = 100, httpGraceMs = 0),
        )

        try {
            client.getSetup()
            fail("Expected WuxianPiModelTransportException")
        } catch (error: WuxianPiModelTransportException) {
            assertEquals(WuxianPiModelTransportFailure.TIMEOUT, error.failure)
            assertTrue(error.message.orEmpty().contains("timed out"))
            assertTrue(error.cause is InterruptedIOException)
        } finally {
            assertTrue(interceptor.timedOut.await(2, TimeUnit.SECONDS))
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `cancellation and response callback race completes at most once`() = runBlocking {
        repeat(12) {
            val interceptor = ReleaseRaceInterceptor(success(emptySetup("race")))
            val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
            val client = WuxianPiModelClient(serviceConfig(), http)
            val request = async(Dispatchers.IO) { client.getSetup() }
            assertTrue(interceptor.started.await(2, TimeUnit.SECONDS))

            val release = launch(Dispatchers.Default) { interceptor.release.countDown() }
            val cancel = launch(Dispatchers.Default) { request.cancel() }
            joinAll(release, cancel)
            withTimeout(2_000) { request.join() }

            assertTrue(request.isCompleted)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun client(transport: StubTransport): WuxianPiModelClient = WuxianPiModelClient(
        config = serviceConfig(),
        http = OkHttpClient.Builder().addInterceptor(transport).build(),
    )

    private fun serviceConfig(): PiServiceConfig =
        PiServiceConfig("http://127.0.0.1:8765/ignored?old=true".toHttpUrl())

    private fun success(data: String): String = """{"ok":true,"data":$data}"""

    private fun emptySetup(revision: String): String =
        """{"revision":"$revision","presets":[],"config":{"providers":{}},"providers":[],"models":[],"defaultModel":null}"""

    private fun Request.jsonBody(): JSONObject {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun isApiKeyField(value: String): Boolean =
        value.replace("_", "").replace("-", "").equals("apikey", ignoreCase = true)

    private class StubTransport(
        private val responseBody: String,
        private val code: Int = 200,
    ) : Interceptor {
        private val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            synchronized(requests) { requests += request }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        fun singleRequest(): Request = synchronized(requests) { requests.single() }
    }

    private class ThreadRecordingResponseBody(private val content: String) : ResponseBody() {
        val readThread = AtomicReference<Thread?>()

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength(): Long = content.toByteArray().size.toLong()

        override fun source(): BufferedSource {
            readThread.compareAndSet(null, Thread.currentThread())
            return Buffer().writeUtf8(content)
        }
    }

    private class CloseTrackingResponseBody(content: String) : ResponseBody() {
        val closed = CountDownLatch(1)
        val readStarted = AtomicBoolean(false)
        private val trackedSource =
            object : okio.ForwardingSource(Buffer().writeUtf8(content)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readStarted.set(true)
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    closed.countDown()
                    super.close()
                }
            }.buffer()

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = trackedSource
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val dispatched = CountDownLatch(1)
        private val pending = AtomicReference<Runnable?>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(pending.compareAndSet(null, block)) { "Only one dispatch is expected" }
            dispatched.countDown()
        }

        fun runPending() {
            requireNotNull(pending.getAndSet(null)) { "No dispatched block is pending" }.run()
        }
    }

    private class CancelAwareInterceptor : Interceptor {
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            canceled.countDown()
            throw IOException("Canceled")
        }
    }

    private class TimeoutAwareInterceptor : Interceptor {
        val timedOut = CountDownLatch(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            while (!chain.call().isCanceled()) Thread.sleep(5)
            timedOut.countDown()
            throw InterruptedIOException("timeout")
        }
    }

    private class ReleaseRaceInterceptor(private val responseBody: String) : Interceptor {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            val request = chain.request()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
