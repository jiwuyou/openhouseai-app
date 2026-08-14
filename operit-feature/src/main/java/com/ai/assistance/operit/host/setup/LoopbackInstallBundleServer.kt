package com.ai.assistance.operit.host.setup

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.json.JSONObject

/** Serves exactly one bundled installer TAR to the local Termux process. */
class LoopbackInstallBundleServer private constructor(
    private val context: Context,
    private val assetPath: String,
    var offer: Offer,
) : Closeable {
    data class Offer(
        val offerId: String,
        val apkVersionCode: Long,
        val resourceSetVersion: String,
        val resourceSetSequence: Long,
        val bundleSize: Long,
        val url: String,
    )

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    init {
        thread(name = "wuxianpi-install-bundle", isDaemon = true) {
            try {
                server.soTimeout = 10 * 60 * 1_000
                server.accept().use(::serve)
            } catch (_: Exception) {
                // The Termux command reports a failed download. There is no credential or state to log here.
            } finally {
                close()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val request = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        val requestLine = request.readLine().orEmpty()
        while (request.readLine()?.isNotEmpty() == true) Unit
        val expected = "GET ${offer.url.substringAfter("127.0.0.1:${server.localPort}")} HTTP/"
        val output = socket.getOutputStream()
        if (!requestLine.startsWith(expected)) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-tar\r\n" +
                    "Content-Length: ${offer.bundleSize}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        context.assets.open(assetPath).use { input -> input.copyTo(output) }
        output.flush()
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        fun start(
            context: Context,
            assetPath: String = "wuxianpi-install/openhouse-install-bundle.tar",
            indexAssetPath: String = "wuxianpi-install/bundle-index.json",
        ): LoopbackInstallBundleServer {
            val appContext = context.applicationContext
            val index = appContext.assets.open(indexAssetPath).bufferedReader().use { JSONObject(it.readText()) }
            require(index.optInt("schema") == 2 && index.optString("bundleAsset") == assetPath) {
                "Invalid APK install bundle index"
            }
            val bundleSize = index.getLong("bundleSize")
            val sequence = index.getLong("resourceSetSequence")
            require(bundleSize > 0L && sequence > 0L) { "Invalid APK install bundle index" }
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
            } else {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toLong()
            }
            val offerId = "${appContext.packageName}-$versionCode-$sequence"
            val provisional = Offer(
                offerId = offerId,
                apkVersionCode = versionCode,
                resourceSetVersion = index.optString("resourceSetVersion"),
                resourceSetSequence = sequence,
                bundleSize = bundleSize,
                url = "",
            )
            val holder = LoopbackInstallBundleServer(appContext, assetPath, provisional)
            val url = "http://127.0.0.1:${holder.server.localPort}/wuxianpi-install/$offerId"
            holder.offer = provisional.copy(url = url)
            return holder
        }
    }
}
