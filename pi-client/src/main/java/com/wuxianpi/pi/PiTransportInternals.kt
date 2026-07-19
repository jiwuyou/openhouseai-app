package com.wuxianpi.pi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A bounded single-consumer actor queue; overflow is an explicit reconnect/recovery signal. */
internal class OrderedQueue<T>(
    scope: CoroutineScope,
    capacity: Int = DEFAULT_CAPACITY,
    private val consume: suspend (T) -> Unit,
) : AutoCloseable {
    init {
        require(capacity > 0) { "Queue capacity must be positive" }
    }

    private val queue = Channel<T>(capacity)
    private val job = scope.launch {
        for (item in queue) consume(item)
    }

    fun offer(item: T): QueueOfferResult = if (queue.trySend(item).isSuccess) {
        QueueOfferResult.ACCEPTED
    } else {
        QueueOfferResult.OVERFLOW_REQUIRES_RECOVERY
    }

    override fun close() {
        queue.close()
        job.cancel()
    }

    companion object {
        const val DEFAULT_CAPACITY = 2048
    }
}

internal enum class QueueOfferResult {
    ACCEPTED,
    OVERFLOW_REQUIRES_RECOVERY,
}

internal class PromptGate {
    private val occupied = AtomicBoolean(false)

    fun tryBegin(connected: Boolean, isStreaming: Boolean): Boolean =
        connected && !isStreaming && occupied.compareAndSet(false, true)

    fun onAgentStart() {
        occupied.set(true)
    }

    fun onAgentEnd() {
        occupied.set(false)
    }

    fun onPromptRejected() {
        occupied.set(false)
    }

    fun onRecovered(isStreaming: Boolean) {
        occupied.set(isStreaming)
    }

    fun isOccupied(): Boolean = occupied.get()
}

internal class SocketGenerationGate {
    private val generation = AtomicLong(0)

    fun next(): Long = generation.incrementAndGet()
    fun invalidate(): Long = generation.incrementAndGet()
    fun isCurrent(candidate: Long): Boolean = candidate == generation.get()
}

internal data class RecoveryProgress(
    val matched: Boolean,
    val complete: Boolean,
    val failed: Boolean,
    val isStreaming: Boolean? = null,
)

/** Tracks the two native Pi recovery responses; it never sends or replays a prompt. */
internal class RecoveryTracker(
    val generation: Long,
    private val stateRequestId: String,
    private val messagesRequestId: String,
) {
    private var stateComplete = false
    private var messagesComplete = false
    private var failed = false

    fun accept(response: PiResponse): RecoveryProgress {
        val matched = response.id == stateRequestId || response.id == messagesRequestId
        if (!matched) return RecoveryProgress(false, complete = false, failed = false)
        if (!response.success) failed = true
        var streaming: Boolean? = null
        when (response.id) {
            stateRequestId -> {
                stateComplete = true
                streaming = (response.data as? JSONObject)?.optBoolean("isStreaming", false)
            }
            messagesRequestId -> messagesComplete = true
        }
        return RecoveryProgress(
            matched = true,
            complete = stateComplete && messagesComplete,
            failed = failed,
            isStreaming = streaming,
        )
    }
}

internal fun requireTrustedGateway(base: HttpUrl) {
    require(base.scheme == "http" || base.scheme == "https") { "Gateway must use HTTP(S)" }
    require(isLoopbackHost(base.host)) { "Gateway must be bound to loopback" }
    require(base.username.isEmpty() && base.password.isEmpty()) { "Gateway URL must not contain credentials" }
}

internal fun resolveTrustedWebSocketUrl(base: HttpUrl, wsPath: String): HttpUrl {
    requireTrustedGateway(base)
    val expectedWebSocketScheme = if (base.isHttps) "wss" else "ws"
    val candidate = when {
        wsPath.startsWith("ws://") || wsPath.startsWith("wss://") -> {
            val separator = wsPath.indexOf("://")
            val suppliedScheme = wsPath.substring(0, separator)
            require(suppliedScheme == expectedWebSocketScheme) {
                "WebSocket scheme does not match gateway transport"
            }
            // OkHttp's HttpUrl normalizes WebSocket URLs to HTTP(S); newWebSocket performs the upgrade.
            val normalizedScheme = if (suppliedScheme == "wss") "https" else "http"
            "$normalizedScheme${wsPath.substring(separator)}".toHttpUrl()
        }
        else -> {
            require(!wsPath.startsWith("//")) { "Protocol-relative WebSocket URLs are forbidden" }
            base.resolve(wsPath) ?: throw IllegalArgumentException("Invalid WebSocket path")
        }
    }
    require(candidate.scheme == base.scheme) { "WebSocket scheme does not match gateway transport" }
    require(candidate.host.equals(base.host, ignoreCase = true) && candidate.port == base.port) {
        "WebSocket must use the same origin as the gateway"
    }
    require(isLoopbackHost(candidate.host)) { "WebSocket must remain on loopback" }
    require(candidate.username.isEmpty() && candidate.password.isEmpty()) {
        "WebSocket URL must not contain credentials"
    }
    return candidate
}

private fun isLoopbackHost(host: String): Boolean =
    host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
