package com.badoo.automation.deviceserver.controllers

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom

/**
 * Network throughput probes on the server port, the only one a hub may reach: GET streams bytes to the
 * caller, POST counts the bytes it receives. At most [maxConcurrent] run at once, so tests on the Mac keep the link.
 */
class SpeedTest(maxConcurrent: Int = 2) {
    private val permits = Semaphore(maxConcurrent)

    internal val availablePermits: Int get() = permits.availablePermits()

    suspend fun download(call: ApplicationCall) {
        val bytes = requestedBytes(call.request.queryParameters["bytes"])
        if (bytes == null) {
            call.respondText("bytes must be a number from 0 to $MAX_BYTES", status = HttpStatusCode.BadRequest)
            return
        }
        limited(call) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondOutputStream(ContentType.Application.OctetStream, contentLength = bytes) {
                // Random so that no compressing proxy on the path shortens the transfer
                val chunk = ByteArray(CHUNK_BYTES).also { ThreadLocalRandom.current().nextBytes(it) }
                withContext(Dispatchers.IO) {
                    var left = bytes
                    while (left > 0) {
                        val size = minOf(left, chunk.size.toLong()).toInt()
                        write(chunk, 0, size)
                        left -= size
                    }
                }
            }
        }
    }

    suspend fun upload(call: ApplicationCall) {
        if ((call.request.contentLength() ?: 0) > MAX_BYTES) {
            call.respondText("body must be at most $MAX_BYTES bytes", status = HttpStatusCode.PayloadTooLarge)
            return
        }
        limited(call) {
            val started = System.nanoTime()
            val channel = call.receiveChannel()
            val received = withContext(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK_BYTES)
                var total = 0L
                while (total <= MAX_BYTES) {
                    val read = channel.readAvailable(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
            if (received > MAX_BYTES) {
                call.respondText("body must be at most $MAX_BYTES bytes", status = HttpStatusCode.PayloadTooLarge)
                return@limited
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            call.respondText("""{"bytes": $received, "millis": $millis}""", ContentType.Application.Json)
        }
    }

    /** @return the `bytes` query parameter, [DEFAULT_BYTES] without one, null when out of range or not a number */
    internal fun requestedBytes(parameter: String?): Long? {
        val bytes = if (parameter == null) DEFAULT_BYTES else parameter.toLongOrNull() ?: return null
        return bytes.takeIf { it in 0..MAX_BYTES }
    }

    private suspend fun limited(call: ApplicationCall, block: suspend () -> Unit) {
        if (!permits.tryAcquire()) {
            call.respondText("speed test already running, retry later", status = HttpStatusCode.TooManyRequests)
            return
        }
        try {
            block()
        } finally {
            permits.release()
        }
    }

    companion object {
        const val DEFAULT_BYTES = 8L * 1024 * 1024
        const val MAX_BYTES = 32L * 1024 * 1024
        private const val CHUNK_BYTES = 64 * 1024

        const val DESCRIPTION = "Network speed test (no auth, at most 2 at a time, else 429):\n" +
            "  GET  /status/speedtest?bytes=N  streams N random bytes, default $DEFAULT_BYTES, max $MAX_BYTES (else 400)\n" +
            "  POST /status/speedtest          reads the body (max $MAX_BYTES, else 413), answers {\"bytes\": N, \"millis\": M}"
    }
}

/** GET and POST `speedtest` under the current route. */
fun Route.speedTest(speedTest: SpeedTest) {
    get("speedtest") { speedTest.download(call) }
    post("speedtest") { speedTest.upload(call) }
}
