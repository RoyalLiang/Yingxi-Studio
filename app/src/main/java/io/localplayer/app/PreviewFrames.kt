package io.localplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import io.localplayer.core.ReadAheadFile
import io.localplayer.core.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A single, independent, cancellable read identity; never seeks the playing MediaSession. */
class PreviewFrames(private val context: Context) : Closeable {
    private companion object { val closer = java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var active: Closeable? = null

    suspend fun get(uri: Uri, timeMs: Long): Bitmap? = mutex.withLock {
        val bucket = timeMs.coerceAtLeast(0) / 5000 * 5000
        val key = "$uri:$bucket"
        cache.get(key)?.let { return@withLock it }
        suspendCancellableCoroutine<Bitmap?> { continuation ->
            val resource = java.util.concurrent.atomic.AtomicReference<Closeable?>(null)
            val future = worker.submit {
            if (closed.get() || !continuation.isActive) { if(continuation.isActive)continuation.resumeWith(Result.success(null));return@submit }
            val retriever = MediaMetadataRetriever()
            var data: RemotePreviewData? = null
            try {
                if (uri.scheme == "localplayer") {
                    val original = PlaybackRegistry.get(uri)
                    val lease = PlaybackLease(original.source, original.credentials, original.path)
                    data = RemotePreviewData(lease)
                    resource.set(data)
                    active = data
                    if (closed.get() || !continuation.isActive) throw IOException("预览已关闭")
                    retriever.setDataSource(data)
                } else {
                    retriever.setDataSource(context, uri)
                }
                val bitmap = retriever.getScaledFrameAtTime(bucket * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                if (continuation.isActive && !closed.get()) {
                    if (bitmap != null) cache.put(key, bitmap)
                    continuation.resumeWith(Result.success(bitmap))
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            } finally {
                runCatching { retriever.release() }
                runCatching { data?.close() }
                active = null
            }
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                resource.get()?.let { value -> closer.execute { runCatching { value.close() } } }
            }
        }
    }

    override fun close() {
        closed.set(true)
        active?.let { resource -> closer.execute { runCatching { resource.close() } } }
        worker.shutdownNow()
        cache.evictAll()
    }
}

private class RemotePreviewData(private val lease: PlaybackLease) : MediaDataSource() {
    private val closed = AtomicBoolean(false)
    @Volatile private var file: RemoteFile? = null
    private fun opened(): RemoteFile {
        if (closed.get()) throw IOException("预览已取消")
        file?.let { return it }
        val result = ReadAheadFile(lease.open(), 128 * 1024)
        file = result
        if (closed.get()) { result.close(); throw IOException("预览已取消") }
        return result
    }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (size == 0) 0 else opened().readAt(position, buffer, offset, size)
    override fun getSize() = opened().size
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { file?.close() }
            lease.close()
        }
    }
}
