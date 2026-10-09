package io.github.kazumibridge.core

import java.io.IOException
import java.util.Arrays

interface MemoryChunk : AutoCloseable {
    val capacity: Int
    fun read(offset: Int, data: ByteArray, dataOffset: Int, count: Int)
    fun write(offset: Int, data: ByteArray, dataOffset: Int, count: Int)
}

fun interface MemoryChunkFactory {
    /** The allocated chunk must be zero-filled and have exactly the requested capacity. */
    fun allocate(capacity: Int): MemoryChunk
}

object HeapMemoryChunkFactory : MemoryChunkFactory {
    override fun allocate(capacity: Int): MemoryChunk = object : MemoryChunk {
        private var bytes: ByteArray? = ByteArray(capacity)
        override val capacity = capacity

        override fun read(offset: Int, data: ByteArray, dataOffset: Int, count: Int) {
            val current = bytes ?: throw IOException("内存缓存块已关闭")
            current.copyInto(data, dataOffset, offset, offset + count)
        }

        override fun write(offset: Int, data: ByteArray, dataOffset: Int, count: Int) {
            val current = bytes ?: throw IOException("内存缓存块已关闭")
            data.copyInto(current, offset, dataOffset, dataOffset + count)
        }

        override fun close() { bytes = null }
    }
}

/** Seekable media storage. Missing chunks represent zero-filled holes without consuming budget. */
class MemoryMediaBuffer(
    private val budget: MemoryBudget,
    private val factory: MemoryChunkFactory = HeapMemoryChunkFactory,
) : AutoCloseable {
    private val chunks = HashMap<Long, MemoryChunk>()
    private var size = 0L
    private var closed = false

    val length: Long @Synchronized get() = size
    val allocatedBytes: Long @Synchronized get() = chunks.size.toLong() * CHUNK_SIZE

    @Synchronized fun read(
        offset: Long,
        data: ByteArray,
        dataOffset: Int = 0,
        count: Int = data.size - dataOffset,
    ): Int {
        checkOpen()
        checkRange(offset, data, dataOffset, count)
        if (offset >= size || count == 0) return 0
        val available = minOf(count.toLong(), size - offset).toInt()
        var position = offset
        var destination = dataOffset
        var remaining = available
        while (remaining > 0) {
            val within = (position % CHUNK_SIZE).toInt()
            val amount = minOf(remaining, CHUNK_SIZE - within)
            val chunk = chunks[position / CHUNK_SIZE]
            if (chunk == null) Arrays.fill(data, destination, destination + amount, 0.toByte())
            else chunk.read(within, data, destination, amount)
            position += amount
            destination += amount
            remaining -= amount
        }
        return available
    }

    @Synchronized fun write(
        offset: Long,
        data: ByteArray,
        dataOffset: Int = 0,
        count: Int = data.size - dataOffset,
    ) {
        checkOpen()
        val end = checkRange(offset, data, dataOffset, count)
        budget.reserve(0) // A failed shared budget also forbids overwriting existing chunks.
        if (count == 0) return
        val added = ArrayList<Long>()
        try {
            var index = offset / CHUNK_SIZE
            val last = (end - 1) / CHUNK_SIZE
            while (index <= last) {
                if (!chunks.containsKey(index)) {
                    val chunk = allocateChunk()
                    try {
                        added.add(index)
                        chunks[index] = chunk
                    } catch (failure: Throwable) {
                        try { chunk.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                        budget.release(CHUNK_SIZE.toLong())
                        throw failure
                    }
                }
                index++
            }
            var position = offset
            var source = dataOffset
            var remaining = count
            while (remaining > 0) {
                val within = (position % CHUNK_SIZE).toInt()
                val amount = minOf(remaining, CHUNK_SIZE - within)
                chunks.getValue(position / CHUNK_SIZE).write(within, data, source, amount)
                position += amount
                source += amount
                remaining -= amount
            }
            size = maxOf(size, end)
        } catch (failure: Throwable) {
            for (index in added) chunks.remove(index)?.let { chunk ->
                try { chunk.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                finally { budget.release(CHUNK_SIZE.toLong()) }
            }
            throw failure
        }
    }

    @Synchronized fun reset() {
        checkOpen()
        releaseChunks()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        releaseChunks()
    }

    private fun allocateChunk(): MemoryChunk {
        budget.reserve(CHUNK_SIZE.toLong())
        var chunk: MemoryChunk? = null
        try {
            chunk = factory.allocate(CHUNK_SIZE)
            if (chunk.capacity != CHUNK_SIZE) throw IOException("内存缓存块容量与预留量不一致")
            return chunk
        } catch (failure: Throwable) {
            try { chunk?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            finally { budget.release(CHUNK_SIZE.toLong()) }
            if (failure is Error && failure !is OutOfMemoryError) throw failure
            throw IOException("内存缓存分配失败", failure)
        }
    }

    private fun releaseChunks() {
        var failure: IOException? = null
        val iterator = chunks.values.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            iterator.remove()
            try { chunk.close() } catch (closeFailure: Exception) {
                if (failure == null) failure = IOException("内存缓存释放失败", closeFailure)
                else failure.addSuppressed(closeFailure)
            } finally { budget.release(CHUNK_SIZE.toLong()) }
        }
        size = 0
        failure?.let { throw it }
    }

    private fun checkOpen() {
        if (closed) throw IOException("内存缓存已关闭")
    }

    private fun checkRange(offset: Long, data: ByteArray, dataOffset: Int, count: Int): Long {
        require(offset >= 0) { "内存缓存偏移不能为负数" }
        require(dataOffset >= 0 && count >= 0 && count <= data.size && dataOffset <= data.size - count) {
            "内存缓存读写数组范围无效"
        }
        require(offset <= Long.MAX_VALUE - count) { "内存缓存偏移超出范围" }
        return offset + count
    }

    companion object { const val CHUNK_SIZE = 64 * 1024 }
}
