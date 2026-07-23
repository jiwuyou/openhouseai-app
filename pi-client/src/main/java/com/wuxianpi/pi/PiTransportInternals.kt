package com.wuxianpi.pi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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

internal object PiHttpTransport {
    val sharedClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::OkHttpClient)
}

internal class PromptGate {
    private val occupied = AtomicBoolean(false)

    fun tryBegin(connected: Boolean, isStreaming: Boolean): Boolean =
        connected && !isStreaming && occupied.compareAndSet(false, true)

    fun onAgentStart() {
        occupied.set(true)
    }

    fun onAgentSettled() {
        occupied.set(false)
    }

    fun onPromptRejected() {
        occupied.set(false)
    }

    fun onPromptCompletedWithoutAgent() {
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

internal fun requireLoopbackService(base: HttpUrl) {
    require(base.scheme == "http" || base.scheme == "https") { "Pi service must use HTTP(S)" }
    require(isLoopbackHost(base.host)) { "Pi service must be bound to loopback" }
    require(base.username.isEmpty() && base.password.isEmpty()) { "Pi service URL must not contain credentials" }
}

internal fun resolveServiceWebSocketUrl(base: HttpUrl): HttpUrl {
    requireLoopbackService(base)
    return base.newBuilder()
        .encodedPath("/v1/ws")
        .query(null)
        .fragment(null)
        .build()
}

internal fun resolveServiceHttpUrl(base: HttpUrl, absolutePath: String): HttpUrl {
    requireLoopbackService(base)
    require(absolutePath.startsWith('/')) { "Pi service HTTP path must be absolute" }
    return base.newBuilder()
        .encodedPath(absolutePath)
        .query(null)
        .fragment(null)
        .build()
}

private fun isLoopbackHost(host: String): Boolean =
    host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
