package com.wuxianpi.pi

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class WuxianPiModelTransportFailure {
    TIMEOUT,
    NETWORK,
}

class WuxianPiModelTransportException(
    val failure: WuxianPiModelTransportFailure,
    message: String,
    cause: IOException,
) : IOException(message, cause)

class WuxianPiModelApiException(
    val statusCode: Int,
    val code: String,
    override val message: String,
    val details: Any? = null,
) : IOException(message) {
    val modeResults: List<PiModelDiscoveryModeResult>
        get() = PiModelSetupJson.parseErrorModeResults(details)

    val expectedRevision: String?
        get() = (details as? Map<*, *>)?.get("expected") as? String

    val actualRevision: String?
        get() = (details as? Map<*, *>)?.get("actual") as? String
}

data class WuxianPiModelClientTimeouts(
    val setupRequestMs: Long = WuxianPiModelClient.SETUP_REQUEST_TIMEOUT_MS,
    val applyRequestMs: Long = WuxianPiModelClient.APPLY_REQUEST_TIMEOUT_MS,
    val httpGraceMs: Long = WuxianPiModelClient.HTTP_TIMEOUT_GRACE_MS,
) {
    init {
        require(setupRequestMs > 0) { "Setup request timeout must be positive" }
        require(applyRequestMs > 0) { "Apply request timeout must be positive" }
        require(httpGraceMs >= 0) { "HTTP timeout grace must not be negative" }
    }
}

/** HTTP client for the shared Pi-owned model setup state. */
class WuxianPiModelClient(
    private val config: PiServiceConfig,
    internal val http: OkHttpClient = PiHttpTransport.sharedClient,
    private val timeouts: WuxianPiModelClientTimeouts = WuxianPiModelClientTimeouts(),
) {
    suspend fun getSetup(): PiModelSetupState = request(
        method = "GET",
        path = MODELS_SETUP_PATH,
        body = null,
        timeoutMs = timeouts.setupRequestMs,
        parser = PiModelSetupJson::parseSetup,
    )

    suspend fun fetchModels(draft: PiModelProviderDraft): PiModelDraftResult {
        val runtimeTimeout = draft.timeoutMs ?: DEFAULT_DISCOVERY_TIMEOUT_MS
        return request(
            method = "POST",
            path = MODELS_FETCH_PATH,
            body = PiModelSetupJson.draftRequest(draft.copy(timeoutMs = runtimeTimeout)),
            timeoutMs = boundedRuntimeTimeout(runtimeTimeout) + timeouts.httpGraceMs,
            parser = PiModelSetupJson::parseDraftResult,
        )
    }

    suspend fun testModel(draft: PiModelProviderDraft, modelId: String): PiModelDraftResult {
        require(modelId.isNotBlank()) { "Model ID must not be blank" }
        require(draft.api != PiModelApi.AUTO) {
            "Test a resolved API mode; auto is only valid for model discovery"
        }
        val runtimeTimeout = draft.timeoutMs ?: DEFAULT_TEST_TIMEOUT_MS
        val result = request(
            method = "POST",
            path = MODELS_TEST_PATH,
            body = PiModelSetupJson.draftRequest(draft.copy(timeoutMs = runtimeTimeout), modelId),
            timeoutMs = boundedRuntimeTimeout(runtimeTimeout) + timeouts.httpGraceMs,
            parser = PiModelSetupJson::parseDraftResult,
        )
        val submittedApi = draft.api
        return if (result.resolvedApi == null && submittedApi != null && submittedApi != PiModelApi.AUTO) {
            result.copy(resolvedApi = submittedApi)
        } else {
            result
        }
    }

    suspend fun apply(request: PiModelApplyRequest): PiModelSetupState = request(
        method = "POST",
        path = MODELS_APPLY_PATH,
        body = PiModelSetupJson.applyRequest(request),
        timeoutMs = timeouts.applyRequestMs,
        parser = PiModelSetupJson::parseSetup,
    )

    private suspend fun <T> request(
        method: String,
        path: String,
        body: JSONObject?,
        timeoutMs: Long,
        parser: (JSONObject) -> T,
    ): T {
        val sensitiveValues = PiModelSetupJson.sensitiveValues(body)
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(resolveServiceHttpUrl(config.baseUrl, path))
            .method(method, requestBody)
            .header("Accept", "application/json")
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        val response = try {
            call.awaitResponse()
        } catch (error: IOException) {
            val timeout = error.isTimeoutFailure()
            throw WuxianPiModelTransportException(
                failure = if (timeout) WuxianPiModelTransportFailure.TIMEOUT else WuxianPiModelTransportFailure.NETWORK,
                message = if (timeout) "WuxianPi model API request timed out" else "WuxianPi model API request failed",
                cause = error,
            )
        }
        return response.use { parseResponse(it, sensitiveValues, parser) }
    }

    private fun <T> parseResponse(
        response: Response,
        sensitiveValues: Set<String>,
        parser: (JSONObject) -> T,
    ): T {
        val body = response.body?.string().orEmpty()
        val root = try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw IOException("WuxianPi model API returned invalid JSON (HTTP ${response.code})", error)
        }
        if (!response.isSuccessful || root.optBoolean("ok", true).not()) {
            throw parseApiError(response.code, root, sensitiveValues)
        }
        val data = root.optJSONObject("data") ?: root
        return try {
            parser(data)
        } catch (error: JSONException) {
            throw IOException("WuxianPi model API returned an invalid response contract", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("WuxianPi model API returned an invalid response contract", error)
        }
    }

    private fun parseApiError(
        statusCode: Int,
        root: JSONObject,
        sensitiveValues: Set<String>,
    ): WuxianPiModelApiException {
        val error = root.optJSONObject("error")
        val code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "runtime_http_error"
        val rawMessage = error?.optString("message")?.takeIf(String::isNotBlank)
            ?: "WuxianPi model API request failed (HTTP $statusCode)"
        val rawDetails = error?.opt("details")?.let(PiModelSetupJson::jsonValue)
        return WuxianPiModelApiException(
            statusCode = statusCode,
            code = code,
            message = PiModelSetupJson.redactSensitiveText(rawMessage, sensitiveValues),
            details = PiModelSetupJson.redactSensitive(rawDetails, sensitiveValues),
        )
    }

    companion object {
        const val MODELS_SETUP_PATH = "/api/web/v1/models/setup"
        const val MODELS_FETCH_PATH = "/api/web/v1/models/fetch"
        const val MODELS_TEST_PATH = "/api/web/v1/models/test"
        const val MODELS_APPLY_PATH = "/api/web/v1/models/apply"

        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 45_000L
        const val DEFAULT_TEST_TIMEOUT_MS = 30_000L
        const val SETUP_REQUEST_TIMEOUT_MS = 30_000L
        const val APPLY_REQUEST_TIMEOUT_MS = 90_000L
        const val HTTP_TIMEOUT_GRACE_MS = 15_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun boundedRuntimeTimeout(timeoutMs: Long): Long = timeoutMs.coerceIn(1_000L, 60_000L)
    }
}

private suspend fun Call.awaitResponse(): Response {
    val pendingResponse = AtomicReference<Response?>()
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
            pendingResponse.getAndSet(null)?.close()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                pendingResponse.set(response)
                if (continuation.isCancelled) {
                    pendingResponse.getAndSet(null)?.close()
                } else {
                    continuation.resume(response)
                }
            }
        })
    }.also { response -> pendingResponse.compareAndSet(response, null) }
}

private fun IOException.isTimeoutFailure(): Boolean =
    this is InterruptedIOException && message?.contains("timeout", ignoreCase = true) == true
