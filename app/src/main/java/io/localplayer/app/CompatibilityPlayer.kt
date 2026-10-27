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

/** Stable MediaSession player. Unsupported cleartext formats share the existing compatibility backend. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CompatibilityPlayer(private val context:Context,private val native:ExoPlayer):ForwardingSimpleBasePlayer(native) {
    companion object { val VIDEO_LAYOUT=androidx.media3.session.SessionCommand("io.localplayer.VIDEO_LAYOUT",android.os.Bundle.EMPTY) }
    private var fillVideo=false
    private var subtitleMarginX=25
    private var subtitleMarginY=22
    internal fun setVideoLayout(fill:Boolean,marginX:Int,marginY:Int) {
        fillVideo=fill;subtitleMarginX=marginX.coerceIn(0,360);subtitleMarginY=marginY.coerceIn(0,360)
        compatibility?.setVideoLayout(fillVideo,subtitleMarginX,subtitleMarginY)
    }

    private val handler=Handler(applicationLooper)
    private var compatibility:MpvPlayer?=null
    private var output:Any?=null
    private var requestedPosition=0L
    private var nativeHasFrame=false
    private var recoveringAttachments=false
    private var closed=false
    private var pendingCaption:Pair<Int,MediaItem>?=null
    private var pendingTextParameters:TrackSelectionParameters?=null
    private fun discardPendingCaption(){
        val parameters=pendingTextParameters;pendingCaption=null;pendingTextParameters=null
        if(parameters!=null)native.trackSelectionParameters=parameters
    }
    private fun attachedGroup()=native.currentTracks.groups.firstOrNull{it.mediaTrackGroup.id.substringAfterLast(':').startsWith(MutableSubtitleSource.PREFIX)}
    override fun getState():State {
        val state=super.getState()
        if(player!==native)return state
        val captions=MutableSubtitleSource.captions(native.currentMediaItem)
        val groups=native.currentTracks.groups.flatMap { group ->
            if(!group.mediaTrackGroup.id.substringAfterLast(':').startsWith(MutableSubtitleSource.PREFIX))listOf(group) else captions.mapIndexed { i,caption ->
                val track=MutableSubtitleSource.index(caption,i)
                Tracks.Group(TrackGroup("${group.mediaTrackGroup.id}:caption:$i",MutableSubtitleSource.input(caption)),false,
                    intArrayOf(group.getTrackSupport(track)),booleanArrayOf(group.isTrackSelected(track)))
            }
        }
        return state.buildUpon().setPlaylist(native.currentTimeline,Tracks(com.google.common.collect.ImmutableList.copyOf(groups)),native.mediaMetadata)
            .setTrackSelectionParameters(pendingTextParameters?:native.trackSelectionParameters).build()
    }
    private fun selectCaptions(parameters:TrackSelectionParameters,item:MediaItem?=native.currentMediaItem) {
        val group=attachedGroup()?.mediaTrackGroup?:run{native.trackSelectionParameters=parameters;return}
        val captions=MutableSubtitleSource.captions(item)
        if(parameters.overrides.values.any{it.type==C.TRACK_TYPE_TEXT&&it.mediaTrackGroup!=group&&!it.mediaTrackGroup.id.startsWith(group.id+":caption:")}){
            native.trackSelectionParameters=parameters;return
        }
        val explicit=parameters.overrides.values.firstOrNull{it.mediaTrackGroup.id.startsWith(group.id+":caption:")}
            ?.mediaTrackGroup?.id?.substringAfterLast(':')?.toIntOrNull()
        val nativeOverride=parameters.overrides[group]
        val chosen=explicit?:nativeOverride?.trackIndices?.firstOrNull()?.div(2)?.takeIf{it<captions.size}
            ?:captions.indexOfFirst{c->parameters.preferredTextLanguages.any{normalizedTrackLanguage(it)==normalizedTrackLanguage(c.language)}}.takeIf{it>=0}
            ?:captions.indexOfFirst{it.selectionFlags and C.SELECTION_FLAG_DEFAULT!=0}.takeIf{it>=0}
            ?:0.takeIf{captions.isNotEmpty()&&!native.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT&&it.isSelected&&!it.mediaTrackGroup.id.substringAfterLast(':').startsWith(MutableSubtitleSource.PREFIX)}}
        val updated=parameters.buildUpon()
        if(explicit!=null)updated.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if(chosen!=null&&chosen<captions.size)updated.setOverrideForType(TrackSelectionOverride(group,MutableSubtitleSource.index(captions[chosen],chosen)))
        else if(nativeOverride!=null){
            updated.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            native.currentTracks.groups.firstOrNull{it.type==C.TRACK_TYPE_TEXT&&it.mediaTrackGroup!=group&&it.isSupported}
                ?.let{updated.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup,0))}
        }
        val next=updated.build()
        if(next!=native.trackSelectionParameters)native.trackSelectionParameters=next
    }
    private fun applyPendingCaption(){
        val pending=pendingCaption?:return
        val item=native.currentMediaItem
        if(native.currentMediaItemIndex!=pending.first||item?.mediaId!=pending.second.mediaId||item.localConfiguration?.uri!=pending.second.localConfiguration?.uri||
            item.mediaMetadata.extras?.getString("play-intent")!=pending.second.mediaMetadata.extras?.getString("play-intent")){discardPendingCaption();return}
        if(native.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT&&it.isSelected})return
        val parameters=pendingTextParameters?:native.trackSelectionParameters
        pendingCaption=null;pendingTextParameters=null
        native.replaceMediaItem(pending.first,pending.second)
        selectCaptions(parameters,pending.second)
    }
    private val nativeListener=object:Player.Listener {
        override fun onTracksChanged(tracks:Tracks){considerFallback();if(player===native){if(pendingCaption!=null)applyPendingCaption()else selectCaptions(native.trackSelectionParameters)}}
        override fun onPlaybackStateChanged(state:Int){considerFallback()}
        override fun onMediaItemTransition(item:MediaItem?,reason:Int){discardPendingCaption();requestedPosition=native.currentPosition.coerceAtLeast(0);nativeHasFrame=false}
        override fun onRenderedFirstFrame(){nativeHasFrame=true}
        override fun onPlayerError(error:PlaybackException){considerFallback()}
    }
    init {native.addListener(nativeListener)}
    private fun videoFormat():Format? = native.videoFormat?:native.currentTracks.groups.firstOrNull{it.type==C.TRACK_TYPE_VIDEO}?.getTrackFormat(0)
    private fun retryWithoutAttachments(error:PlaybackException):Boolean {
        val item=native.currentMediaItem?:return false
        if(player!==native||error !is androidx.media3.exoplayer.ExoPlaybackException||error.type!=androidx.media3.exoplayer.ExoPlaybackException.TYPE_SOURCE)return false
        if(SidecarAudio.from(item).isEmpty()&&item.localConfiguration?.subtitleConfigurations.isNullOrEmpty())return false
        if(recoveringAttachments)return true
        recoveringAttachments=true
        handler.post {
            recoveringAttachments=false
            if(closed||player!==native||native.playerError!==error||native.currentMediaItem!=item)return@post
            val index=native.currentMediaItemIndex;val position=native.currentPosition.coerceAtLeast(0);val play=native.playWhenReady
            val clean=SidecarAudio.attach(item,emptyList()).buildUpon().setSubtitleConfigurations(emptyList()).build()
            val queue=(0 until native.mediaItemCount).map{if(it==index)clean else native.getMediaItemAt(it)}
            AutomaticSidecars.status.value=item.mediaId to "外挂轨道无法读取，已继续播放主片；可重新选择文件"
            native.trackSelectionParameters=native.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            native.setMediaItems(queue,index,position);native.prepare();native.playWhenReady=play
        }
        return true
    }
    private fun considerFallback(){
        if(closed||player!==native)return
        native.playerError?.let{if(retryWithoutAttachments(it))return}
        val format=videoFormat()
        if(!needsCompatibility(format,native.currentTracks,native.playerError,
            native.playbackState==Player.STATE_ENDED&&native.currentTracks.groups.isEmpty()&&!nativeHasFrame))return
        val item=native.currentMediaItem?:return
        if(item.localConfiguration?.drmConfiguration!=null)return
        val queue=(0 until native.mediaItemCount).map(native::getMediaItemAt)
        val index=native.currentMediaItemIndex
        val play=native.playWhenReady
        val start=if(nativeHasFrame)native.currentPosition.coerceAtLeast(0)else requestedPosition
        native.pause() // No audio-only clock or history advancement while opening video.
        handler.post {
            if(closed||player!==native||native.currentMediaItem?.mediaId!=item.mediaId)return@post
            val replacement=MpvPlayer(context,format?:Format.Builder().setSampleMimeType("video/x-unknown").build())
            replacement.setVideoLayout(fillVideo,subtitleMarginX,subtitleMarginY)
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
            PlaybackMetrics.sdrStarted(format?.sampleMimeType==MimeTypes.VIDEO_DOLBY_VISION)
            replacement.prepare();replacement.playWhenReady=play
        }
    }
    private fun toNative(items:List<MediaItem>,index:Int,position:Long,play:Boolean){
        requestedPosition=position.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        val previous=compatibility
        val speed=player.playbackParameters;val volume=player.volume;val selection=player.trackSelectionParameters
        previous?.pause();previous?.clearVideoSurface()
        // Populate before swapping: listeners must always see the complete original queue.
        native.setMediaItems(items.map{softwareSdrItem(it,false)},index,requestedPosition)
        native.playbackParameters=speed;native.volume=volume;native.trackSelectionParameters=selection
        setPlayer(native);compatibility=null
        output?.let{value->if(previous!=null)previous.afterVideoDetached{if(!closed&&player===native&&output===value)applyOutput(native,value)}else applyOutput(native,value)}
        previous?.release()
        native.prepare();native.playWhenReady=play
    }
    override fun handleSetMediaItems(mediaItems:List<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<*> {
        discardPendingCaption()
        requestedPosition=startPositionMs.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        if(compatibility!=null){toNative(mediaItems,startIndex.coerceAtLeast(0),requestedPosition,player.playWhenReady);return done()}
        return super.handleSetMediaItems(mediaItems.map{softwareSdrItem(it,false)},startIndex,startPositionMs)
    }
    override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*> {
        discardPendingCaption()
        requestedPosition=positionMs.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        if(compatibility!=null&&mediaItemIndex!=currentMediaItemIndex){
            toNative((0 until mediaItemCount).map(::getMediaItemAt),mediaItemIndex,requestedPosition,playWhenReady);return done()
        }
        return super.handleSeek(mediaItemIndex,positionMs,seekCommand)
    }
    private fun restoreNative(){
        if(compatibility!=null)toNative((0 until mediaItemCount).map(::getMediaItemAt),currentMediaItemIndex,currentPosition,playWhenReady)
    }
    override fun handleAddMediaItems(index:Int,mediaItems:List<MediaItem>):ListenableFuture<*>{discardPendingCaption();restoreNative();return super.handleAddMediaItems(index,mediaItems)}
    override fun handleRemoveMediaItems(fromIndex:Int,toIndex:Int):ListenableFuture<*>{discardPendingCaption();restoreNative();return super.handleRemoveMediaItems(fromIndex,toIndex)}
    override fun handleMoveMediaItems(fromIndex:Int,toIndex:Int,newIndex:Int):ListenableFuture<*>{discardPendingCaption();restoreNative();return super.handleMoveMediaItems(fromIndex,toIndex,newIndex)}
    override fun handleReplaceMediaItems(fromIndex:Int,toIndex:Int,mediaItems:List<MediaItem>):ListenableFuture<*> {
        val replacement=mediaItems.singleOrNull()
        val current=currentMediaItem
        if(fromIndex==currentMediaItemIndex&&toIndex==fromIndex+1&&replacement!=null&&current!=null&&
            replacement.mediaId==current.mediaId&&replacement.localConfiguration?.uri==current.localConfiguration?.uri&&
            replacement.mediaMetadata.extras?.getString("play-intent")==current.mediaMetadata.extras?.getString("play-intent")) {
            val items=(0 until mediaItemCount).map{if(it==fromIndex)replacement else getMediaItemAt(it)}
            compatibility?.let{it.updateAttachments(items);return done()}
            if(SidecarAudio.from(current)==SidecarAudio.from(replacement)){
                if(current.localConfiguration?.subtitleConfigurations!=replacement.localConfiguration?.subtitleConfigurations){
                    pendingCaption=fromIndex to replacement
                    if(pendingTextParameters==null)pendingTextParameters=native.trackSelectionParameters
                    native.trackSelectionParameters=native.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                    applyPendingCaption()
                }else native.replaceMediaItem(fromIndex,replacement)
            }else {
                // A user changing an audio source requires a new period; keep the rest of the queue.
                val position=native.currentPosition;val play=native.playWhenReady
                native.replaceMediaItem(fromIndex,replacement);native.seekTo(fromIndex,position);native.playWhenReady=play
            }
            return done()
        }
        restoreNative();return super.handleReplaceMediaItems(fromIndex,toIndex,mediaItems)
    }
    override fun handleSetVideoOutput(videoOutput:Any):ListenableFuture<*> {
        if(compatibility!=null&&videoOutput !is TextureView)return done()
        output=videoOutput
        return super.handleSetVideoOutput(videoOutput)
    }
    internal fun attachTexture(view:TextureView){if(compatibility!=null&&output!==view)setVideoTextureView(view)}
    override fun handleSetTrackSelectionParameters(parameters:TrackSelectionParameters):ListenableFuture<*> {
        if(player!==native)return super.handleSetTrackSelectionParameters(parameters)
        if(pendingCaption!=null)pendingTextParameters=parameters else selectCaptions(parameters)
        return done()
    }
    override fun handleClearVideoOutput(videoOutput:Any?):ListenableFuture<*> {
        if(compatibility!=null&&output is TextureView&&videoOutput !is TextureView)return done()
        if(videoOutput==null||videoOutput===output)output=null
        return super.handleClearVideoOutput(videoOutput)
    }
    override fun handleRelease():ListenableFuture<*> {
        CompatibilityVideoOutput.unregister(this)
        closed=true;handler.removeCallbacksAndMessages(null);native.removeListener(nativeListener)
        compatibility?.release();compatibility=null;native.release();output=null
        return done()
    }
    override fun handleStop():ListenableFuture<*>{discardPendingCaption();return super.handleStop()}
    private fun applyOutput(target:Player,value:Any){when(value){
        is SurfaceView->if(target !is MpvPlayer)target.setVideoSurfaceView(value)
        is TextureView->target.setVideoTextureView(value)
        is SurfaceHolder->if(target !is MpvPlayer)target.setVideoSurfaceHolder(value)
        is Surface->if(target !is MpvPlayer)target.setVideoSurface(value)
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun needsCompatibility(format:Format?,tracks:Tracks,error:PlaybackException?,emptyVideo:Boolean=false):Boolean {
    if(format?.drmInitData!=null)return false
    // Both bundled dav1d builds trap on the tested ARM64 10-bit path; keep AV1 on MediaCodec.
    if(format?.sampleMimeType==MimeTypes.VIDEO_AV1)return false
    if(error==null&&emptyVideo)return true
    if(format!=null&&needsDv5Sdr(format,tracks,error))return true
    if(error?.errorCode==PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)return true
    if(error?.errorCode==PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED&&
        generateSequence(error.cause){it.cause}.filterIsInstance<ParserException>()
            .any{it.message?.startsWith("No valid tracks were found")==true})return true
    val decodeFailure=error?.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
    if(error!=null&&!decodeFailure)return false
    // Use the color-managed backend for formats outside the tested NextLib video routes.
    return format?.sampleMimeType in setOf(MimeTypes.VIDEO_MPEG,MimeTypes.VIDEO_MP4V,MimeTypes.VIDEO_H263,
        "video/wvc1","video/vc1","video/x-ms-wmv","video/mjpeg","video/x-vnd.on2.vp6","video/theora") &&
        (unsupportedVideo(tracks)||decodeFailure)
}
