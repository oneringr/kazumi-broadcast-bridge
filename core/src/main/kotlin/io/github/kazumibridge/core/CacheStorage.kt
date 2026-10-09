package io.github.kazumibridge.core

import java.io.IOException

enum class CacheStorage { DISK, MEMORY }

/** Shared accounting for allocated media chunks, including temporary download buffers. */
class MemoryBudget(val limitBytes: Long) {
    private var used = 0L
    private var stopped: String? = null

    init { require(limitBytes > 0) { "内存缓存阈值必须大于零" } }

    val usedBytes: Long @Synchronized get() = used
    val failure: String? @Synchronized get() = stopped

    @Synchronized fun reserve(bytes: Long) {
        require(bytes >= 0) { "内存缓存预留量不能为负数" }
        stopped?.let { throw IOException(it) }
        if (bytes >= limitBytes - used) {
            stopped = "内存缓存达到设置阈值，已停止（上限 ${limitBytes / (1024 * 1024)} MB）"
            throw IOException(stopped)
        }
        used += bytes
    }

    @Synchronized fun release(bytes: Long) {
        require(bytes >= 0 && bytes <= used) { "内存缓存释放量超出已分配容量" }
        used -= bytes
    }
}
