package io.localplayer.app

import android.content.Context
import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Stable MediaSession player. Only unsupported, unencrypted DV5 enters the SDR backend. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CompatibilityPlayer(private val context:Context,private val native:ExoPlayer):ForwardingSimpleBasePlayer(native) {
    private val handler=Handler(applicationLooper)
    private var compatibility:MpvPlayer?=null
    private var output:Any?=null
    private var requestedPosition=0L
    private var nativeHasFrame=false
    private var closed=false
    private val nativeListener=object:Player.Listener {
        override fun onTracksChanged(tracks:Tracks){considerFallback()}
        override fun onMediaItemTransition(item:MediaItem?,reason:Int){requestedPosition=native.currentPosition.coerceAtLeast(0);nativeHasFrame=false}
        override fun onRenderedFirstFrame(){nativeHasFrame=true}
        override fun onPlayerError(error:PlaybackException){considerFallback()}
    }
    init {native.addListener(nativeListener)}
    private fun videoFormat():Format? = native.videoFormat?:native.currentTracks.groups.firstOrNull{it.type==C.TRACK_TYPE_VIDEO}?.getTrackFormat(0)
    private fun considerFallback(){
        if(closed||player!==native)return
        val format=videoFormat()?:return
        if(!needsDv5Sdr(format,native.currentTracks,native.playerError))return
        val item=native.currentMediaItem?:return
        val queue=(0 until native.mediaItemCount).map(native::getMediaItemAt)
        val index=native.currentMediaItemIndex
        val play=native.playWhenReady
        val start=if(nativeHasFrame)native.currentPosition.coerceAtLeast(0)else requestedPosition
        native.pause() // No audio-only clock or history advancement while opening video.
        handler.post {
            if(closed||player!==native||native.currentMediaItem?.mediaId!=item.mediaId)return@post
            val replacement=MpvPlayer(context,format)
            replacement.setMediaItems(queue,index,start)
            replacement.playbackParameters=native.playbackParameters
            replacement.volume=native.volume
            replacement.trackSelectionParameters=native.trackSelectionParameters
            replacement.addListener(object:Player.Listener{
                override fun onPlaybackStateChanged(state:Int){
                    if(state==Player.STATE_ENDED&&replacement.hasNextMediaItem()){
                        handler.post{if(!closed&&player===replacement){toNative(queue,index+1,0,true)}}
                    }
                }
            })
            native.clearVideoSurface();native.stop()
            compatibility=replacement
            setPlayer(replacement)
            output?.let{applyOutput(replacement,it)}
            PlaybackMetrics.sdrStarted()
            replacement.prepare();replacement.playWhenReady=play
        }
    }
    private fun toNative(items:List<MediaItem>,index:Int,position:Long,play:Boolean){
        requestedPosition=position.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        val previous=compatibility
        val speed=player.playbackParameters;val volume=player.volume;val selection=player.trackSelectionParameters
        previous?.pause();previous?.clearVideoSurface()
        // Populate before swapping: listeners must always see the complete original queue.
        native.setMediaItems(items,index,requestedPosition)
        native.playbackParameters=speed;native.volume=volume;native.trackSelectionParameters=selection
        setPlayer(native);compatibility=null
        output?.let{applyOutput(native,it)}
        previous?.release()
        native.prepare();native.playWhenReady=play
    }
    override fun handleSetMediaItems(mediaItems:List<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<*> {
        requestedPosition=startPositionMs.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        if(compatibility!=null){toNative(mediaItems,startIndex.coerceAtLeast(0),requestedPosition,player.playWhenReady);return done()}
        return super.handleSetMediaItems(mediaItems,startIndex,startPositionMs)
    }
    override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*> {
        requestedPosition=positionMs.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        if(compatibility!=null&&mediaItemIndex!=currentMediaItemIndex){
            toNative((0 until mediaItemCount).map(::getMediaItemAt),mediaItemIndex,requestedPosition,playWhenReady);return done()
        }
        return super.handleSeek(mediaItemIndex,positionMs,seekCommand)
    }
    private fun restoreNative(){
        if(compatibility!=null)toNative((0 until mediaItemCount).map(::getMediaItemAt),currentMediaItemIndex,currentPosition,playWhenReady)
    }
    override fun handleAddMediaItems(index:Int,mediaItems:List<MediaItem>):ListenableFuture<*>{restoreNative();return super.handleAddMediaItems(index,mediaItems)}
    override fun handleRemoveMediaItems(fromIndex:Int,toIndex:Int):ListenableFuture<*>{restoreNative();return super.handleRemoveMediaItems(fromIndex,toIndex)}
    override fun handleMoveMediaItems(fromIndex:Int,toIndex:Int,newIndex:Int):ListenableFuture<*>{restoreNative();return super.handleMoveMediaItems(fromIndex,toIndex,newIndex)}
    override fun handleReplaceMediaItems(fromIndex:Int,toIndex:Int,mediaItems:List<MediaItem>):ListenableFuture<*>{restoreNative();return super.handleReplaceMediaItems(fromIndex,toIndex,mediaItems)}
    override fun handleSetVideoOutput(videoOutput:Any):ListenableFuture<*> {output=videoOutput;return super.handleSetVideoOutput(videoOutput)}
    override fun handleClearVideoOutput(videoOutput:Any?):ListenableFuture<*> {
        if(videoOutput==null||videoOutput===output)output=null
        return super.handleClearVideoOutput(videoOutput)
    }
    override fun handleRelease():ListenableFuture<*> {
        closed=true;handler.removeCallbacksAndMessages(null);native.removeListener(nativeListener)
        compatibility?.release();compatibility=null;native.release();output=null
        return done()
    }
    private fun applyOutput(target:Player,value:Any){when(value){
        is SurfaceView->target.setVideoSurfaceView(value)
        is TextureView->target.setVideoTextureView(value)
        is SurfaceHolder->target.setVideoSurfaceHolder(value)
        is Surface->target.setVideoSurface(value)
    }}
    private fun done():ListenableFuture<*> = Futures.immediateVoidFuture()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun needsDv5Sdr(format:Format,tracks:Tracks,error:PlaybackException?):Boolean {
    val decoderFailed=error?.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
    if(error!=null&&!decoderFailed)return false
    return format.drmInitData==null && format.sampleMimeType==MimeTypes.VIDEO_DOLBY_VISION &&
        format.codecs?.split(',')?.any{Regex("(?:dvhe|dvh1)\\.0?5(?:\\..*)?",RegexOption.IGNORE_CASE).matches(it.trim())}==true &&
        (unsupportedVideo(tracks)||decoderFailed)
}
