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
import java.util.concurrent.TimeUnit

/** One decoder at a time, independent of playback transports. Frames never leave app-private cache. */
internal object MediaThumbnails {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = linkedMapOf<String, Long>()
    val changes = MutableStateFlow(0L)
    private const val MAX_DISK_BYTES = 24L * 1024 * 1024
    private const val MAX_REMOTE_BYTES = 16L * 1024 * 1024
    private fun directory(context: Context) = File(context.cacheDir, "media-thumbnails-v1").apply { mkdirs() }
    private fun key(media: MediaRef): String {
        val identity = listOf(media.key, media.location, media.sizeBytes, media.localUri).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun warm(context: Context, media: MediaRef, activeUri: Uri) {
        scope.launch { load(context.applicationContext, media, activeUri) }
    }

    suspend fun load(context: Context, media: MediaRef, activeUri: Uri? = null): Bitmap? = withContext(dispatcher) {
        val cacheKey = key(media)
        val history = HistoryStore(context)
        val token = history.token(media.key)
        val store = SourceStore(context)
        // An edited server, principal or host key cannot reuse a prior server's frame.
        val source = if (media.sourceId != null) runCatching { store.load() }.getOrDefault(emptyList()).firstOrNull { it.id == media.sourceId } else null
        if (media.sourceId != null && activeUri == null && (source == null || media.location != mediaLocation(source))) return@withContext null
        memory.get(cacheKey)?.let { return@withContext it }
        val target = File(directory(context), "$cacheKey.jpg")
        if (target.isFile) {
            BitmapFactory.decodeFile(target.path)?.let {
                target.setLastModified(System.currentTimeMillis())
                memory.put(cacheKey, it)
                return@withContext it
            }
            target.delete()
        }
        if (activeUri == null && SystemClock.elapsedRealtime() - (failed[cacheKey] ?: Long.MIN_VALUE / 2) < 60_000) return@withContext null
        val cancellation = Cancellation()
        val timeout = timer.schedule({ cancellation.close() }, 20, TimeUnit.SECONDS)
        var retriever: MediaMetadataRetriever? = null
        var preview: PlaybackLease? = null
        try {
            cancellation.check()
            retriever = MediaMetadataRetriever()
            val local = media.localUri?.let(Uri::parse)
            if (local != null) {
                retriever.setDataSource(context, local)
            } else {
                preview = if (activeUri?.scheme == "localplayer") PlaybackRegistry.preview(activeUri) else {
                    val saved = source ?: return@withContext null
                    val path = media.path ?: return@withContext null
                    // Never open an authentication UI or bypass SFTP trust to obtain artwork.
                    val password = store.password(saved.id)
                    if (saved.username.isNotBlank() && password == null) return@withContext null
                    PlaybackLease(saved, Credentials(password ?: ""), path)
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
                        val n = file.readAt(position, buffer, offset, length)
                        if (n > 0) read += n
                        return n
                    }
                    override fun getSize() = file.size
                    override fun close() = file.close()
                })
            }
            cancellation.check()
            val bitmap = retriever.embeddedPicture?.let(MediaPosters::decode)
                ?: retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                ?: retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                ?: return@withContext null
            cancellation.check()
            ensureActive()
            // Clearing/removing history while extraction runs must not resurrect its private frame.
            if (history.token(media.key) != token) return@withContext null
            val temporary = File(target.parentFile, "$cacheKey.tmp")
            try {
                temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                if (!temporary.renameTo(target)) throw IOException("Cannot save thumbnail")
            } finally { temporary.delete() }
            if (history.token(media.key) != token) { target.delete(); return@withContext null }
            memory.put(cacheKey, bitmap)
            failed.remove(cacheKey)
            trim(context)
            changes.update { it + 1 }
            bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed[cacheKey] = SystemClock.elapsedRealtime()
            while (failed.size > 128) failed.remove(failed.keys.first())
            null
        } finally {
            timeout.cancel(false)
            runCatching { retriever?.release() }
            cancellation.close()
            runCatching { preview?.close() }
        }
    }

    fun remove(context: Context, media: MediaRef) {
        scope.launch { val id = key(media); memory.remove(id); failed.remove(id); File(directory(context), "$id.jpg").delete(); changes.update { it + 1 } }
    }
    fun clear(context: Context) {
        scope.launch { memory.evictAll(); failed.clear(); directory(context).listFiles()?.forEach { it.delete() }; changes.update { it + 1 } }
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
            coroutineScope {
                launch { MediaPosters.load(context,media)?.let { value=it } }
                val frame=MediaThumbnails.load(context,media)
                if(value==null)value=frame
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
