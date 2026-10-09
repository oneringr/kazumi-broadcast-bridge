package io.github.kazumibridge.core

import java.io.File
import java.io.RandomAccessFile

enum class CastMode { COMPLETE, STREAM }
data class ProbeResult(val format: String, val videoCodec: String, val audioCodec: String?, val pixelFormat: String, val channels: Int, val duration: Double?)
data class ConversionPlan(val passthrough: Boolean, val copyVideo: Boolean, val copyAudio: Boolean, val stripAdts: Boolean = false)

object MediaPolicy {
    /** Chapter/data-track durations must not hide truncated audio or video. */
    fun completedDuration(expectedVideo: Double?, actualVideo: Double?, expectedAudio: Double? = null, actualAudio: Double? = null): Double {
        fun validate(track: String, expected: Double?, actual: Double?) {
            require(actual != null && actual.isFinite() && actual > 0) { "生成的 MP4 缺少有效的${track}时长。" }
            if (expected != null && expected.isFinite() && expected > 0) {
                val tolerance = maxOf(2.0, expected * 0.001)
                require(actual >= expected - tolerance) {
                    "${track}转换提前结束：来源约 ${expected.toInt()} 秒，生成约 ${actual.toInt()} 秒。请重试或更换来源。"
                }
            }
        }
        validate("视频", expectedVideo, actualVideo)
        if (expectedAudio != null) validate("音频", expectedAudio, actualAudio)
        return maxOf(actualVideo!!, actualAudio?.takeIf { it.isFinite() && it > 0 } ?: 0.0)
    }

    fun networkOptions(headers: Map<String, String>, hls: Boolean = false): List<String> = buildList {
        addAll(listOf("-rw_timeout", "15000000", "-user_agent", headers["User-Agent"] ?: "Kazumi/2.3.8",
            "-protocol_whitelist", "http,https,tcp,tls,crypto,data,httpproxy"))
        // Resume seekable HTTP resources at the failed offset; ordinary EOF still terminates.
        addAll(listOf("-reconnect", "1", "-reconnect_on_network_error", "1", "-reconnect_on_http_error", "429,500,502,503,504",
            "-reconnect_delay_max", "4", "-reconnect_max_retries", "3", "-reconnect_delay_total_max", "10"))
        if (hls) addAll(listOf("-allowed_extensions", "ALL"))
        val extra = headers.filterKeys { it != "User-Agent" }.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        if (extra.isNotEmpty()) addAll(listOf("-headers", extra))
    }

    fun plan(probe: ProbeResult, prefix: ByteArray, forceH264: Boolean): ConversionPlan {
        require(probe.videoCodec.isNotEmpty()) { "No video track found" }
        val videoCompatible = probe.videoCodec in (if (forceH264) setOf("h264") else setOf("h264", "hevc")) &&
            probe.pixelFormat in setOf("yuv420p", "nv12", "")
        val audioCompatible = probe.audioCodec == null || (probe.audioCodec == "aac" && probe.channels <= 2)
        val mp4 = isMp4(prefix) && probe.format != "hls" && probe.format.split(',').any { it == "mp4" || it == "mov" }
        return ConversionPlan(mp4 && videoCompatible && audioCompatible, videoCompatible, audioCompatible,
            probe.audioCodec == "aac" && audioCompatible && probe.format.split(',').any { it in setOf("hls", "mpegts", "aac") })
    }

    fun isHls(bytes: ByteArray): Boolean = bytes.take(1024).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    fun isMp4(bytes: ByteArray): Boolean {
        var offset = 0
        repeat(16) {
            if (offset + 12 > bytes.size) return false
            val size = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255) }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "ftyp") return String(bytes, offset + 8, 4, Charsets.US_ASCII) != "qt  "
            if (size < 8 || size > bytes.size - offset) return false
            offset += size.toInt()
        }
        return false
    }

    fun arguments(input: String, output: String, plan: ConversionPlan, mode: CastMode, inputOptions: List<String> = emptyList(), logLevel: String = "error", moovReserveBytes: Int? = null): List<String> = buildList {
        require(logLevel in setOf("error", "warning"))
        addAll(listOf("-hide_banner", "-loglevel", logLevel, "-y"))
        addAll(inputOptions)
        addAll(listOf("-i", input, "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn"))
        if (plan.copyVideo) addAll(listOf("-c:v", "copy")) else addAll(listOf(
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "22", "-pix_fmt", "yuv420p", "-profile:v", "main",
            "-vf", "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2", "-r", "30"))
        if (plan.copyAudio) addAll(listOf("-c:a", "copy")) else addAll(listOf("-c:a", "aac", "-b:a", "160k", "-ac", "2", "-ar", "48000"))
        if (plan.copyAudio && plan.stripAdts) addAll(listOf("-bsf:a", "aac_adtstoasc"))
        if (mode == CastMode.COMPLETE) {
            if (moovReserveBytes != null) {
                require(moovReserveBytes > 0) { "MP4 索引预留空间必须大于零。" }
                addAll(listOf("-moov_size", moovReserveBytes.toString()))
            } else addAll(listOf("-movflags", "+faststart"))
        } else addAll(listOf(
            "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-frag_duration", "1000000", "-flush_packets", "1"))
        addAll(listOf("-f", "mp4", output))
    }

    fun fragmentedReady(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                fragmentedReady(input.length()) { offset, bytes ->
                    input.seek(offset)
                    input.read(bytes)
                }
            }
        }.getOrDefault(false)
    }

    /** Reads box headers only, so RAM output never needs a duplicate of the full media. */
    fun fragmentedReady(length: Long, read: (offset: Long, data: ByteArray) -> Int): Boolean = runCatching {
        fun exact(offset: Long, count: Int): ByteArray? {
            val bytes = ByteArray(count)
            var done = 0
            while (done < count) {
                val target = if (done == 0) bytes else ByteArray(count - done)
                val received = read(offset + done, target)
                if (received <= 0 || received > target.size) return null
                if (target !== bytes) target.copyInto(bytes, done, 0, received)
                done += received
            }
            return bytes
        }
        fun unsigned(bytes: ByteArray, offset: Int, count: Int): Long =
            (offset until offset + count).fold(0L) { value, index -> (value shl 8) or (bytes[index].toLong() and 255) }
        if (length < 8) return@runCatching false
        var offset = 0L
        var ftyp = false
        var moov = false
        var moof = false
        while (offset <= length - 8) {
            val bytes = exact(offset, 8) ?: break
            var size = unsigned(bytes, 0, 4)
            val type = String(bytes, 4, 4, Charsets.US_ASCII)
            var header = 8L
            if (size == 1L) {
                if (offset > length - 16) break
                size = unsigned(exact(offset + 8, 8) ?: break, 0, 8)
                header = 16
            }
            if (size < header || size > length - offset) break
            when (type) {
                "ftyp" -> ftyp = true
                "moov" -> moov = true
                "moof" -> moof = true
                "mdat" -> if (ftyp && moov && moof) return@runCatching true
            }
            offset += size
        }
        false
    }.getOrDefault(false)
}
