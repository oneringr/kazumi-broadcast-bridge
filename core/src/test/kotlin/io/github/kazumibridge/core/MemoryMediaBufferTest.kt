package io.github.kazumibridge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MemoryMediaBufferTest {
    private val block = MemoryMediaBuffer.CHUNK_SIZE

    @Test fun crossBlockReadsAndSeekOverwritePreserveBytesWithoutExtraAllocation() {
        val budget = MemoryBudget(5L * block)
        MemoryMediaBuffer(budget).use { buffer ->
            val original = ByteArray(block + 31) { (it % 251).toByte() }
            buffer.write(block - 9L, original)
            assertEquals(3L * block, buffer.allocatedBytes)
            assertEquals(2L * block + 22, buffer.length)
            buffer.write(block + 3L, byteArrayOf(99, 98, 97))
            original[12] = 99; original[13] = 98; original[14] = 97
            val output = ByteArray(original.size + 7) { 42 }
            assertEquals(original.size, buffer.read(block - 9L, output, 3, original.size + 4))
            assertArrayEquals(original, output.copyOfRange(3, original.size + 3))
            assertEquals(42.toByte(), output.first())
            assertEquals(42.toByte(), output.last())
            assertEquals(0, buffer.read(buffer.length, ByteArray(8)))
            assertEquals(3L * block, budget.usedBytes)
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun sparseLongOffsetsReadAsZerosWithoutAllocatingHoles() {
        val budget = MemoryBudget(2L * block)
        MemoryMediaBuffer(budget).use { buffer ->
            val farOffset = 1L shl 40
            buffer.write(farOffset + 5, byteArrayOf(12, 34))
            assertEquals(farOffset + 7, buffer.length)
            assertEquals(block.toLong(), budget.usedBytes)
            val gap = ByteArray(32) { 88 }
            assertEquals(gap.size, buffer.read(0, gap))
            assertArrayEquals(ByteArray(gap.size), gap)
            val boundary = ByteArray(14) { 88 }
            assertEquals(10, buffer.read(farOffset - 3, boundary))
            assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 12, 34), boundary.copyOf(10))
            assertEquals(88.toByte(), boundary[10])
        }
    }

    @Test fun sharedThresholdStopsBothBuffersAndNeverAllocatesAtTheLimit() {
        val allocations = AtomicInteger()
        val budget = MemoryBudget(3L * block)
        val factory = MemoryChunkFactory { capacity ->
            allocations.incrementAndGet()
            HeapMemoryChunkFactory.allocate(capacity)
        }
        val first = MemoryMediaBuffer(budget, factory)
        val second = MemoryMediaBuffer(budget, factory)
        first.write(0, byteArrayOf(1))
        second.write(0, byteArrayOf(2))
        val failure = assertThrows(IOException::class.java) { second.write(block.toLong(), byteArrayOf(3)) }
        assertTrue(failure.message!!.contains("达到设置阈值"))
        assertEquals(failure.message, budget.failure)
        assertEquals(2, allocations.get())
        assertEquals(2L * block, budget.usedBytes)
        assertEquals(1L, second.length)
        assertThrows(IOException::class.java) { first.write(0, byteArrayOf(4)) }
        first.close()
        assertEquals(block.toLong(), budget.usedBytes)
        second.reset()
        assertEquals(0L, budget.usedBytes)
        assertThrows(IOException::class.java) { second.write(0, byteArrayOf(5)) }
        first.close(); second.close(); second.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun failedLargeWriteRollsBackAllNewChunks() {
        val closed = AtomicInteger()
        val budget = MemoryBudget(3L * block)
        val buffer = MemoryMediaBuffer(budget, countingFactory(closed))
        assertThrows(IOException::class.java) { buffer.write(0, ByteArray(2 * block + 1)) }
        assertEquals(0L, budget.usedBytes)
        assertEquals(0L, buffer.length)
        assertEquals(0L, buffer.allocatedBytes)
        assertEquals(2, closed.get())
        buffer.close()
        assertEquals(2, closed.get())
    }

    @Test fun allocationFailureReleasesReservationsAndEarlierNewChunks() {
        val allocated = AtomicInteger()
        val budget = MemoryBudget(4L * block)
        val buffer = MemoryMediaBuffer(budget, MemoryChunkFactory { capacity ->
            if (allocated.incrementAndGet() == 2) throw OutOfMemoryError("fixture")
            HeapMemoryChunkFactory.allocate(capacity)
        })
        val failure = assertThrows(IOException::class.java) { buffer.write(0, ByteArray(block + 1)) }
        assertTrue(failure.message!!.contains("分配失败"))
        assertTrue(failure.cause is OutOfMemoryError)
        assertEquals(0L, budget.usedBytes)
        assertEquals(0L, buffer.length)
        assertEquals(0L, buffer.allocatedBytes)
        buffer.write(0, byteArrayOf(7))
        assertEquals(block.toLong(), budget.usedBytes)
        buffer.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun resetReleasesStorageAndStartsFreshWithZeroFilledBytes() {
        val budget = MemoryBudget(3L * block)
        val buffer = MemoryMediaBuffer(budget)
        buffer.write(0, ByteArray(block) { 55 })
        buffer.reset()
        assertEquals(0L, buffer.length)
        assertEquals(0L, budget.usedBytes)
        buffer.write(10, byteArrayOf(4))
        val data = ByteArray(11)
        assertEquals(11, buffer.read(0, data))
        assertArrayEquals(ByteArray(10) + byteArrayOf(4), data)
        buffer.close()
        assertThrows(IOException::class.java) { buffer.write(0, byteArrayOf(5)) }
        assertThrows(IOException::class.java) { buffer.read(0, ByteArray(0)) }
        assertThrows(IOException::class.java) { buffer.reset() }
    }

    @Test fun invalidOffsetsAndArrayRangesDoNotConsumeBudget() {
        val budget = MemoryBudget(2L * block)
        MemoryMediaBuffer(budget).use { buffer ->
            assertThrows(IllegalArgumentException::class.java) { buffer.write(-1, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { buffer.write(Long.MAX_VALUE, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { buffer.write(0, ByteArray(1), 1, 1) }
            assertThrows(IllegalArgumentException::class.java) { buffer.read(0, ByteArray(1), -1, 1) }
            assertThrows(IllegalArgumentException::class.java) { buffer.read(0, ByteArray(1), 0, Int.MAX_VALUE) }
            buffer.write(Long.MAX_VALUE, ByteArray(0))
            assertEquals(0L, buffer.length)
            assertEquals(0L, budget.usedBytes)
        }
        assertThrows(IllegalArgumentException::class.java) { budget.release(1) }
    }

    @Test fun parallelWritersPreserveAllDistinctRegionsAndShareAccounting() {
        val writers = 32
        val budget = MemoryBudget((writers + 1L) * block)
        val buffer = MemoryMediaBuffer(budget)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until writers).map { writer -> executor.submit {
                buffer.write(writer.toLong() * block, ByteArray(256) { writer.toByte() })
            } }
            for (future in futures) future.get(5, TimeUnit.SECONDS)
            for (writer in 0 until writers) {
                val data = ByteArray(256)
                assertEquals(data.size, buffer.read(writer.toLong() * block, data))
                assertArrayEquals(ByteArray(data.size) { writer.toByte() }, data)
            }
            assertEquals(writers.toLong() * block, budget.usedBytes)
        } finally {
            executor.shutdownNow()
            buffer.close()
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun closingDuringAWriteWaitsForTheChunkAndReleasesItOnce() {
        val entered = CountDownLatch(1)
        val allowAllocation = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = AtomicInteger()
        val budget = MemoryBudget(2L * block)
        val counting = countingFactory(closed)
        val buffer = MemoryMediaBuffer(budget, MemoryChunkFactory { capacity ->
            entered.countDown()
            check(allowAllocation.await(5, TimeUnit.SECONDS))
            counting.allocate(capacity)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val write = executor.submit { buffer.write(0, byteArrayOf(5)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = executor.submit { closing.countDown(); buffer.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            allowAllocation.countDown()
            write.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS)
            assertEquals(0L, budget.usedBytes)
            assertEquals(1, closed.get())
            assertThrows(IOException::class.java) { buffer.write(0, byteArrayOf(6)) }
            buffer.close()
            assertEquals(1, closed.get())
        } finally {
            allowAllocation.countDown()
            executor.shutdownNow()
            buffer.close()
        }
    }

    private fun countingFactory(closed: AtomicInteger) = MemoryChunkFactory { capacity ->
        val delegate = HeapMemoryChunkFactory.allocate(capacity)
        object : MemoryChunk {
            override val capacity = delegate.capacity
            override fun read(offset: Int, data: ByteArray, dataOffset: Int, count: Int) = delegate.read(offset, data, dataOffset, count)
            override fun write(offset: Int, data: ByteArray, dataOffset: Int, count: Int) = delegate.write(offset, data, dataOffset, count)
            override fun close() { closed.incrementAndGet(); delegate.close() }
        }
    }
}
