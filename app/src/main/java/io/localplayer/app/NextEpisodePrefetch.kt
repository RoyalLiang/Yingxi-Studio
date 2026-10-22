package io.localplayer.app

import android.content.Context
import androidx.media3.common.Player
import io.localplayer.core.Protocol
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

internal fun shouldPrefetchNext(position:Long,duration:Long,buffered:Long):Boolean =
    duration>0&&position>=duration-duration/5&&buffered>=15_000

/** One bounded warm-up, owned by the active service; never a background download task. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NextEpisodePrefetch(private val context:Context):java.io.Closeable {
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"next-episode-cache").apply{isDaemon=true} }
    private val active=AtomicReference<PlaybackLease?>()
    private var future:Future<*>?=null
    private var attempted:String?=null
    fun consider(player:Player) {
        val prefs=CloudReadPreferences(context)
        if(!player.isPlaying||!prefs.nextEpisode||!prefs.cacheEnabled||!prefs.unmeteredWifi()) {cancel();return}
        if(!player.hasNextMediaItem()||!shouldPrefetchNext(player.currentPosition,player.duration,player.totalBufferedDuration)){cancel();return}
        val item=player.getMediaItemAt(player.nextMediaItemIndex)
        val uri=item.localConfiguration?.uri?:return
        if(uri.scheme!="localplayer"||attempted==item.mediaId)return
        val target=runCatching{PlaybackRegistry.get(uri)}.getOrNull()?:return
        if(target.source.protocol!=Protocol.ALIYUN)return
        attempted=item.mediaId
        future=worker.submit {
            val lease=PlaybackLease(target.source,target.credentials,target.path,AliyunCloud.connector(target.source,1))
            active.set(lease)
            try {
                lease.open().use { file ->
                    // Without a provider content hash another session cannot safely reuse bytes.
                    if(!file.reusableCache)return@use
                    val limit=minOf(file.size,1024L*1024)
                    val buffer=ByteArray(64*1024);var at=0L
                    while(at<limit) {
                        if(Thread.currentThread().isInterrupted)break
                        val n=file.readAt(at,buffer,0,minOf(buffer.size.toLong(),limit-at).toInt())
                        if(n<=0)break
                        at+=n
                    }
                }
            }catch(_:Exception){ /* Pre-reading never changes the active player's result. */ }
            finally {active.compareAndSet(lease,null);lease.close()}
        }
    }
    fun cancel(){future?.cancel(true);future=null;active.getAndSet(null)?.let(PlaybackRegistry::closeAsync)}
    fun transition(){cancel();attempted=null}
    override fun close(){cancel();worker.shutdownNow()}
}
