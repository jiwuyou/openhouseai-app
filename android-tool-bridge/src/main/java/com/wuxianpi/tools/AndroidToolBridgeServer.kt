package com.wuxianpi.tools

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only bridge used by the Pi extension. Execution failures are returned as ToolResult;
 * they never close the server or the Pi conversation.
 */
class AndroidToolBridgeServer(
    private val token: String,
    private val registry: AndroidToolRegistry,
    private val requestedPort: Int = 0,
) : AutoCloseable {
    init {
        require(token.length >= 24) { "Bridge token must contain at least 24 characters" }
    }

    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "openhouse-android-tool").apply { isDaemon = true }
    }
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        if (running.get()) return port
        val socket = ServerSocket(requestedPort, 32, InetAddress.getLoopbackAddress())
        server = socket
        running.set(true)
        workers.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    workers.execute { handle(client) }
                } catch (_: Exception) {
                    if (running.get()) continue else break
                }
            }
        }
        return socket.localPort
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3) return write(output, 400, errorJson("bad_request", "Malformed request"))
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            if (!isAuthorized(headers["authorization"])) {
                return write(output, 401, errorJson("unauthorized", "Invalid bridge token"))
            }
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            if (method == "GET" && path == "/v1/health") {
                return write(
                    output,
                    200,
                    JSONObject().put("ok", true).put("tools", registry.names()).toString(),
                )
            }
            if (method != "POST" || !path.startsWith("/v1/tools/")) {
                return write(output, 404, errorJson("not_found", "Unknown endpoint"))
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 1..MAX_BODY_BYTES) {
                return write(output, 413, errorJson("invalid_body", "Body size is invalid"))
            }
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) return
                offset += count
            }
            val json = try {
                JSONObject(String(body, StandardCharsets.UTF_8))
            } catch (_: Exception) {
                return write(output, 400, errorJson("invalid_json", "Body must be a JSON object"))
            }
            val name = path.removePrefix("/v1/tools/")
            val call = ToolCall(
                id = json.optString("id").ifBlank { "android-${System.nanoTime()}" },
                name = name,
                arguments = json.optJSONObject("arguments") ?: JSONObject(),
            )
            // Tool-level errors deliberately use HTTP 200 so the extension can return them to Pi.
            write(output, 200, registry.execute(call).toJson().toString())
        }
    }

    private fun isAuthorized(header: String?): Boolean {
        val provided = header?.removePrefix("Bearer ") ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        if (bytes.size > MAX_HEADER_LINE_BYTES) return null
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun write(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        output.write(
            "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\n".toByteArray(),
        )
        output.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun errorJson(code: String, message: String): String = ToolResult.failure(
        callId = "",
        code = code,
        message = message,
    ).toJson().toString()

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        workers.shutdownNow()
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 8192
    }
}
