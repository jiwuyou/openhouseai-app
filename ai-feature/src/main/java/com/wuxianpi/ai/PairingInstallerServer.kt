package com.wuxianpi.ai

import android.content.Context
import org.json.JSONObject
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

/** One-shot loopback installer used only while the pairing screen is visible. */
class PairingInstallerServer(
    context: Context,
    private val onPaired: (GatewayCredentials) -> Unit,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val oneTimeToken = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val running = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "wuxianpi-installer").apply { isDaemon = true }
    }
    private var socket: ServerSocket? = null

    val port: Int get() = socket?.localPort ?: 0
    val command: String get() = "curl -fsSL http://127.0.0.1:$port/i/$oneTimeToken | bash"

    @Synchronized
    fun start(): Int {
        if (running.get()) return port
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
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
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
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
                        val bytes = runCatching { assets.open("openhouse-runtime/$asset").use { it.readBytes() } }
                            .getOrNull()
                        if (bytes == null) respond(output, 404, "text/plain", "Runtime payload not bundled")
                        else respond(output, 200, "application/octet-stream", bytes)
                    }
                }
                method == "POST" && path == "/paired/$oneTimeToken" && !consumed.get() -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length !in 2..8192) return respond(output, 400, "text/plain", "Invalid body")
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        if (count < 0) return
                        offset += count
                    }
                    val json = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }.getOrNull()
                        ?: return respond(output, 400, "text/plain", "Invalid JSON")
                    val gatewayPort = json.optInt("port")
                    val gatewayToken = json.optString("token")
                    if (gatewayPort !in 1..65535 || gatewayToken.length < 24) {
                        return respond(output, 400, "text/plain", "Invalid gateway credentials")
                    }
                    if (!consumed.compareAndSet(false, true)) {
                        return respond(output, 409, "text/plain", "Already paired")
                    }
                    onPaired(
                        GatewayCredentials(
                            adminUrl = "http://127.0.0.1:$gatewayPort/",
                            token = gatewayToken,
                            clientId = json.optString("clientId").ifBlank { "wuxianpi-${System.nanoTime()}" },
                        ),
                    )
                    respond(output, 200, "application/json", "{\"ok\":true}")
                }
                else -> respond(output, 404, "text/plain", "Not found")
            }
        }
    }

    private fun installerScript(): String = """#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
BASE='http://127.0.0.1:$port'
PAIR='$oneTimeToken'
ROOT="${'$'}HOME/.local/share/openhouseai"
BIN="${'$'}HOME/.local/bin"
mkdir -p "${'$'}ROOT" "${'$'}BIN" "${'$'}HOME/.config/openhouseai"
${arm64InstallerGuard()}
TMP="${'$'}(mktemp -d)"
trap 'rm -rf "${'$'}TMP"' EXIT
curl -fsSL "${'$'}BASE/payload/${'$'}PAIR/$ARM64_RUNTIME_ASSET" -o "${'$'}TMP/runtime.tar.gz"
tar -xzf "${'$'}TMP/runtime.tar.gz" -C "${'$'}ROOT"
"${'$'}ROOT/install.sh" "${'$'}ROOT" "${'$'}BIN"
TOKEN_FILE="${'$'}HOME/.config/openhouseai/runtime.token"
if [ ! -s "${'$'}TOKEN_FILE" ]; then
  (umask 077; head -c 32 /dev/urandom | base64 | tr -d '\n' > "${'$'}TOKEN_FILE")
fi
PORT="${'$'}(shuf -i 18000-28000 -n 1)"
nohup "${'$'}BIN/openhouse-pi-runtime" --listen "127.0.0.1:${'$'}PORT" --token-file "${'$'}TOKEN_FILE" >"${'$'}ROOT/runtime.log" 2>&1 &
for _ in ${'$'}(seq 1 50); do
  TOKEN="${'$'}(cat "${'$'}TOKEN_FILE")"
  if curl -fsS -H "Authorization: Bearer ${'$'}TOKEN" "http://127.0.0.1:${'$'}PORT/admin/v1/health" >/dev/null 2>&1; then
    curl -fsS -X POST -H 'Content-Type: application/json' \
      --data "{\"port\":${'$'}PORT,\"token\":\"${'$'}TOKEN\"}" \
      "${'$'}BASE/paired/${'$'}PAIR"
    echo
    echo 'WuxianPi runtime installed and paired.'
    exit 0
  fi
  sleep 0.2
done
echo "Runtime failed to start. See ${'$'}ROOT/runtime.log" >&2
exit 1
"""

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
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        executor.shutdownNow()
    }
}

internal const val ARM64_RUNTIME_ASSET = "runtime-aarch64.tgz"

internal fun arm64InstallerGuard(): String = """case "${'$'}(uname -m)" in
  aarch64|arm64) ;;
  *) echo '当前版本仅支持 ARM64' >&2; exit 2 ;;
esac"""
