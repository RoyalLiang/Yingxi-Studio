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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A single, independent, cancellable read identity; never seeks the playing MediaSession. */
internal interface PreviewDecoder : Closeable { fun prepare() {}; fun frame(timeMs: Long): Bitmap? }

internal data class PreviewFrame(val bitmap: Bitmap, val timeMs: Long)

class PreviewFrames internal constructor(private val cache:LruCache<String,Bitmap> = memoryCache(),private val scope:String="",private val openDecoder:(Uri)->PreviewDecoder) : Closeable {
    constructor(context:Context,scope:String=""):this(playbackCache,scope,{uri->AndroidPreviewDecoder(context,uri)})
    internal companion object {
        private val closer=java.util.concurrent.Executors.newSingleThreadExecutor()
        private val cacheRevision=java.util.concurrent.atomic.AtomicLong()
        internal fun memoryCache()=object:LruCache<String,Bitmap>(24*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
        internal val playbackCache=memoryCache()
        internal fun clearPlaybackCache(){synchronized(playbackCache){cacheRevision.incrementAndGet();playbackCache.evictAll()}}
    }
    private data class Session(val uri: Uri, val decoder: PreviewDecoder)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val warmGeneration = java.util.concurrent.atomic.AtomicLong()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.CancellableContinuation<Bitmap?>>()

    // Only the worker changes session; cancellation may close the current reader to unblock I/O.
    private var session: Session? = null
    @Volatile private var active: PreviewDecoder? = null
    @Volatile private var lastFrame: Pair<Uri, PreviewFrame>? = null
    private fun cacheKey(uri:Uri,timeMs:Long)="$scope\u0000$uri:${bucket(timeMs)}"
    private fun evictOwnedCache(){if(cache!==playbackCache)cache.evictAll()}
    private fun bucket(timeMs: Long) = timeMs.coerceAtLeast(0) / 1000 * 1000
    internal fun cached(uri: Uri, timeMs: Long): PreviewFrame? = if(closed.get())null else
        cache.get(cacheKey(uri,timeMs))?.let { PreviewFrame(it, bucket(timeMs)) }
    internal fun latest(uri: Uri): PreviewFrame? = if(closed.get())null else lastFrame?.takeIf { it.first == uri }?.second

    internal fun nearest(uri:Uri,timeMs:Long):PreviewFrame? {
        if(closed.get())return null
        val prefix="$scope\u0000$uri:"
        return cache.snapshot().entries.mapNotNull{(key,bitmap)->
            key.takeIf{it.startsWith(prefix)}?.removePrefix(prefix)?.toLongOrNull()?.let{PreviewFrame(bitmap,it)}
        }.filter{kotlin.math.abs(it.timeMs-timeMs)<=2000}.minByOrNull{kotlin.math.abs(it.timeMs-timeMs)}
    }

    /** Cached targets bypass a busy reader; queued misses collapse to the latest position. */
    internal fun frames(uri: Uri, positions: Flow<Long>): Flow<PreviewFrame> = flow {
        coroutineScope {
            val requested = java.util.concurrent.atomic.AtomicLong(-1)
            val requests = Channel<Long>(Channel.CONFLATED)
            val results = Channel<PreviewFrame>(Channel.CONFLATED)
            val producer = launch {
                try {
                    positions.map(::bucket).distinctUntilChanged().collect { time ->
                        requested.set(time)
                        cached(uri,time)?.let { results.send(it) }
                        requests.send(time)
                    }
                } finally { requests.close() }
            }
            val reader = launch {
                try {
                    for(time in requests) {
                        if(time!=requested.get())continue
                        try {
                            val bitmap=withTimeoutOrNull(2500) { get(uri,time) }
                            if(bitmap!=null&&time==requested.get())results.send(PreviewFrame(bitmap,time))
                        } catch(e:CancellationException) { throw e }
                        catch(_:Exception) { /* A failed preview must not interrupt video playback. */ }
                    }
                } finally { results.close() }
            }
            try {
                for(frame in results)if(frame.timeMs==requested.get())emit(frame)
            } finally { producer.cancel();reader.cancel();requests.cancel();results.cancel() }
        }
    }

    internal fun releaseReader() {
        if(closed.get())return
        warmGeneration.incrementAndGet()
        active?.let{decoder->closer.execute{runCatching{decoder.close()}}}
        try { worker.execute { discard(session?.decoder) } }
        catch(_:java.util.concurrent.RejectedExecutionException){ /* Already disposed. */ }
    }

    /** Prepare metadata only; a second video decoder would compete with playback for CPU. */
    internal fun warm(uri: Uri) {
        if(closed.get())return
        val ticket=warmGeneration.incrementAndGet()
        try { worker.execute {
            if(closed.get()||ticket!=warmGeneration.get()||session?.uri==uri)return@execute
            discard(session?.decoder)
            runCatching { openDecoder(uri) }.getOrNull()?.let { decoder ->
                active=decoder
                try{decoder.prepare()}catch(_:Exception){discard(decoder);return@let}
                if(closed.get()||ticket!=warmGeneration.get())discard(decoder)
                else session=Session(uri,decoder)
            }
        } } catch(_:java.util.concurrent.RejectedExecutionException) { /* Disposed. */ }
    }

    private fun discard(decoder: PreviewDecoder?) {
        decoder ?: return
        if (session?.decoder === decoder) session = null
        if (active === decoder) active = null
        closer.execute { runCatching { decoder.close() } }
    }

    suspend fun get(uri: Uri, timeMs: Long): Bitmap? {
        if (closed.get()) return null
        cached(uri,timeMs)?.let { return it.bitmap }
        return mutex.withLock {
            if (closed.get()) return@withLock null
            val revision=cacheRevision.get()
            val position = bucket(timeMs)
            val key = cacheKey(uri,position)
            cache.get(key)?.let { return@withLock it }
            suspendCancellableCoroutine { continuation ->
                val resource = java.util.concurrent.atomic.AtomicReference<PreviewDecoder?>(null)
                val submitted = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<*>?>(null)
                pending.add(continuation)
                continuation.invokeOnCancellation {
                    pending.remove(continuation)
                    submitted.get()?.cancel(true)
                    resource.get()?.let { value -> closer.execute { runCatching { value.close() } } }
                }
                try {
                    val future = worker.submit {
                        var decoder: PreviewDecoder? = null
                        var reusable = false
                        try {
                            if (closed.get() || !continuation.isActive) { if(continuation.isActive)continuation.resumeWith(Result.success(null));return@submit }
                            if (session?.uri != uri) discard(session?.decoder)
                            decoder = session?.decoder ?: openDecoder(uri).also { session = Session(uri,it) }
                            resource.set(decoder); active = decoder
                            if (closed.get() || !continuation.isActive) { if(continuation.isActive)continuation.resumeWith(Result.success(null));return@submit }
                            val bitmap = decoder.frame(position)
                            if (continuation.isActive && !closed.get()) {
                                val valid=synchronized(cache){
                                if(cache!==playbackCache||revision==cacheRevision.get()){
                                    if(bitmap!=null)cache.put(key,bitmap)
                                    true
                                }else false
                            }
                            if(valid&&bitmap!=null)lastFrame=uri to PreviewFrame(bitmap,position)
                            reusable = true
                            continuation.resumeWith(Result.success(if(valid)bitmap else null))
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        } finally {
                            pending.remove(continuation)
                            if (!reusable || continuation.isCancelled || closed.get()) discard(decoder)
                            if (closed.get()) { evictOwnedCache(); lastFrame = null }
                        }
                    }
                    submitted.set(future)
                    if (!continuation.isActive) future.cancel(true)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    pending.remove(continuation)
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        pending.toList().forEach { it.cancel() }
        active?.let { decoder -> closer.execute { runCatching { decoder.close() } } }
        worker.shutdownNow()
        evictOwnedCache(); lastFrame = null
    }
}

private class AndroidPreviewDecoder(private val context: Context, private val uri: Uri) : PreviewDecoder {
    private val retriever = MediaMetadataRetriever()
    private val closed = AtomicBoolean(false)
    private var prepared = false
    private val data = if (uri.isPlayerNetworkUri()) RemotePreviewData(PlaybackRegistry.preview(uri)) else null
    override fun prepare() {
        check(!closed.get())
        if(!prepared)try {
            if(data!=null)retriever.setDataSource(data)else retriever.setDataSource(context,uri)
            prepared=true
        } catch(e:Exception) { close();throw e }
    }
    override fun frame(timeMs: Long): Bitmap? {
        prepare()
        return retriever.getScaledFrameAtTime(timeMs*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { data?.close() }
            runCatching { retriever.release() }
        }
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
