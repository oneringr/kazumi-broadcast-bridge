package io.github.kazumibridge.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Pure synthetic playlists: signed-query preservation and HTTP lifetime, without media downloads. */
class HlsSnapshotTest {
    private lateinit var upstream: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var base: String
    private val snapshots = mutableListOf<HlsSnapshot>()
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private val receivedQueries = ConcurrentHashMap<String, String>()
    private val fixtureHeaders = mapOf(
        "User-Agent" to "KazumiBridge-HLS-fixture",
        "Referer" to "https://fixture.invalid/player/",
        "Cookie" to "fixture=yes",
    )

    @Before fun startUpstream() {
        executor = Executors.newCachedThreadPool()
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = executor
        upstream.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                receivedQueries[path] = exchange.requestURI.rawQuery.orEmpty()
                val count = requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                if (fixtureHeaders.any { (name, value) -> exchange.requestHeaders.getFirst(name) != value }) {
                    exchange.sendResponseHeaders(403, -1)
                    return@createContext
                }
                val playlist = when (path) {
                    "/catalog/root.m3u8" -> largeVod()
                    "/catalog/master.m3u8" -> master()
                    "/catalog/video/index.m3u8", "/audio/index.m3u8", "/catalog/iframe/index.m3u8" -> childVod()
                    "/catalog/live-master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nlive/index.m3u8?token=l%2Bv\n"
                    "/catalog/live/index.m3u8" -> live(count)
                    else -> {
                        exchange.sendResponseHeaders(404, -1)
                        return@createContext
                    }
                }
                val bytes = playlist.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                // A normal GET returns the whole playlist, including content after the probe prefix.
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } finally {
                exchange.close()
            }
        }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
    }

    @After fun closeServers() {
        snapshots.forEach(HlsSnapshot::close)
        upstream.stop(0)
        executor.shutdownNow()
    }

    @Test fun fullVodRootIsFetchedOnceAndReusedByProbeAndConversion() {
        val source = "$base/catalog/root.m3u8?key=fixture%2Bsignature&timestamp=1800000060"
        val content = SourceProbe.readContent(source, fixtureHeaders)
        assertTrue(MediaPolicy.isHls(content.prefix))
        assertArrayEquals(largeVod().toByteArray(Charsets.UTF_8), content.manifest)
        assertEquals(source, content.resolvedSource)
        assertTrue("The fixture must exceed the 64 KiB probe prefix", largeVod().toByteArray().size > 65536)
        val snapshot = requireNotNull(HlsSnapshot.create(content.resolvedSource, requireNotNull(content.manifest), fixtureHeaders))
        snapshots.add(snapshot)

        val probePlaylist = get(snapshot.url)
        val conversionPlaylist = get(snapshot.url)

        assertEquals(probePlaylist, conversionPlaylist)
        assertEquals("Reusing the snapshot must not reopen the signed root", 1, count("/catalog/root.m3u8"))
        assertTrue("Content after the prefix must survive", conversionPlaylist.contains("last-segment.ts?token=last%2Bsegment"))
    }

    @Test fun relativeMediaKeyAndMapUrisKeepTheirOriginalEncodedQueries() {
        val source = "$base/catalog/root.m3u8?key=fixture%2Bsignature"
        val snapshot = create(source, largeVod())
        val playlist = get(snapshot.url)

        assertTrue(playlist.contains("$base/media/segment.ts?token=segment%2Btoken&part=1"))
        assertTrue(playlist.contains("URI=\"$base/keys/key.bin?key=a%2Bb&n=1\""))
        assertTrue(playlist.contains("URI=\"$base/init/header.mp4?token=map%2Btoken\""))
        assertFalse("Encoded plus signs must not become double encoded", playlist.contains("%252B"))
        assertFalse("Signed query values must not be form-decoded", playlist.contains("token=segment+token"))
        val queryOnly = get(create(source, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"?key=q%2Bk\"\n#EXTINF:2,\n?part=s%2Bp\n#EXT-X-ENDLIST\n").url)
        assertTrue(queryOnly.contains("URI=\"$base/catalog/root.m3u8?key=q%2Bk\""))
        assertTrue(queryOnly.contains("$base/catalog/root.m3u8?part=s%2Bp"))
    }

    @Test fun masterVariantAudioAndIframePlaylistsAreLazilyCachedWithSourceHeaders() {
        val snapshot = create("$base/catalog/master.m3u8?key=master%2Btoken", master())
        val playlist = get(snapshot.url)
        val ordinary = playlist.lineSequence().map(String::trim).first { it.isNotEmpty() && !it.startsWith('#') }
        val attributes = Regex("URI=\"([^\"]+)\"").findAll(playlist).map { it.groupValues[1] }.toList()
        assertEquals(2, attributes.size)
        val endpoints = listOf(ordinary) + attributes
        val childPaths = listOf("/catalog/video/index.m3u8", "/audio/index.m3u8", "/catalog/iframe/index.m3u8")

        childPaths.forEach { assertEquals("Child playlists must be fetched on demand", 0, count(it)) }
        endpoints.forEach { endpoint ->
            val uri = URI(endpoint)
            assertEquals("http", uri.scheme)
            assertEquals("127.0.0.1", uri.host)
            assertEquals("Child playlists must be served by the same snapshot", URI(snapshot.url).port, uri.port)
            val first = get(endpoint)
            assertEquals(first, get(endpoint))
            assertTrue(first.contains("#EXT-X-ENDLIST"))
            assertTrue(first.contains("chunk.ts?token=child%2Btoken"))
            assertFalse(first.contains("%252B"))
        }
        childPaths.forEach { assertEquals("A VOD child must be fetched once with the required source headers", 1, count(it)) }
        assertEquals("sig=v%2B1", receivedQueries["/catalog/video/index.m3u8"])
        assertEquals("token=x%2By", receivedQueries["/audio/index.m3u8"])
        assertEquals("sig=i%2Bf", receivedQueries["/catalog/iframe/index.m3u8"])
    }

    @Test fun liveMediaPlaylistDoesNotCreateFrozenSnapshot() {
        assertNull(HlsSnapshot.create("$base/catalog/live/index.m3u8", live(1).toByteArray(Charsets.UTF_8), fixtureHeaders))
    }

    @Test fun liveChildOfMasterIsReloadedInsteadOfFrozen() {
        val root = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nlive/index.m3u8?token=l%2Bv\n"
        val snapshot = create("$base/catalog/live-master.m3u8?key=master%2Btoken", root)
        val endpoint = get(snapshot.url).lineSequence().map(String::trim).first { it.isNotEmpty() && !it.startsWith('#') }
        val first = get(endpoint)
        val second = get(endpoint)

        assertTrue(first.contains("segment-1.ts"))
        assertTrue(second.contains("segment-2.ts"))
        assertEquals(2, count("/catalog/live/index.m3u8"))
    }

    @Test fun closeStopsSnapshotHttpListener() {
        val snapshot = create("$base/catalog/root.m3u8", childVod())
        assertTrue(get(snapshot.url).contains("#EXT-X-ENDLIST"))
        snapshot.close()
        snapshots.remove(snapshot)
        assertThrows(IOException::class.java) { get(snapshot.url) }
    }

    private fun create(source: String, manifest: String): HlsSnapshot =
        requireNotNull(HlsSnapshot.create(source, manifest.toByteArray(Charsets.UTF_8), fixtureHeaders)).also { snapshots.add(it) }

    private fun count(path: String): Int = requests[path]?.get() ?: 0

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        try {
            assertEquals(200, connection.responseCode)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun largeVod(): String = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:2
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin?key=a%2Bb&n=1"
        #EXT-X-MAP:URI="../init/header.mp4?token=map%2Btoken"
        #EXTINF:2,
        ../media/segment.ts?token=segment%2Btoken&part=1
    """.trimIndent() + "\n" + "# synthetic fixture padding\n".repeat(3000) + "#EXTINF:2,\nlast-segment.ts?token=last%2Bsegment\n#EXT-X-ENDLIST\n"

    private fun master(): String = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="../audio/index.m3u8?token=x%2By"
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1000,URI="iframe/index.m3u8?sig=i%2Bf"
        #EXT-X-STREAM-INF:BANDWIDTH=300000,AUDIO="aud"
        video/index.m3u8?sig=v%2B1
    """.trimIndent() + "\n"

    private fun childVod(): String = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:2,\nchunk.ts?token=child%2Btoken\n#EXT-X-ENDLIST\n"

    private fun live(revision: Int): String = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$revision\n#EXTINF:2,\nsegment-$revision.ts?token=live%2Btoken\n"
}
