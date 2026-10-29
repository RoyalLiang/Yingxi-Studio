package io.localplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/** One decoder at a time, independent of playback transports. Frames never leave app-private cache. */
internal object MediaThumbnails {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = Executors.newSingleThreadExecutor()
    private val closer = Executors.newFixedThreadPool(2)
    internal suspend fun <T> extract(cancellation:Cancellation,work:()->T):T = suspendCancellableCoroutine { result ->
        val future=worker.submit {
            try { cancellation.check();val value=work();if(result.isActive)result.resumeWith(Result.success(value)) }
            catch(error:Exception){if(result.isActive)result.resumeWith(Result.failure(error))}
            finally { cancellation.close() }
        }
        result.invokeOnCancellation { future.cancel(true);closer.execute{cancellation.close()} }
    }
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val generation=java.util.concurrent.atomic.AtomicLong()
    val changes = MutableStateFlow(0L)
    private const val MAX_DISK_BYTES = 24L * 1024 * 1024
    private const val MAX_REMOTE_BYTES = 16L * 1024 * 1024
    private fun directory(context: Context) = File(context.cacheDir, "media-thumbnails-v1").apply { mkdirs() }
    private fun key(media:MediaRef)=enrichmentKey(media)

    fun warm(context: Context, media: MediaRef, activeUri: Uri) {
        scope.launch { load(context.applicationContext, media, activeUri) }
    }

    private suspend fun openLocal(context:Context,uri:Uri,cancellation:Cancellation):android.content.res.AssetFileDescriptor = suspendCancellableCoroutine { result ->
        val signal=android.os.CancellationSignal()
        cancellation.own(java.io.Closeable{signal.cancel()})
        val task=java.util.concurrent.FutureTask<Unit> {
            try {
                cancellation.check()
                val descriptor=if(uri.scheme=="content"){
                    val client=context.contentResolver.acquireContentProviderClient(uri)?:throw IOException("Cannot reach thumbnail provider")
                    cancellation.own(java.io.Closeable{client.close()})
                    // ContentResolver's typed */* fallback can discard the provider cancellation signal.
                    try{client.openAssetFile(uri,"r",signal)}catch(_:java.io.FileNotFoundException){client.openTypedAssetFileDescriptor(uri,"*/*",null,signal)}
                }else context.contentResolver.openAssetFileDescriptor(uri,"r",signal)
                if(descriptor==null)throw IOException("Cannot open thumbnail")
                cancellation.own(descriptor)
                if(result.isActive)result.resumeWith(Result.success(descriptor))else descriptor.close()
            }catch(error:Exception){if(result.isActive)result.resumeWith(Result.failure(error))}
            finally{Thread.interrupted()} // Do not leak Future cancellation into the shared IO pool.
        }
        result.invokeOnCancellation{task.cancel(true);Dispatchers.IO.asExecutor().execute{signal.cancel()}}
        Dispatchers.IO.asExecutor().execute(task)
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun load(context: Context, media: MediaRef, activeUri: Uri? = null): Bitmap? = withContext(dispatcher) {
        val caller=coroutineContext.job
        val cacheGeneration=generation.get()
        if(SealedMedia.protects(media)&&!SealedMedia.unlocked)return@withContext null
        val cacheKey = key(media)
        val history = HistoryStore(context)
        val token = history.token(media)
        val store = SourceStore(context)
        // An edited server, principal or host key cannot reuse a prior server's frame.
        val source = if (media.sourceId != null) runCatching { store.load() }.getOrDefault(emptyList()).firstOrNull { it.id == media.sourceId } else null
        if(runCatching{MediaDataMigration.session(context).checkLocator(media)}.isFailure)return@withContext null
        if (media.sourceId != null && (source == null || !SourceBindingEpochs.matches(context,source,media) || media.path?.let{io.localplayer.core.SourceRoot(source.initialPath).contains(it)}!=true)) return@withContext null
        val target=File(directory(context),"$cacheKey.jpg")
        var decode=false
        val cached=extract(Cancellation()) {
            memory.get(cacheKey)?.let { return@extract it }
            if (target.isFile) {
                BitmapFactory.decodeFile(target.path)?.let {
                    target.setLastModified(System.currentTimeMillis())
                    memory.put(cacheKey, it)
                    return@extract it
                }
                target.delete()
            }
            if (activeUri == null && SystemClock.elapsedRealtime() - (failed[cacheKey] ?: Long.MIN_VALUE / 2) < 60_000) return@extract null
            decode=true
            null
        }
        if(!decode)return@withContext cached
        val cancellation=Cancellation()
        try {
            withTimeoutOrNull(20_000) {
                // Provider RPC runs before entering the single native decoder lane.
                val descriptor=media.localUri?.let{openLocal(context,Uri.parse(it),cancellation)}
                extract(cancellation) {
                    var retriever:MediaMetadataRetriever?=null
                    var preview:PlaybackLease?=null
                    try {
                        cancellation.check()
                        retriever=MediaMetadataRetriever()
                        if(descriptor!=null){
                            if(descriptor.declaredLength<0)retriever.setDataSource(descriptor.fileDescriptor)
                            else retriever.setDataSource(descriptor.fileDescriptor,descriptor.startOffset,descriptor.declaredLength)
                        }else {
                            preview = if (activeUri?.isPlayerNetworkUri() == true) PlaybackRegistry.preview(activeUri) else {
                                val saved = source ?: return@extract null
                                val path = media.path ?: return@extract null
                                // Never open an authentication UI or bypass SFTP trust to obtain artwork.
                                val password = store.password(saved.id)
                                if (saved.username.isNotBlank() && password == null) return@extract null
                                PlaybackLease(saved, Credentials(password ?: ""), path, prefetch=false,authority={
                        SourceBindingEpochs.verifyLease(context,saved,media.bindingEpoch?:0)
                        MediaDataMigration.session(context).checkLocator(media)
                    })
                            }
                            if (media.sourceId != preview.source.id || (media.location != null && media.location != mediaLocation(preview.source))) throw IOException("Media source changed")
                            cancellation.own(preview)
                            val file = preview.open()
                            if (media.sizeBytes != null && media.sizeBytes != file.size) throw IOException("Media changed")
                            retriever.setDataSource(object : MediaDataSource() {
                                private var read = 0L
                                @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                                    cancellation.check()
                                    if (size == 0) return 0
                                    if (position >= file.size) return -1
                                    if (read >= MAX_REMOTE_BYTES) throw IOException("Thumbnail read budget exceeded")
                                    val length = minOf(size.toLong(), 256L * 1024, MAX_REMOTE_BYTES - read).toInt()
                                    preview?.checkCurrent()
                                val n = file.readAt(position, buffer, offset, length)
                                preview?.checkCurrent()
                                    if (n > 0) read += n
                                    return n
                                }
                                override fun getSize() = file.size
                                override fun close() = file.close()
                            })
                                    }
                        cancellation.check()
                        val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0
                        val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
                        val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
                        videoOrientation(width,height,rotation,1f)?.let{PlaybackOrientationProbe.remember(context,media,it)}
                        val bitmap = retriever.embeddedPicture?.let(MediaPosters::decode)
                            ?: retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                            ?: retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                            ?: return@extract null
                        cancellation.check()
                        caller.ensureActive()
                        // Clearing/removing history while extraction runs must not resurrect its private frame.
                        if (history.token(media) != token || generation.get()!=cacheGeneration || runCatching{preview?.checkCurrent()}.isFailure) return@extract null
                        val temporary = File(target.parentFile, "$cacheKey.tmp")
                        try {
                            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                            if (!temporary.renameTo(target)) throw IOException("Cannot save thumbnail")
                        } finally { temporary.delete() }
                        if (history.token(media) != token || generation.get()!=cacheGeneration || runCatching{preview?.checkCurrent()}.isFailure) { target.delete(); return@extract null }
                        memory.put(cacheKey, bitmap)
                        failed.remove(cacheKey)
                        trim(context)
                        changes.update { it + 1 }
                        bitmap
                    } finally {
                        cancellation.close()
                        runCatching{retriever?.release()}
                        runCatching{preview?.close()}
                    }
                }
            }
        }catch(error:CancellationException){throw error}catch(_:Exception){
            caller.ensureActive()
            failed[cacheKey]=SystemClock.elapsedRealtime()
            while(failed.size>128){val key=failed.keys.firstOrNull()?:break;failed.remove(key)}
            null
        }finally{closer.execute{cancellation.close()}}
    }

    fun remove(context: Context, media: MediaRef) {
        generation.incrementAndGet()
        worker.execute { PlaybackOrientationProbe.remove(context,media); val id = key(media); memory.remove(id); failed.remove(id); File(directory(context), "$id.jpg").delete(); changes.update { it + 1 } }
    }
    fun clear(context: Context) {
        generation.incrementAndGet()
        worker.execute { PlaybackOrientationProbe.clear(context); memory.evictAll(); failed.clear(); directory(context).listFiles()?.forEach { it.delete() }; changes.update { it + 1 } }
    }
    private fun trim(context: Context) {
        val files = directory(context).listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { index, file -> bytes += file.length(); if (index >= 128 || bytes > MAX_DISK_BYTES) file.delete() }
    }
}

@Composable
internal fun MediaThumbnail(media: MediaRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val revision by MediaThumbnails.changes.collectAsState()
    val posterRevision by MediaPosters.changes.collectAsState()
    val bitmap by key(media.key,media.location,media.sizeBytes,media.localUri) {
        produceState<Bitmap?>(null, revision, posterRevision) {
            // Keep the displayed frame while other cache entries notify observers.
            val saved=MediaPosters.load(context,media,localOnly=true)
            if(saved!=null)value=saved else coroutineScope {
                var posterLoaded=false
                launch { MediaPosters.load(context,media)?.let { posterLoaded=true;value=it } }
                val frame=MediaThumbnails.load(context,media)
                if(!posterLoaded)value=frame
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(Berry.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
        Crossfade(targetState = bitmap, animationSpec = tween(180), label = "thumbnail fade") { frame ->
            if (frame != null) Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().testTag("media-thumbnail:${media.key}"), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = Berry) }
        }
    }
}
