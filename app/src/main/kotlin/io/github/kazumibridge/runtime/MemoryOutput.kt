package io.github.kazumibridge.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import io.github.kazumibridge.core.MemoryBudget
import io.github.kazumibridge.core.MemoryChunk
import io.github.kazumibridge.core.MemoryChunkFactory
import io.github.kazumibridge.core.MemoryMediaBuffer
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NativeMemoryChunks : MemoryChunkFactory {
    override fun allocate(capacity: Int): MemoryChunk {
        val allocated = NativeMemory.allocate(capacity)
        if (allocated == 0L) throw IOException("可用内存不足，已停止缓存。")
        return object : MemoryChunk {
            override val capacity = capacity
            private var handle = allocated
            @Synchronized override fun read(offset: Int, data: ByteArray, dataOffset: Int, count: Int) {
                checkOpen(); NativeMemory.read(handle, offset, data, dataOffset, count)
            }
            @Synchronized override fun write(offset: Int, data: ByteArray, dataOffset: Int, count: Int) {
                checkOpen(); NativeMemory.write(handle, offset, data, dataOffset, count)
            }
            private fun checkOpen() { if (handle == 0L) throw IOException("内存缓存已释放。") }
            @Synchronized override fun close() { if (handle != 0L) { NativeMemory.release(handle); handle = 0L } }
        }
    }
}

/** A private provider supplies seekable native I/O without creating a disk file. */
class MemoryOutput(private val context: Context, val budget: MemoryBudget) : AutoCloseable {
    val buffer = MemoryMediaBuffer(budget, NativeMemoryChunks)
    private val id = UUID.randomUUID().toString()
    private val uri = Uri.parse("content://${context.packageName}.memory/$id/video.mp4")
    @Volatile var error: String? = null
        private set
    private val descriptors = ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
    private var closed = false
    init { MemoryMediaProvider.outputs[id] = this }
    fun parameter(): String = FFmpegKitConfig.getSafParameter(context, uri, "rw")
    internal fun failed(message: String) { if (error == null) error = message }
    @Synchronized internal fun retain(descriptor: ParcelFileDescriptor): ParcelFileDescriptor {
        if (closed) { descriptor.close(); throw FileNotFoundException("内存缓存已释放。") }
        descriptors.add(descriptor)
        return descriptor
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        MemoryMediaProvider.outputs.remove(id, this)
        descriptors.toTypedArray().forEach { runCatching { it.close() } }
        descriptors.clear()
        buffer.close()
    }
}

class MemoryMediaProvider : ContentProvider() {
    override fun onCreate() = true
    private fun output(uri: Uri): MemoryOutput = uri.pathSegments.takeIf { it.size == 2 && it[1] == "video.mp4" }
        ?.firstOrNull()?.let(outputs::get) ?: throw FileNotFoundException("内存缓存不存在或已释放。")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode !in setOf("r", "w", "rw")) throw FileNotFoundException("不支持的内存缓存访问模式。")
        val media = output(uri)
        val access = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE
        return media.retain(requireNotNull(context).getSystemService(StorageManager::class.java).openProxyFileDescriptor(access,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = operation { media.buffer.length }
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = operation { media.buffer.read(offset, data, 0, size) }
                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = operation {
                    media.buffer.write(offset, data, 0, size); size
                }
                override fun onFsync() { /* This backend deliberately retains data only in RAM. */ }
                override fun onRelease() { /* The cast, rather than an individual native FD, owns the buffer. */ }
                private fun <T> operation(action: () -> T): T = try { action() } catch (error: IOException) {
                    val message = media.budget.failure ?: error.message ?: "内存缓存读写失败。"
                    media.failed(message)
                    throw ErrnoException("memory cache", if (media.budget.failure != null) OsConstants.ENOSPC else OsConstants.EIO, error)
                }
            }, handler))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val media = output(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> "video.mp4"
            OpenableColumns.SIZE -> media.buffer.length
            else -> null
        } }) }
    }
    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object {
        internal val outputs = ConcurrentHashMap<String, MemoryOutput>()
        private val handler by lazy { Handler(HandlerThread("KazumiBridge-memory-io").apply { start() }.looper) }
    }
}
