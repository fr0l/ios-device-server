package com.badoo.automation.deviceserver.controllers

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestTest {
    private fun speedTestApplication(speedTest: SpeedTest = SpeedTest(), test: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            routing { route("status") { speedTest(speedTest) } }
            test()
        }

    @Test
    fun downloadStreamsTheRequestedNumberOfBytes() = speedTestApplication {
        val response = client.get("/status/speedtest?bytes=200000")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(200_000, response.readRawBytes().size)
        assertEquals(ContentType.Application.OctetStream, response.contentType()?.withoutParameters())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun downloadSizeDefaultsToEightMebibytesAndAllowsZeroToThirtyTwo() {
        // The in-memory test engine moves a few MB/s, so the full-size transfers are checked here, not over HTTP
        val speedTest = SpeedTest()
        assertEquals(8_388_608L, speedTest.requestedBytes(null))
        assertEquals(33_554_432L, speedTest.requestedBytes("33554432"))
        assertEquals(0L, speedTest.requestedBytes("0"))
        assertEquals(null, speedTest.requestedBytes("33554433"))
        assertEquals(null, speedTest.requestedBytes("-1"))
        assertEquals(null, speedTest.requestedBytes("8MB"))
    }

    @Test
    fun downloadStreamsSeveralChunksAndNothingForZero() = speedTestApplication {
        assertEquals(1_000_000, client.get("/status/speedtest?bytes=1000000").readRawBytes().size)
        assertEquals(0, client.get("/status/speedtest?bytes=0").readRawBytes().size)
    }

    @Test
    fun downloadRejectsTooManyNegativeOrNonNumericBytes() = speedTestApplication {
        listOf("${SpeedTest.MAX_BYTES + 1}", "-1", "abc", "1.5").forEach { bytes ->
            assertEquals(bytes, HttpStatusCode.BadRequest, client.get("/status/speedtest?bytes=$bytes").status)
        }
    }

    @Test
    fun uploadCountsTheReceivedBytes() = speedTestApplication {
        val response = client.post("/status/speedtest") { setBody(ByteArray(123_456)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals(true, Regex("""\{"bytes": 123456, "millis": \d+}""").matches(response.bodyAsText()))
    }

    @Test
    fun uploadRejectsBodiesOverTheLimitWithOrWithoutContentLength() = speedTestApplication {
        val declared = client.post("/status/speedtest") { setBody(ByteArray(SpeedTest.MAX_BYTES.toInt() + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)

        // Chunked: no Content-Length, so the limit is enforced while reading
        val chunked = client.post("/status/speedtest") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    val chunk = ByteArray(1024 * 1024)
                    repeat(33) { channel.writeFully(chunk) }
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
    }

    @Test
    fun aThirdConcurrentSpeedTestGetsTooManyRequests() {
        val speedTest = SpeedTest(maxConcurrent = 2)
        speedTestApplication(speedTest) { coroutineScope {
            val bodies = List(2) { ByteChannel() }
            val uploads = bodies.map { body -> async { client.post("/status/speedtest") { setBody(body) } } }
            bodies.forEach { it.writeFully(ByteArray(1000)); it.flush() }
            withTimeout(5_000) { while (speedTest.availablePermits > 0) delay(10) }

            assertEquals(HttpStatusCode.TooManyRequests, client.get("/status/speedtest?bytes=10").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.post("/status/speedtest") { setBody(ByteArray(10)) }.status)

            bodies.forEach { it.flushAndClose() }
            uploads.forEach { upload ->
                val response: HttpResponse = upload.await()
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(true, response.bodyAsText().startsWith("""{"bytes": 1000,"""))
            }
            assertEquals(2, speedTest.availablePermits)
            assertEquals(10, client.get("/status/speedtest?bytes=10").readRawBytes().size)
        } }
    }
}
