package io.github.kazumibridge.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP exercises worker limits, cancellation and RAM serving without requiring FFmpeg. */
class HlsDownloadCacheTest {
    private lateinit var directory: File
    private lateinit var origin: HttpServer
    private lateinit var originWorkers: ExecutorService
    private lateinit var readers: ExecutorService
    private lateinit var base: String
    private val caches = mutableListOf<HlsDownloadCache>()
    private val snapshots = mutableListOf<HlsSnapshot>()
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val requests = AtomicInteger()
    private val rejectedHeaders = AtomicInteger()
    private val headers = mapOf("User-Agent" to "KazumiBridge-cache-fixture", "Cookie" to "cache=fixture")
    private val payload = ByteArray(81923) { (it % 251).toByte() }
    @Volatile private var respond: (HttpExchange) -> Unit = { exchange ->
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.write(payload)
    }

    @Before fun start() {
        directory = Files.createTempDirectory("kazumibridge-cache-test-").toFile()
        originWorkers = Executors.newCachedThreadPool()
        readers = Executors.newCachedThreadPool()
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
        origin.executor = originWorkers
        origin.createContext("/") { exchange ->
            requests.incrementAndGet()
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (headers.any { (name, value) -> exchange.requestHeaders.getFirst(name) != value }) {
                    rejectedHeaders.incrementAndGet()
                    exchange.sendResponseHeaders(403, -1)
                } else respond(exchange)
            } catch (_: IOException) {
                // Cancellation intentionally disconnects in-progress response bodies.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally { active.decrementAndGet(); exchange.close() }
        }
        origin.start()
        base = "http://127.0.0.1:${origin.address.port}"
    }

    @After fun stop() {
        snapshots.forEach(HlsSnapshot::close)
        caches.forEach(HlsDownloadCache::close)
        origin.stop(0)
        originWorkers.shutdownNow()
        readers.shutdownNow()
        directory.deleteRecursively()
    }

    @Test fun sixteenWorkersReachAndRespectSelectedLimit() = verifyWorkerLimit(16)

    @Test fun thirtyTwoWorkersReachAndRespectSelectedLimit() = verifyWorkerLimit(32)

    private fun verifyWorkerLimit(limit: Int) {
        val entered = CountDownLatch(limit)
        val release = CountDownLatch(1)
        val bytes = ByteArray(256) { 27 }
        respond = { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes, 0, 1)
            exchange.responseBody.flush()
            entered.countDown()
            check(release.await(6, TimeUnit.SECONDS))
            exchange.responseBody.write(bytes, 1, bytes.size - 1)
        }
        val cache = cache(parallelism = limit)
        val resources = (0 until limit * 2).map { cache.register("$base/segment-$it.ts") }
        cache.playlist(resources)
        val reading = readers.submit<HttpResponse> { serve(cache, resources.first()) }
        try {
            assertTrue("Every selected worker must open a real upstream body", entered.await(5, TimeUnit.SECONDS))
            assertEquals(limit, cache.stats.peakActive)
            assertEquals(limit, peak.get())
            release.countDown()
            assertArrayEquals(bytes, reading.get(5, TimeUnit.SECONDS).body)
            resources.forEach { assertArrayEquals(bytes, serve(cache, it).body) }
            assertEquals(limit, cache.stats.peakActive)
            assertTrue("The origin must not observe more than the selected worker count", peak.get() <= limit)
            assertEquals(resources.size, cache.stats.completed)
            assertEquals(0, rejectedHeaders.get())
            assertNull(cache.failure)
        } finally { release.countDown() }
    }

    @Test fun cancellationDuringConnectionAndOutputRemovalClosesAllReaders() {
        val entered = CountDownLatch(32)
        val completeHalf = CountDownLatch(1)
        val releaseRest = CountDownLatch(1)
        respond = { exchange ->
            val index = exchange.requestURI.path.substringAfterLast('-').substringBefore('.').toInt()
            exchange.sendResponseHeaders(200, 65536)
            exchange.responseBody.write(ByteArray(128) { 5 })
            exchange.responseBody.flush()
            entered.countDown()
            if (index % 2 == 1) {
                check(completeHalf.await(6, TimeUnit.SECONDS))
                exchange.responseBody.write(ByteArray(65536 - 128) { 8 })
            } else check(releaseRest.await(6, TimeUnit.SECONDS))
        }
        val cache = cache(parallelism = 32)
        val resources = (0 until 32).map { cache.register("$base/blocked-$it.ts") }
        cache.playlist(resources)
        val reading = readers.submit<Boolean> { runCatching { serve(cache, resources.first()) }.isFailure }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val completing = readers.submit { completeHalf.countDown() }
            // Multiple callers may stop one cast while completing workers remove output/connection handles.
            val closing = (0 until 8).map { readers.submit { cache.close() } }
            completing.get(3, TimeUnit.SECONDS)
            closing.forEach { it.get(4, TimeUnit.SECONDS) }
            assertTrue("The waiting local media reader must stop", reading.get(4, TimeUnit.SECONDS))
            assertEquals(0L, cache.bytes)
            assertFalse("Stopped downloads must leave no complete or partial resources", directory.walkTopDown().any { it.isFile })
        } finally { completeHalf.countDown(); releaseRest.countDown() }
    }

    @Test fun memoryResourcesServeHeadAndRangesWithoutCreatingDiskCache() {
        val budget = MemoryBudget(4L * MemoryMediaBuffer.CHUNK_SIZE + 1)
        val cache = cache(storage = CacheStorage.MEMORY, budget = budget)
        val resource = cache.register("$base/memory.ts")
        val response = serve(cache, resource)
        assertEquals(200, response.status)
        assertArrayEquals(payload, response.body)
        assertEquals(2L * MemoryMediaBuffer.CHUNK_SIZE, budget.usedBytes)
        val range = serve(cache, resource, range = "bytes=65530-65560")
        assertEquals(206, range.status)
        assertEquals("bytes 65530-65560/${payload.size}", range.headers["content-range"])
        assertArrayEquals(payload.copyOfRange(65530, 65561), range.body)
        val invalid = serve(cache, resource, range = "bytes=${payload.size}-")
        assertEquals(416, invalid.status)
        val head = ByteArrayOutputStream()
        cache.serve(resource, request("HEAD"), head)
        val text = head.toString(Charsets.US_ASCII)
        assertTrue(text.contains("content-length: ${payload.size}", ignoreCase = true))
        assertTrue("HEAD must not include media bytes", text.endsWith("\r\n\r\n"))
        assertEquals(1, requests.get())
        assertEquals(0, rejectedHeaders.get())
        assertFalse(File(directory, "resources").exists())
        cache.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun sharedMemoryBudgetIncludesTheFinalVideoAndStopsAtItsThreshold() {
        respond = { exchange -> exchange.sendResponseHeaders(200, 256); exchange.responseBody.write(ByteArray(256) { 3 }) }
        val budget = MemoryBudget(2L * MemoryMediaBuffer.CHUNK_SIZE + 1)
        val finalVideo = MemoryMediaBuffer(budget)
        finalVideo.write(0, ByteArray(16))
        val cache = cache(storage = CacheStorage.MEMORY, budget = budget)
        try {
            serve(cache, cache.register("$base/first.ts"), method = "HEAD")
            assertEquals(2L * MemoryMediaBuffer.CHUNK_SIZE, budget.usedBytes)
            assertThrows(IOException::class.java) { serve(cache, cache.register("$base/second.ts"), method = "HEAD") }
            assertNotNull(cache.failure)
            assertTrue(cache.failure.orEmpty().contains("内存缓存"))
            assertTrue(cache.failure.orEmpty().contains("阈值"))
            assertEquals(2, requests.get())
            assertFalse(File(directory, "resources").exists())
            cache.close()
            assertEquals("Closing HLS must preserve the separate final-video allocation", finalVideo.allocatedBytes, budget.usedBytes)
        } finally { cache.close(); finalVideo.close() }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun declaredResourceAtMemoryThresholdIsRejectedBeforeAllocation() {
        val budget = MemoryBudget(65536)
        val cache = cache(storage = CacheStorage.MEMORY, budget = budget)
        val error = assertThrows(IOException::class.java) { serve(cache, cache.register("$base/too-large.ts")) }
        assertTrue(error.message.orEmpty().contains("阈值"))
        assertEquals(0L, budget.usedBytes)
        assertEquals(0L, cache.bytes)
        assertEquals(1, requests.get())
        assertFalse(File(directory, "resources").exists())
    }

    @Test fun snapshotAccountsRetainedManifestsAndReleasesTheSharedBudget() {
        val budget = MemoryBudget(4L * MemoryMediaBuffer.CHUNK_SIZE + 1)
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nmemory.ts\n#EXT-X-ENDLIST\n".toByteArray()
        val snapshot = requireNotNull(HlsSnapshot.create("$base/index.m3u8", manifest, headers,
            HlsDownloadCache.Options(File(directory, "resources"), maxBytes = budget.limitBytes,
                storage = CacheStorage.MEMORY, memoryBudget = budget)))
        snapshots.add(snapshot)
        assertTrue("The rewritten playlist retained in RAM belongs to the shared budget", budget.usedBytes > manifest.size)
        assertFalse(File(directory, "resources").exists())
        snapshot.close()
        snapshot.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun streamManifestUsesExplicitSharedBudgetWithoutDownloadingResources() {
        val budget = MemoryBudget(4L * MemoryMediaBuffer.CHUNK_SIZE + 1)
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nmemory.ts\n#EXT-X-ENDLIST\n".toByteArray()
        val snapshot = requireNotNull(HlsSnapshot.create("$base/index.m3u8", manifest, headers,
            memoryBudget = budget) { false })
        snapshots.add(snapshot)
        assertTrue(budget.usedBytes > manifest.size)
        assertNull("STREAM retains the manifest but has no parallel segment cache", snapshot.downloadStats)
        assertEquals(0, requests.get())
        assertFalse(File(directory, "resources").exists())
        snapshot.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun childManifestReadFailurePreservesTheSharedMemoryThresholdReason() {
        val child = ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#" + "quota-fixture".repeat(128) +
            "\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n").toByteArray()
        respond = { exchange -> exchange.sendResponseHeaders(200, child.size.toLong()); exchange.responseBody.write(child) }
        val budget = MemoryBudget(256)
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8\n".toByteArray()
        val snapshot = requireNotNull(HlsSnapshot.create("$base/master.m3u8", master, headers, memoryBudget = budget))
        snapshots.add(snapshot)
        val playlist = URI(snapshot.url).toURL().openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val childUrl = playlist.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }
        val connection = URI(childUrl).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            assertEquals("An oversized retained child manifest must reject the read", 502, connection.responseCode)
            assertNotNull(budget.failure)
            assertEquals("The generic child-read IOException must not hide the threshold", budget.failure, snapshot.failure)
            assertTrue(snapshot.failure.orEmpty().contains("阈值"))
        } finally { connection.disconnect(); snapshot.close() }
        assertEquals(0L, budget.usedBytes)
        assertEquals(1, requests.get())
        assertEquals(0, rejectedHeaders.get())
    }

    private fun cache(parallelism: Int = 4, storage: CacheStorage = CacheStorage.DISK,
        budget: MemoryBudget? = null): HlsDownloadCache = HlsDownloadCache(
        HlsDownloadCache.Options(File(directory, "resources"), parallelism,
            maxBytes = 16L * 1024 * 1024, reserveFreeBytes = 0, storage = storage, memoryBudget = budget),
        headers, { false }).also { caches.add(it) }

    private fun request(method: String, range: String? = null) = HttpRequest(method, "/media",
        range?.let { mapOf("range" to it) } ?: emptyMap(), byteArrayOf())

    private fun serve(cache: HlsDownloadCache, resource: HlsDownloadCache.Resource,
        method: String = "GET", range: String? = null): HttpResponse {
        val output = ByteArrayOutputStream()
        cache.serve(resource, request(method, range), output)
        if (method == "HEAD") return HttpResponse(200, "OK")
        return HttpWire.readResponse(output.toByteArray().inputStream())
    }
}
