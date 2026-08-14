package com.ai.assistance.operit.host.setup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Process-local loopback bridge. Each UI process owns its own socket and identity; there is no
 * Android Service and no cross-process lifecycle coordinator.
 */
object OpenHouseConnectionBridge {
    data class StartResult(
        val identity: OpenHouseConnectionBridgeStore.Identity,
        val port: Int,
    )

    private var server: OpenHouseConnectionBridgeServer? = null

    @JvmStatic
    @Synchronized
    fun ensureStarted(context: Context, reason: String = "manual"): StartResult {
        val appContext = context.applicationContext
        val store = OpenHouseConnectionBridgeStore.get(appContext)
        val active = server
        if (active != null && active.isRunning()) {
            return StartResult(store.identity(), active.port())
        }

        val replacement = OpenHouseConnectionBridgeServer(store)
        val port = replacement.start()
        server = replacement
        val identity = store.identity()
        Log.i(
            LOG_TAG,
            "Bridge listening process=${identity.processName} reason=$reason " +
                "bridgeId=${identity.bridgeId.take(8)} port=$port",
        )
        return StartResult(identity, port)
    }

    private const val LOG_TAG = "OpenHouseBridge"
}

/** Starts a local Bridge only while the current UI process has a foreground Activity. */
object OpenHouseConnectionBridgeForegroundSupervisor {
    private const val CHECK_INTERVAL_MS = 15_000L

    @JvmStatic
    fun install(application: Application, processName: String?) {
        val packageName = application.packageName
        if (processName !in setOf("$packageName:openhouse", "$packageName:rescue_ui")) return

        val handler = Handler(Looper.getMainLooper())
        var startedActivities = 0
        val periodicCheck = object : Runnable {
            override fun run() {
                if (startedActivities <= 0) return
                runCatching { OpenHouseConnectionBridge.ensureStarted(application, "foreground-periodic") }
                    .onFailure { Log.w("OpenHouseBridge", "Foreground Bridge check failed", it) }
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        fun ensure(reason: String) {
            runCatching { OpenHouseConnectionBridge.ensureStarted(application, reason) }
                .onFailure { Log.w("OpenHouseBridge", "Bridge start failed: $reason", it) }
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    ensure("foreground-started")
                    handler.removeCallbacks(periodicCheck)
                    handler.postDelayed(periodicCheck, CHECK_INTERVAL_MS)
                }
            }

            override fun onActivityResumed(activity: Activity) = ensure("foreground-resumed")

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) handler.removeCallbacks(periodicCheck)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/** Android-private state shared by the direct OpenHouse code and the loopback request handlers. */
class OpenHouseConnectionBridgeStore private constructor(context: Context) {
    data class Identity(
        val bridgeId: String,
        val packageName: String,
        val processName: String,
        val activePort: Int,
    )

    private val appContext = context.applicationContext
    private val processName = currentProcessName(appContext)
    private val preferences = appContext.getSharedPreferences(
        "$FILE_NAME_PREFIX-${processName.substringAfter(':', "main").replace(Regex("[^A-Za-z0-9._-]"), "_")}",
        Context.MODE_PRIVATE,
    )

    fun identity(): Identity {
        val bridgeId = preferences.getString(KEY_BRIDGE_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_BRIDGE_ID, it).commit()
            }
        return Identity(
            bridgeId = bridgeId,
            packageName = appContext.packageName,
            processName = processName,
            activePort = preferences.getInt(KEY_ACTIVE_PORT, 0),
        )
    }

    fun managementKey(): String =
        preferences.getString(KEY_MANAGEMENT_KEY, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_MANAGEMENT_KEY, it).commit()
            }

    fun markListening(port: Int) {
        preferences.edit().putInt(KEY_ACTIVE_PORT, port).apply()
    }

    fun clearListening(port: Int) {
        if (preferences.getInt(KEY_ACTIVE_PORT, 0) == port) {
            preferences.edit().remove(KEY_ACTIVE_PORT).apply()
        }
    }

    fun write(scope: String, key: String, jsonValue: String) {
        require(scope in SCOPES) { "Unsupported bridge scope" }
        require(KEY_PATTERN.matches(key)) { "Invalid bridge key" }
        require(jsonValue.toByteArray(StandardCharsets.UTF_8).size <= MAX_VALUE_BYTES) {
            "Bridge value is too large"
        }
        JSONTokener(jsonValue).nextValue()
        preferences.edit().putString(valuePreferenceKey(scope, key), jsonValue).apply()
        if (scope == PRIVATE_SCOPE && key == SERVICE_MANAGER_CONNECTION_KEY) {
            val connection = JSONObject(jsonValue)
            WuxianPiConnectionStore.get(appContext).save(
                connection.getString("serviceManagerBaseUrl"),
                connection.getString("token"),
            )
        }
    }

    fun read(scope: String, key: String): String? =
        preferences.getString(valuePreferenceKey(scope, key), null)

    fun delete(scope: String, key: String) {
        preferences.edit().remove(valuePreferenceKey(scope, key)).apply()
    }

    private fun valuePreferenceKey(scope: String, key: String): String = "value.$scope.$key"

    companion object {
        const val PUBLIC_SCOPE = "public"
        const val PRIVATE_SCOPE = "private"
        const val SERVICE_MANAGER_CONNECTION_KEY = "service-manager.connection"
        const val SERVICE_MANAGER_PORT_KEY = "service-manager.port"

        private const val FILE_NAME_PREFIX = "openhouse-connection-bridge-v2"
        private const val KEY_BRIDGE_ID = "bridge_id"
        private const val KEY_MANAGEMENT_KEY = "management_key"
        private const val KEY_ACTIVE_PORT = "active_port"
        private const val MAX_VALUE_BYTES = 64 * 1024
        private val SCOPES = setOf(PUBLIC_SCOPE, PRIVATE_SCOPE)
        private val KEY_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")

        fun get(context: Context) = OpenHouseConnectionBridgeStore(context)

        private fun currentProcessName(context: Context): String =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                Application.getProcessName().takeIf { it.isNotBlank() } ?: context.packageName
            } else {
                try {
                    BufferedReader(FileReader("/proc/self/cmdline")).use { reader ->
                        reader.readLine()?.replace('\u0000', ' ')?.trim().takeUnless { it.isNullOrBlank() }
                            ?: context.packageName
                    }
                } catch (_: IOException) {
                    context.packageName
                }
            }
    }
}

private class OpenHouseConnectionBridgeServer(
    private val store: OpenHouseConnectionBridgeStore,
) : AutoCloseable {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false

    @Synchronized
    fun start(): Int {
        if (running) return port()
        val previousPort = store.identity().activePort
        val candidates = listOf(previousPort) + PORT_CANDIDATES
        val failures = mutableListOf<String>()
        val bound = candidates.distinct().firstNotNullOfOrNull { candidate -> bind(candidate, failures) }
            ?: throw IllegalStateException(
                "Unable to bind OpenHouse Bridge ports ${PORT_CANDIDATES.first}-${PORT_CANDIDATES.last}: " +
                    failures.joinToString("; "),
            )
        socket = bound
        running = true
        store.markListening(bound.localPort)
        thread(name = "openhouse-connection-bridge", isDaemon = true) {
            try {
                while (running) {
                    val client = try {
                        bound.accept()
                    } catch (error: IOException) {
                        if (running) Log.w("OpenHouseBridge", "Bridge accept failed", error)
                        break
                    }
                    runCatching { client.use(::handle) }
                }
            } finally {
                running = false
                socket = null
                store.clearListening(bound.localPort)
            }
        }
        return bound.localPort
    }

    fun isRunning(): Boolean = running && socket?.isClosed == false

    fun port(): Int = socket?.localPort ?: 0

    private fun bind(port: Int, failures: MutableList<String>): ServerSocket? {
        if (port !in 1..65535) return null
        return runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
        }.onFailure { error ->
            failures += "$port:${error.javaClass.simpleName}:${error.message.orEmpty()}"
        }.getOrNull()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val request = requestLine.split(' ')
        if (request.size < 2) return respond(client, 400, "Bad Request")
        val method = request[0]
        val path = request[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return respond(client, 400, "Bad Request")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength !in 0..MAX_BODY_BYTES) return respond(client, 413, "Payload Too Large")
        val body = if (contentLength == 0) "" else readBody(input, contentLength)
        route(client, method, path, headers, body)
    }

    private fun route(
        client: Socket,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ) {
        if (method == "GET" && path == "/v1/health") {
            return respondJson(
                client,
                200,
                JSONObject()
                    .put("ok", true)
                    .put("service", SERVICE_NAME)
                    .put("schema", 1)
                    .put("processName", store.identity().processName)
                    .put("port", socket?.localPort ?: 0),
            )
        }
        if (method == "GET" && path == "/v1/identity") {
            val identity = store.identity()
            return respondJson(
                client,
                200,
                JSONObject()
                    .put("service", SERVICE_NAME)
                    .put("schema", 1)
                    .put("packageName", identity.packageName)
                    .put("processName", identity.processName)
                    .put("bridgeId", identity.bridgeId)
                    .put("port", socket?.localPort ?: identity.activePort),
            )
        }

        val segments = path.split('/').filter(String::isNotEmpty)
        val isWrite = segments.size == 4 && segments[0] == "v1" && segments[1] == "write"
        val isPublicRead = segments.size == 3 && segments[0] == "v1" && segments[1] == "public"
        val isManage = segments.size == 4 && segments[0] == "v1" && segments[1] == "manage"
        if (isWrite && method in setOf("POST", "PUT")) {
            return writeValue(client, segments[2], segments[3], body)
        }
        if (isPublicRead && method == "GET") {
            return readValue(client, OpenHouseConnectionBridgeStore.PUBLIC_SCOPE, segments[2])
        }
        if (isManage && headers[MANAGEMENT_HEADER] == store.managementKey()) {
            return when (method) {
                "GET" -> readValue(client, segments[2], segments[3])
                "POST", "PUT" -> writeValue(client, segments[2], segments[3], body)
                "DELETE" -> deleteValue(client, segments[2], segments[3])
                else -> respond(client, 405, "Method Not Allowed")
            }
        }
        if (isManage) return respond(client, 403, "Forbidden")
        respond(client, 404, "Not Found")
    }

    private fun writeValue(client: Socket, scope: String, key: String, body: String) {
        if (body.isBlank()) return respond(client, 400, "JSON body is required")
        runCatching { store.write(scope, key, body) }
            .onSuccess { respond(client, 204, "No Content") }
            .onFailure { respond(client, 400, it.message ?: "Invalid bridge value") }
    }

    private fun readValue(client: Socket, scope: String, key: String) {
        val value = runCatching { store.read(scope, key) }.getOrNull()
            ?: return respond(client, 404, "Not Found")
        val jsonValue = runCatching { JSONTokener(value).nextValue() }.getOrElse { value }
        respondJson(client, 200, JSONObject().put("key", key).put("scope", scope).put("value", jsonValue))
    }

    private fun deleteValue(client: Socket, scope: String, key: String) {
        runCatching { store.delete(scope, key) }
            .onSuccess { respond(client, 204, "No Content") }
            .onFailure { respond(client, 400, it.message ?: "Invalid bridge key") }
    }

    private fun respondJson(client: Socket, code: Int, body: JSONObject) =
        respond(client, code, statusMessage(code), body.toString(), "application/json; charset=utf-8")

    private fun respond(client: Socket, code: Int, message: String) = respond(client, code, message, "")

    private fun respond(
        client: Socket,
        code: Int,
        message: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        client.getOutputStream().use { output ->
            output.write(
                ("HTTP/1.1 $code $message\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
            )
            output.write(bytes)
            output.flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= MAX_LINE_BYTES) {
            when (val next = input.read()) {
                -1 -> return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.US_ASCII.name())
                '\n'.code -> return bytes.toString(StandardCharsets.US_ASCII.name()).trimEnd('\r')
                else -> bytes.write(next)
            }
        }
        return null
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw IllegalArgumentException("Unexpected end of request body")
            offset += read
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    override fun close() {
        running = false
        socket?.close()
        socket = null
    }

    private companion object {
        const val SERVICE_NAME = "openhouse-host-bridge"
        const val MANAGEMENT_HEADER = "x-openhouse-management-key"
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        val PORT_CANDIDATES = 20771..20775

        fun statusMessage(code: Int): String =
            when (code) {
                200 -> "OK"
                204 -> "No Content"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                413 -> "Payload Too Large"
                else -> "Error"
            }
    }
}
