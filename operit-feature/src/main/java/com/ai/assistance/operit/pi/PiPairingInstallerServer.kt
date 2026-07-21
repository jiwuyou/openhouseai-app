package com.ai.assistance.operit.pi

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.ai.assistance.operit.host.OperitHostProvider
import org.json.JSONObject

/** One-shot loopback installer used by a host while its Pi runtime is being paired. */
class PiPairingInstallerServer(
    context: Context,
    private val onPaired: (PiServiceCredentials) -> Unit,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val oneTimeToken = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val running = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "operit-pi-pairing").apply { isDaemon = true }
    }
    private var socket: ServerSocket? = null

    val port: Int get() = socket?.localPort ?: 0
    val command: String get() = pairingScript()

    @Synchronized
    fun start(): Int {
        if (running.get()) return port
        val server = ServerSocket(0, 8, InetAddress.getByName(PAIRING_INSTALLER_HOST))
        socket = server
        running.set(true)
        executor.execute {
            while (running.get()) {
                try {
                    val client = server.accept()
                    executor.execute { handle(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        return server.localPort
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val first = readLine(input)?.split(' ') ?: return
            if (first.size != 3) return respond(output, 400, "text/plain", "Bad request")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isBlank()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] =
                    line.substring(colon + 1).trim()
            }
            val method = first[0]
            val path = first[1].substringBefore('?')
            when {
                method == "GET" && path == "/i/$oneTimeToken" && !consumed.get() ->
                    respond(output, 200, "text/x-shellscript", installerScript())
                method == "GET" && path.startsWith("/payload/$oneTimeToken/") && !consumed.get() -> {
                    val asset = path.removePrefix("/payload/$oneTimeToken/")
                    if (!asset.matches(Regex("[A-Za-z0-9._-]+"))) {
                        respond(output, 400, "text/plain", "Invalid asset")
                    } else {
                        val bytes = runCatching {
                            assets.open("openhouse-runtime/$asset").use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null) {
                            respond(output, 404, "text/plain", "Runtime payload not bundled")
                        } else {
                            respond(output, 200, "application/octet-stream", bytes)
                        }
                    }
                }
                method == "POST" && path == "/paired/$oneTimeToken" && !consumed.get() -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length !in 2..8192) {
                        return respond(output, 400, "text/plain", "Invalid body")
                    }
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        if (count < 0) return
                        offset += count
                    }
                    val json = runCatching {
                        JSONObject(String(body, StandardCharsets.UTF_8))
                    }.getOrNull() ?: return respond(output, 400, "text/plain", "Invalid JSON")
                    val servicePort = json.optInt("port")
                    if (servicePort !in 1..65535) {
                        return respond(output, 400, "text/plain", "Invalid service port")
                    }
                    if (!consumed.compareAndSet(false, true)) {
                        return respond(output, 409, "text/plain", "Already paired")
                    }
                    onPaired(
                        PiServiceCredentials(
                            serviceUrl = "http://127.0.0.1:$servicePort/",
                            clientId = json.optString("clientId")
                                .ifBlank { "operit-${System.nanoTime()}" },
                        ),
                    )
                    respond(output, 200, "application/json", "{\"ok\":true}")
                }
                else -> respond(output, 404, "text/plain", "Not found")
            }
        }
    }

    private fun installerScript(): String = pairingScript()

    private fun pairingScript(): String {
        val baseUrl = "http://127.0.0.1:$port"
        return OperitHostProvider.currentOperationsOrNull()
            ?.pairingInstallerScript(baseUrl, oneTimeToken)
            ?: "#!/bin/sh\necho 'Pi host pairing is unsupported' >&2\nexit 78\n"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val data = ArrayList<Byte>()
        while (data.size < 8192) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) data.add(value.toByte())
        }
        return data.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun respond(output: BufferedOutputStream, code: Int, type: String, body: String) =
        respond(output, code, type, body.toByteArray(StandardCharsets.UTF_8))

    private fun respond(output: BufferedOutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        output.write(
            "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(body)
        output.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        executor.shutdownNow()
    }

    private companion object {
        const val PAIRING_INSTALLER_HOST = "127.0.0.1"
    }
}

const val OPERIT_PI_RUNTIME_PORT = 8765
const val OPERIT_PI_RUNTIME_URL = "http://127.0.0.1:8765/"
const val OPERIT_ADVANCED_UI_URL = "http://127.0.0.1:25808/"
const val OPERIT_BUILTIN_WEB_UI_URL = OPERIT_PI_RUNTIME_URL
