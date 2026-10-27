package io.localplayer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import io.localplayer.core.Cancellation
import java.util.concurrent.Executors

/** Discover before the first period, including queued episodes; automatic audio never reopens video. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PreparingSidecarSource(private val context:Context,private var item:MediaItem,
    private val media:MediaRef,private val create:(MediaItem)->MediaSource):CompositeMediaSource<Unit>() {
    companion object {
        private val workers=Executors.newFixedThreadPool(2)
        private val closer=Executors.newCachedThreadPool()
        private const val AUTO="automatic-sidecar-audio"
    }
    private var child:MediaSource?=null
    private var cancellation:Cancellation?=null
    private var future:java.util.concurrent.Future<*>?=null
    private var epoch=0
    override fun getMediaItem()=item
    override fun canUpdateMediaItem(mediaItem:MediaItem)=child?.canUpdateMediaItem(mediaItem)?:
        (item.localConfiguration?.uri==mediaItem.localConfiguration?.uri&&SidecarAudio.from(item)==SidecarAudio.from(mediaItem))
    override fun updateMediaItem(mediaItem:MediaItem){item=mediaItem;child?.updateMediaItem(mediaItem)}
    override fun prepareSourceInternal(mediaTransferListener:TransferListener?){
        super.prepareSourceInternal(mediaTransferListener)
        val handler=Handler(Looper.myLooper()!!);val current=++epoch
        val original=item;val c=Cancellation();cancellation=c
        val timeout=EnrichmentDeadlines.timer.schedule({closer.execute{c.close()}},8,java.util.concurrent.TimeUnit.SECONDS)
        future=workers.submit {
            var found:SidecarSubtitles.Discovery?=null
            try {
                if(!SealedMedia.protects(media)||SealedMedia.unlocked)
                    found=SidecarSubtitles.discover(context,media,original.localConfiguration!!.uri,c,OnlineSubtitles.provider,EnrichmentPreferences(context).bilingual)
            }catch(_:Exception){}finally{timeout.cancel(false);c.close()}
            val result=found
            handler.post {
                if(epoch!=current){result?.registered?.forEach(PlaybackRegistry::remove);return@post}
                if(result!=null){
                    AutomaticSidecars.register(item,result.registered)
                    val captions=result.subtitles.mapIndexed { i,file ->
                        val mime=when(file.extension){"ass","ssa"->MimeTypes.TEXT_SSA;"vtt"->MimeTypes.TEXT_VTT;else->MimeTypes.APPLICATION_SUBRIP}
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file)).setMimeType(mime).setLabel(result.subtitleNames[i])
                            .setLanguage(sidecarLanguage(result.subtitleNames[i])).setSelectionFlags(if(i==0&&item.localConfiguration?.subtitleConfigurations.isNullOrEmpty())C.SELECTION_FLAG_DEFAULT else 0).build()
                    }
                    item=SidecarAudio.attach(item,(SidecarAudio.from(item)+result.audio).distinctBy{it.uri}.take(8))
                    val extras=android.os.Bundle(item.mediaMetadata.extras?:android.os.Bundle()).apply{putStringArrayList(AUTO,ArrayList(result.registered.map{it.toString()}))}
                    item=item.buildUpon()
                        .setSubtitleConfigurations((item.localConfiguration?.subtitleConfigurations.orEmpty()+captions).distinctBy{it.uri})
                        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                    AutomaticSidecars.status.value=item.mediaId to "同目录已载入：字幕 ${captions.size} · 音轨 ${result.audio.size}"
                }
                val extras=android.os.Bundle(item.mediaMetadata.extras?:android.os.Bundle()).apply{putBoolean("sidecars-prepared",true)}
                item=item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                child=create(item)
                prepareChildSource(Unit,checkNotNull(child))
            }
        }
    }
    override fun onChildSourceInfoRefreshed(id:Unit,mediaSource:MediaSource,timeline:Timeline){refreshSourceInfo(timeline)}
    override fun createPeriod(id:MediaSource.MediaPeriodId,allocator:Allocator,startPositionUs:Long)=checkNotNull(child).createPeriod(id,allocator,startPositionUs)
    override fun releasePeriod(mediaPeriod:MediaPeriod){child?.releasePeriod(mediaPeriod)}
    override fun releaseSourceInternal(){
        epoch++;future?.cancel(true);cancellation?.let{closer.execute{it.close()}};cancellation=null
        super.releaseSourceInternal();child=null
    }
}
