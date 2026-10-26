package io.localplayer.app

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.media3.common.VideoSize
import io.localplayer.core.Cancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Read metadata before opening a player window. Never decode a thumbnail or borrow playback I/O. */
internal object PlaybackOrientationProbe {
    private const val TIMEOUT_MS=1500L
    private const val MAX_BYTES=4L*1024*1024
    private val workers=ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,SynchronousQueue(),{ task->Thread(task,"Yingxi-video-metadata").apply{isDaemon=true} })
    private val closer=Executors.newSingleThreadExecutor{task->Thread(task,"Yingxi-metadata-close").apply{isDaemon=true}}
    private val known=object:LinkedHashMap<String,Int>(64,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Int>?)=size>64}
    private fun key(media:MediaRef)=listOf(media.key,media.location,media.sizeBytes,media.localUri).joinToString("\u0000")
    fun remember(media:MediaRef,orientation:Int)=synchronized(known){known[key(media)]=orientation;Unit}

    fun cached(media:MediaRef):Int?=synchronized(known){known[key(media)]}

    suspend fun resolve(context:Context,target:PlayTarget):Int? {
        cached(target.media)?.let{return it}
        return withTimeoutOrNull(TIMEOUT_MS){
            suspendCancellableCoroutine { continuation->
                val cancellation=Cancellation()
                val deadline=SystemClock.elapsedRealtime()+TIMEOUT_MS
                val future=runCatching{workers.submit{
                    val value=read(context.applicationContext,target,cancellation,deadline)
                    if(value!=null&&continuation.isActive)remember(target.media,value)
                    if(continuation.isActive)continuation.resume(value)
                }}.getOrNull()
                if(future==null){continuation.resume(null);return@suspendCancellableCoroutine}
                continuation.invokeOnCancellation{
                    future.cancel(true)
                    closer.execute{cancellation.close()}
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun read(context:Context,target:PlayTarget,cancellation:Cancellation,deadline:Long):Int? {
        var retriever:MediaMetadataRetriever?=null
        var preview:PlaybackLease?=null
        try {
            cancellation.check()
            retriever=MediaMetadataRetriever()
            if(target.uri.isPlayerNetworkUri()){
                preview=PlaybackRegistry.preview(target.uri)
                cancellation.own(preview)
                var bytes=0L
                val raw=preview.open()
                val file=io.localplayer.core.ReadAheadFile(object:io.localplayer.core.RemoteFile by raw {
                    override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                        cancellation.check()
                        if(SystemClock.elapsedRealtime()>=deadline||bytes>=MAX_BYTES)throw IOException("Video metadata probe budget exceeded")
                        return raw.readAt(position,buffer,offset,minOf(length.toLong(),MAX_BYTES-bytes).toInt()).also{if(it>0)bytes+=it}
                    }
                },64*1024)
                cancellation.own(file)
                if(target.media.sizeBytes!=null&&file.size!=target.media.sizeBytes)return null
                retriever.setDataSource(object:MediaDataSource(){
                    @Synchronized override fun readAt(position:Long,buffer:ByteArray,offset:Int,size:Int):Int {
                        cancellation.check()
                        if(SystemClock.elapsedRealtime()>=deadline||bytes>=MAX_BYTES)throw IOException("Video metadata budget exceeded")
                        if(position<0)throw IOException("Invalid video metadata position")
                        if(size==0)return 0
                        if(position>=file.size)return -1
                        val count=minOf(size.toLong(),file.size-position,128L*1024,MAX_BYTES-bytes).toInt()
                        return file.readAt(position,buffer,offset,count)
                    }
                    override fun getSize()=file.size
                    override fun close(){file.close()}
                })
            }else retriever.setDataSource(context,target.uri)
            cancellation.check()
            val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:return null
            val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:return null
            val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
            return videoOrientation(VideoSize(width,height,rotation,1f))
        }catch(_:Exception){return null}
        finally{
            runCatching{retriever?.release()}
            cancellation.close()
            runCatching{preview?.close()}
        }
    }
}
