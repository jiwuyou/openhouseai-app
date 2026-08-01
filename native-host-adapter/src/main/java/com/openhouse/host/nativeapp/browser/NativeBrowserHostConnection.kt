package com.openhouse.host.nativeapp.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openhouse.host.nativeapp.NativeOpenHouseHost
import com.termux.app.browser.ControlledBrowserRuntime
import com.wuxianpi.browser.host.BrowserHostDispatcher
import com.wuxianpi.browser.host.BrowserHost
import com.wuxianpi.browser.host.BrowserHostEvent
import com.wuxianpi.browser.host.BrowserHostRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One reconnecting Browser Host v1 connection for the Native APK browser process. */
internal class NativeBrowserHostConnection private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolverExecutor = Executors.newSingleThreadExecutor()
    private val resolver = NativeBrowserRuntimeEndpointResolver(NativeOpenHouseHost(appContext))
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()
    private val started = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_MS

    private val eventListener = BrowserHost.EventListener { event: BrowserHostEvent ->
        mainHandler.post {
            val runtime = ControlledBrowserRuntime.getInstance()
            val tabs = runtime.getOrCreateView(appContext).tabsSnapshot
            socket?.send(buildBrowserEvent(event, tabs, activeContext(tabs)).toString())
        }
        Unit
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val runtime = ControlledBrowserRuntime.getInstance()
        runtime.configureHost(com.wuxianpi.browser.host.BrowserHostDescription.nativeHost())
        runtime.ensureStarted(appContext)
        runtime.addEventListener(eventListener)
        resolveAndConnect()
    }

    private fun resolveAndConnect() {
        resolverExecutor.execute {
            val endpoint = runCatching { resolver.resolve() }.getOrNull()
            mainHandler.post {
                if (endpoint == null) {
                    scheduleReconnect("runtime endpoint unavailable")
                } else {
                    connect(browserWebSocketUrl(endpoint))
                }
            }
        }
    }

    private fun connect(url: String) {
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun sendRegistration(webSocket: WebSocket) {
        val runtime = ControlledBrowserRuntime.getInstance()
        val description = runtime.describe().toJson()
        val tabs = runtime.getOrCreateView(appContext).tabsSnapshot
        val message = buildBrowserRegistration(
            description = description,
            implementationVersion = applicationVersion(appContext),
            tabs = tabs,
            context = activeContext(tabs),
        )
        webSocket.send(message.toString())
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = message.optString("type", message.optString("messageType"))
        if (type.isNotEmpty() && type != "browser.invoke" && type != "invoke") return
        val request = runCatching { BrowserHostRequest.fromJson(message) }.getOrElse { error ->
            webSocket.send(
                com.wuxianpi.browser.host.BrowserHostResponse.error(
                    message.optString("requestId", message.optString("id")),
                    "invalid_request",
                    error.message ?: "Invalid browser request",
                ).toJson().toString(),
            )
            return
        }
        BrowserHostDispatcher.getInstance().dispatch(request) { response ->
            webSocket.send(response.toJson().toString())
        }
    }

    private fun scheduleReconnect(reason: String) {
        Log.w(TAG, "Browser host disconnected: $reason")
        socket = null
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private val reconnectRunnable = Runnable { resolveAndConnect() }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectDelayMs = INITIAL_RECONNECT_MS
            mainHandler.post { sendRegistration(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(webSocket, text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect("$code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect(t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "NativeBrowserHost"
        private const val INITIAL_RECONNECT_MS = 1_000L
        private const val MAX_RECONNECT_MS = 30_000L
        @Volatile private var instance: NativeBrowserHostConnection? = null

        fun get(context: Context): NativeBrowserHostConnection =
            instance ?: synchronized(this) {
                instance ?: NativeBrowserHostConnection(context).also { instance = it }
            }
    }
}

internal fun buildBrowserRegistration(
    description: JSONObject,
    implementationVersion: String,
    tabs: JSONArray,
    context: JSONObject?,
): JSONObject = JSONObject()
    .put("type", "browser.register")
    .put("protocol", "wuxianpi-browser-host-v1")
    .put("protocolVersion", 1)
    .put("hostId", description.getString("hostId"))
    .put("priority", description.getInt("priority"))
    .put("implementationVersion", implementationVersion.ifBlank { "unknown" })
    .put("capabilities", description.getJSONObject("capabilities"))
    .put("tabs", tabs)
    .apply { if (context != null) put("context", context) }

internal fun buildBrowserEvent(
    event: BrowserHostEvent,
    tabs: JSONArray,
    context: JSONObject?,
): JSONObject = JSONObject()
    .put("type", "browser.event")
    .put("event", event.name)
    .put("at", event.timestamp)
    .put("data", event.data)
    .put("tabs", tabs)
    .put("context", context ?: JSONObject.NULL)
    .apply {
        event.data.optString("tabId", event.data.optString("id"))
            .takeIf { it.isNotBlank() }
            ?.let { put("tabId", it) }
    }

private fun activeContext(tabs: JSONArray): JSONObject? =
    (0 until tabs.length())
        .mapNotNull(tabs::optJSONObject)
        .firstOrNull { it.optBoolean("active", false) }
        ?.optJSONObject("context")

@Suppress("DEPRECATION")
private fun applicationVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("unknown").ifBlank { "unknown" }

internal fun browserWebSocketUrl(baseUrl: String): String {
    val base = URI(baseUrl.trim())
    val scheme = when (base.scheme?.lowercase()) {
        "https", "wss" -> "wss"
        else -> "ws"
    }
    val basePath = base.path.orEmpty().trimEnd('/')
    val path = if (basePath.isEmpty() || basePath == "/") {
        "/v1/browser-host"
    } else {
        "$basePath/v1/browser-host"
    }
    return URI(scheme, base.userInfo, base.host, base.port, path, null, null).toString()
}
