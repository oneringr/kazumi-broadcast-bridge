package io.github.kazumibridge.runtime

import android.content.Context
import android.content.SharedPreferences
import io.github.kazumibridge.core.CacheStorage
import io.github.kazumibridge.core.CastMode

data class BridgeSettings(
    val mode: CastMode,
    val forceH264: Boolean,
    val cacheGiB: Int,
    val referer: String,
    val cookie: String,
    val userAgent: String,
    val hlsParallelism: Int = 4,
    val cacheMp4: Boolean = false,
    val cacheStorage: CacheStorage = CacheStorage.DISK,
    val memoryCacheMiB: Int = 512,
) {
    val cacheLimitBytes: Long
        get() = if (cacheStorage == CacheStorage.MEMORY) memoryCacheMiB * 1024L * 1024 else cacheGiB * 1024L * 1024 * 1024

    fun withSourceHeaders(headers: Map<String, String>) = copy(
        referer = referer.ifBlank { headers["Referer"].orEmpty() }, cookie = cookie.ifBlank { headers["Cookie"].orEmpty() },
        userAgent = userAgent.ifBlank { headers["User-Agent"].orEmpty() })
    fun headers(): Map<String, String> = buildMap {
        put("User-Agent", userAgent.ifBlank { "Kazumi/2.3.8" })
        if (referer.isNotBlank()) put("Referer", referer.trim())
        if (cookie.isNotBlank()) put("Cookie", cookie.trim())
    }
}

object Settings {
    val hlsParallelismOptions = listOf(1, 2, 4, 8, 16, 32)
    val memoryCacheMiBOptions = listOf(64, 128, 256, 512, 1024, 2048)
    val diskCacheGiBOptions = listOf(1, 2, 4, 8)
    val cacheStorageOptions = listOf(CacheStorage.DISK, CacheStorage.MEMORY)
    private lateinit var preferences: SharedPreferences
    fun initialize(context: Context) { preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    fun read() = BridgeSettings(
        runCatching { CastMode.valueOf(preferences.getString("mode", "COMPLETE")!!) }.getOrDefault(CastMode.COMPLETE),
        preferences.getBoolean("forceH264", false), preferences.getInt("cacheGiB", 2).coerceIn(1, 8),
        preferences.getString("referer", "")!!, preferences.getString("cookie", "")!!,
        preferences.getString("userAgent", "")!!,
        preferences.getInt("hlsParallelism", 4).takeIf { it in hlsParallelismOptions } ?: 4,
        preferences.getBoolean("cacheMp4", false),
        runCatching { CacheStorage.valueOf(preferences.getString("cacheStorage", "DISK")!!) }.getOrDefault(CacheStorage.DISK),
        preferences.getInt("memoryCacheMiB", 512).takeIf { it in memoryCacheMiBOptions } ?: 512)
    fun save(value: BridgeSettings) {
        require(listOf(value.referer, value.cookie, value.userAgent).all { !it.contains('\r') && !it.contains('\n') && it.length <= 8192 }) { "请求头不能包含换行，长度不得超过 8192 字符。" }
        require(value.hlsParallelism in hlsParallelismOptions) { "HLS 下载并发数必须为 1、2、4、8、16 或 32。" }
        require(value.memoryCacheMiB in memoryCacheMiBOptions) { "内存缓存阈值必须为 64、128、256、512、1024 或 2048 MB。" }
        preferences.edit().putString("mode", value.mode.name).putBoolean("forceH264", value.forceH264)
            .putInt("cacheGiB", value.cacheGiB).putString("referer", value.referer).putString("cookie", value.cookie)
            .putString("userAgent", value.userAgent).putInt("hlsParallelism", value.hlsParallelism)
            .putBoolean("cacheMp4", value.cacheMp4).putString("cacheStorage", value.cacheStorage.name)
            .putInt("memoryCacheMiB", value.memoryCacheMiB).apply()
    }
}
