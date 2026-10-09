package io.github.kazumibridge.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP interruptions exercise resume and replacement; storage remains caller-owned. */
class CachedMp4DownloadTest {
    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var source: String
    private val data = ByteArray(160 * 1024) { ((it * 71) % 251).toByte() }
    private val firstLength = 12345
    private val requests = AtomicInteger()
    private val failure = AtomicReference<Throwable?>()
    private val fixtureHeaders = mapOf("Cookie" to "fixture=yes", "Referer" to "https://fixture.invalid/player/",
        "User-Agent" to "KazumiBridge-MP4-fixture")
    private var handler: (HttpExchange, Int) -> Unit = { exchange, _ -> complete(exchange) }

    @Before fun start() {
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                assertEquals("identity", exchange.requestHeaders.getFirst("Accept-Encoding"))
                fixtureHeaders.forEach { (name, value) -> assertEquals(value, exchange.requestHeaders.getFirst(name)) }
                assertEquals("key=fixture%2Bsignature", exchange.requestURI.rawQuery)
                handler(exchange, requests.incrementAndGet())
            } catch (error: Throwable) {
                if (error !is IOException) failure.compareAndSet(null, error)
            } finally {
                exchange.close()
            }
        }
        server.start()
        source = "http://127.0.0.1:${server.address.port}/video.mp4?key=fixture%2Bsignature"
    }

    @After fun stop() {
        server.stop(0)
        executor.shutdownNow()
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
    }

    private fun complete(exchange: HttpExchange, status: Int = 200, from: Int = 0, etag: String = "\"stable\"") {
        exchange.responseHeaders.set("ETag", etag)
        if (status == 206) exchange.responseHeaders.set("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
        exchange.sendResponseHeaders(status, (data.size - from).toLong())
        exchange.responseBody.write(data, from, data.size - from)
    }

    private fun interrupted(exchange: HttpExchange) {
        exchange.responseHeaders.set("ETag", "\"stable\"")
        exchange.sendResponseHeaders(200, data.size.toLong())
        exchange.responseBody.write(data, 0, firstLength)
        exchange.responseBody.flush()
        // Closing before Content-Length is met makes the client observe a genuine short EOF.
        exchange.close()
    }

    private fun download(storage: ByteArrayOutputStream, maxBytes: Long = data.size.toLong() + 1,
        resets: AtomicInteger = AtomicInteger(), write: (Long, ByteArray, Int) -> Unit = { offset, buffer, count ->
            assertEquals(storage.size().toLong(), offset)
            storage.write(buffer, 0, count)
        }): CachedMp4Download.Result = CachedMp4Download.download(source, fixtureHeaders, maxBytes,
        reset = { storage.reset(); resets.incrementAndGet() }, write = write)

    @Test fun wholeSourceDownloadKeepsAuthenticationAndSignedQuery() {
        val storage = ByteArrayOutputStream()
        val result = download(storage)
        assertEquals(data.size.toLong(), result.bytes)
        assertArrayEquals(data, storage.toByteArray())
        assertEquals(1, requests.get())
    }

    @Test fun interruptedBodyResumesWithRangeAndStrongValidator() {
        handler = { exchange, request ->
            if (request == 1) {
                assertNull(exchange.requestHeaders.getFirst("Range"))
                interrupted(exchange)
            } else {
                assertEquals("bytes=$firstLength-", exchange.requestHeaders.getFirst("Range"))
                assertEquals("\"stable\"", exchange.requestHeaders.getFirst("If-Range"))
                complete(exchange, 206, firstLength)
            }
        }
        val storage = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), download(storage).bytes)
        assertArrayEquals(data, storage.toByteArray())
        assertEquals(2, requests.get())
    }

    @Test fun ignoredRangeReplacesPartialCopyInsteadOfAppendingFullFile() {
        handler = { exchange, request ->
            if (request == 1) interrupted(exchange) else {
                assertEquals("bytes=$firstLength-", exchange.requestHeaders.getFirst("Range"))
                complete(exchange)
            }
        }
        val storage = ByteArrayOutputStream()
        val resets = AtomicInteger()
        download(storage, resets = resets)
        assertEquals(2, resets.get())
        assertArrayEquals(data, storage.toByteArray())
        assertEquals(2, requests.get())
    }

    @Test fun lastModifiedIsUsedWhenNoStrongEtagIsAvailable() {
        val modified = "Fri, 09 Oct 2026 07:00:00 GMT"
        handler = { exchange, request ->
            exchange.responseHeaders.set("Last-Modified", modified)
            exchange.responseHeaders.set("ETag", "W/\"weak\"")
            if (request == 1) {
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.write(data, 0, firstLength)
                exchange.responseBody.flush()
            } else {
                assertEquals(modified, exchange.requestHeaders.getFirst("If-Range"))
                assertEquals("bytes=$firstLength-", exchange.requestHeaders.getFirst("Range"))
                exchange.responseHeaders.set("Content-Range", "bytes $firstLength-${data.size - 1}/${data.size}")
                exchange.sendResponseHeaders(206, (data.size - firstLength).toLong())
                exchange.responseBody.write(data, firstLength, data.size - firstLength)
            }
        }
        val storage = ByteArrayOutputStream()
        download(storage)
        assertArrayEquals(data, storage.toByteArray())
        assertEquals(2, requests.get())
    }

    @Test fun changedValidatorRejectsNewSourceWithoutMixingItIntoTheCache() {
        handler = { exchange, request ->
            if (request == 1) interrupted(exchange) else complete(exchange, 206, firstLength, "\"changed\"")
        }
        val storage = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) { download(storage) }
        assertTrue(error.message.orEmpty().contains("来源已变化"))
        assertEquals(firstLength, storage.size())
        assertEquals(2, requests.get())
    }

    @Test fun mismatchedResumeOffsetIsRejected() {
        handler = { exchange, request ->
            if (request == 1) interrupted(exchange) else complete(exchange, 206, firstLength + 1)
        }
        val storage = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) { download(storage) }
        assertTrue(error.message.orEmpty().contains("位置不匹配"))
        assertEquals(firstLength, storage.size())
        assertEquals(2, requests.get())
    }

    @Test fun forbiddenRequestIsNotRetried() {
        handler = { exchange, _ -> exchange.sendResponseHeaders(403, -1) }
        val error = assertThrows(IOException::class.java) { download(ByteArrayOutputStream()) }
        assertEquals("MP4 下载服务器返回 HTTP 403。", error.message)
        assertEquals(1, requests.get())
    }

    @Test fun temporaryServerFailureIsRetriedWithinTheBound() {
        handler = { exchange, request ->
            if (request == 1) {
                exchange.responseHeaders.set("Retry-After", "0")
                exchange.sendResponseHeaders(503, -1)
            } else complete(exchange)
        }
        val storage = ByteArrayOutputStream()
        download(storage)
        assertArrayEquals(data, storage.toByteArray())
        assertEquals(2, requests.get())
    }

    @Test fun declaredLengthAtThresholdStopsBeforeWriting() {
        val storage = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) { download(storage, maxBytes = data.size.toLong()) }
        assertTrue(error.message.orEmpty().contains("上限"))
        assertEquals(0, storage.size())
        assertEquals(1, requests.get())
    }

    @Test fun unknownLengthIsBoundedBeforeEveryStorageWrite() {
        handler = { exchange, _ ->
            exchange.sendResponseHeaders(200, 0) // Chunked HTTP: no declared total.
            exchange.responseBody.write(data)
        }
        val storage = ByteArrayOutputStream()
        val threshold = 64000L
        val error = assertThrows(IOException::class.java) { download(storage, maxBytes = threshold) }
        assertTrue(error.message.orEmpty().contains("上限"))
        assertTrue(storage.size() < threshold)
        assertEquals(1, requests.get())
    }

    @Test fun storageBudgetFailurePropagatesWithoutReconnecting() {
        val storageFailure = IOException("shared-memory-budget")
        val error = assertThrows(IOException::class.java) {
            download(ByteArrayOutputStream(), write = { _, _, _ -> throw storageFailure })
        }
        assertSame(storageFailure, error)
        assertEquals(1, requests.get())
    }

    @Test fun cancellationDisconnectsBlockedReadAndDoesNotReconnect() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceled = AtomicBoolean()
        val active = AtomicReference<HttpURLConnection?>()
        handler = { exchange, _ ->
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.write(data, 0, 1)
            exchange.responseBody.flush()
            reached.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val job = worker.submit<CachedMp4Download.Result> {
                CachedMp4Download.download(source, fixtureHeaders, data.size.toLong() + 1,
                    canceled = canceled::get, connectionChanged = active::set,
                    reset = {}, write = { _, _, _ -> })
            }
            assertTrue(reached.await(3, TimeUnit.SECONDS))
            canceled.set(true)
            active.get()?.disconnect()
            release.countDown()
            val error = assertThrows(ExecutionException::class.java) { job.get(3, TimeUnit.SECONDS) }
            assertTrue(error.cause is CancellationException)
            assertEquals(1, requests.get())
            assertNull(active.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
