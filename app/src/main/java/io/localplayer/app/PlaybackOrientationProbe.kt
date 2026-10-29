package io.localplayer.app

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.SystemClock
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
    private const val MAX_CACHE_RECORDS=64
    private val cacheLock=Any()
    private const val CACHE_AGE_MS=24L*60*60*1000
    private fun preferences(context:Context)=context.applicationContext.getSharedPreferences("video-orientation-v1",Context.MODE_PRIVATE)
    private fun recordedAt(value:Any?)=runCatching{org.json.JSONObject(value as String).getLong("recordedAt")}.getOrDefault(0L)
    fun remember(context:Context,media:MediaRef,orientation:Int){
        if(runCatching{MediaDataMigration.session(context).checkLocator(media)}.isFailure||SealedMedia.protects(media)||(media.sizeBytes?:0)<=0||orientation !in setOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))return
        synchronized(cacheLock){
            if(cached(context,media)==orientation)return
            val prefs=preferences(context);val id=enrichmentKey(media)
            val others=prefs.all.filterKeys{it!=id}
            val edit=prefs.edit().putString(id,org.json.JSONObject().put("orientation",orientation).put("recordedAt",System.currentTimeMillis()).toString())
            others.entries.sortedBy{recordedAt(it.value)}.take((others.size-(MAX_CACHE_RECORDS-1)).coerceAtLeast(0)).forEach{edit.remove(it.key)}
            edit.apply()
        }
    }

    fun cached(context:Context,media:MediaRef):Int?{
        if(runCatching{MediaDataMigration.session(context).checkLocator(media)}.isFailure||SealedMedia.protects(media)||(media.sizeBytes?:0)<=0)return null
        return runCatching{
            val record=org.json.JSONObject(preferences(context).getString(enrichmentKey(media),null)?:return null)
            if(System.currentTimeMillis()-record.getLong("recordedAt") !in 0 until CACHE_AGE_MS)return null
            record.getInt("orientation").takeIf{it in setOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)}
        }.getOrNull()
    }
    fun remove(context:Context,media:MediaRef){synchronized(cacheLock){preferences(context).edit().remove(enrichmentKey(media)).apply()}}
    fun clear(context:Context){synchronized(cacheLock){preferences(context).edit().clear().apply()}}

    // Unknown network metadata is read by the player's own transport before showing its controls.
    suspend fun initial(context:Context,target:PlayTarget):Int? {
        if(runCatching{verify(context,target)}.isFailure)return null
        return cached(context,target.media) ?: (if(target.uri.isPlayerNetworkUri()||target.uri.scheme in setOf("http","https"))null else withTimeoutOrNull(150L){resolve(context,target)})
    }

    private fun verify(context:Context,target:PlayTarget){
        MediaDataMigration.session(context).checkLocator(target.media)
        if(target.uri.isPlayerNetworkUri())PlaybackRegistry.get(target.uri)
    }
    suspend fun resolve(context:Context,target:PlayTarget):Int? {
        if(runCatching{verify(context,target)}.isFailure)return null
        cached(context,target.media)?.let{return it}

        return withTimeoutOrNull(TIMEOUT_MS){
            suspendCancellableCoroutine { continuation->
                val cancellation=Cancellation()
                val deadline=SystemClock.elapsedRealtime()+TIMEOUT_MS
                val future=runCatching{workers.submit{
                    val value=read(context.applicationContext,target,cancellation,deadline)?.takeIf{runCatching{verify(context,target)}.isSuccess}
                    if(value!=null&&continuation.isActive)remember(context,target.media,value)

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
            cancellation.check();verify(context,target)
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
                        cancellation.check();preview?.checkCurrent();verify(context,target)
                        if(SystemClock.elapsedRealtime()>=deadline||bytes>=MAX_BYTES)throw IOException("Video metadata budget exceeded")
                        if(position<0)throw IOException("Invalid video metadata position")
                        if(size==0)return 0
                        if(position>=file.size)return -1
                        val count=minOf(size.toLong(),file.size-position,128L*1024,MAX_BYTES-bytes).toInt()
                        return file.readAt(position,buffer,offset,count).also{preview?.checkCurrent();verify(context,target);if(it>0)bytes+=it}

                    }
                    override fun getSize():Long {preview?.checkCurrent();verify(context,target);return file.size.also{preview?.checkCurrent();verify(context,target)}}
                    override fun close(){file.close()}
                })
            }else retriever.setDataSource(context,target.uri)
            cancellation.check();preview?.checkCurrent();verify(context,target)
            val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:return null
            val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:return null
            val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
            preview?.checkCurrent();verify(context,target)
            return videoOrientation(width,height,rotation,1f)

        }catch(_:Exception){return null}
        finally{
            runCatching{retriever?.release()}
            cancellation.close()
            runCatching{preview?.close()}
        }
    }
}
