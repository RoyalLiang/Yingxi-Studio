package io.localplayer.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** One output reset and one HEVC fallback per episode; audio/network errors never trigger either. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoDecoderRecovery(
    private val player:ExoPlayer,
    private val factory:HdrRenderersFactory,
    private val firstFrameTimeoutMs:Long=6000,
):Player.Listener,AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val selector=player.trackSelector as? DefaultTrackSelector
    private var mediaId=player.currentMediaItem?.mediaId
    private var requestedPosition=player.currentPosition.coerceAtLeast(0)
    private var firstFrame=false
    private var readySince=0L
    private var closed=false
    internal var attempted=false;private set
    internal var outputRecoveryAttempted=false;private set
    internal var reason:String?=null;private set
    private fun awaitingFirstFrame() = !closed && !attempted && !firstFrame && player.playWhenReady &&
        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)
    private val watchdog=object:Runnable {
        override fun run(){
            if(!awaitingFirstFrame()){readySince=0;return}
            val format=videoFormat()
            // Do not mistake a slow source for a hung decoder. Require queued media
            // (or a fully loaded short clip) before timing a buffering renderer.
            val mediaQueued = player.playbackState == Player.STATE_READY || player.totalBufferedDuration >= 1000 ||
                (player.duration > 0 && player.bufferedPosition >= player.duration && player.totalBufferedDuration > 0)
            if(compatible(format) && mediaQueued){
                if(readySince==0L)readySince=SystemClock.elapsedRealtime()
                if(SystemClock.elapsedRealtime()-readySince>=firstFrameTimeoutMs){
                    useSoftware("missing-first-frame",format)
                    return
                }
            }else readySince=0
            handler.postDelayed(this,250)
        }
    }
    init {player.addListener(this)}
    private fun arm(){if(awaitingFirstFrame()&&!handler.hasCallbacks(watchdog))handler.post(watchdog)}
    override fun onEvents(player:Player,events:Player.Events){
        if(awaitingFirstFrame())arm()else{readySince=0;handler.removeCallbacks(watchdog)}
    }
    private fun compatible(format:Format?)=format?.sampleMimeType==MimeTypes.VIDEO_H265&&format.drmInitData==null
    private fun videoFormat():Format?=player.videoFormat?:player.currentTracks.groups.firstOrNull{it.type==C.TRACK_TYPE_VIDEO}?.getTrackFormat(0)
    override fun onMediaItemTransition(item:MediaItem?,reason:Int) {
        if(item?.mediaId==mediaId)return
        mediaId=item?.mediaId;attempted=false;outputRecoveryAttempted=false;this.reason=null;firstFrame=false;readySince=0
        requestedPosition=player.currentPosition.coerceAtLeast(0)
        factory.forceSoftware=false
        handler.removeCallbacks(watchdog)
        if(factory.nativeVideoIndex>=0&&selector?.parameters?.getRendererDisabled(factory.nativeVideoIndex)==true)
            selector.parameters=selector.parameters.buildUpon().setRendererDisabled(factory.nativeVideoIndex,false).build()
        arm()
    }
    override fun onRenderedFirstFrame(){firstFrame=true;readySince=0;handler.removeCallbacks(watchdog)}
    override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){
        readySince=0
        if(reason==Player.DISCONTINUITY_REASON_SEEK||reason==Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
            requestedPosition=newPosition.positionMs.coerceAtLeast(0)
    }
    private fun recoveryPosition()=if(firstFrame)player.currentPosition.coerceAtLeast(0)else requestedPosition
    override fun onPlayerError(error:PlaybackException) {
        val rendererError=error as? ExoPlaybackException?:return
        if(rendererError.rendererIndex!=factory.nativeVideoIndex)return
        android.util.Log.d("YingxiDecoder","native error=${error.errorCodeName} software=$attempted outputReset=$outputRecoveryAttempted")
        if(generateSequence(error.cause){it.cause}.any{it is VideoOutputStateException}&&retryOutput())return
        if(error.errorCode !in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))return
        useSoftware(error.errorCodeName,rendererError.rendererFormat)
    }
    private fun retryOutput():Boolean {
        if(closed||outputRecoveryAttempted||player.currentMediaItem==null)return false
        val index=player.currentMediaItemIndex;val position=recoveryPosition();val playing=player.playWhenReady
        outputRecoveryAttempted=true;firstFrame=false;readySince=0
        android.util.Log.d("YingxiDecoder","reload current output")
        player.stop();player.seekTo(index,position);player.prepare();player.playWhenReady=playing
        return true
    }
    internal fun useSoftware(reason:String="manual",format:Format?=videoFormat()):Boolean {
        if(closed||attempted||selector==null||factory.nativeVideoIndex<0||factory.softwareVideoIndex<0||!compatible(format))return false
        if(player.currentMediaItem==null)return false
        val index=player.currentMediaItemIndex;val position=recoveryPosition();val playing=player.playWhenReady
        attempted=true;this.reason=reason;factory.forceSoftware=true;firstFrame=false;readySince=0
        android.util.Log.d("YingxiDecoder","HEVC software fallback: $reason")
        // Release the failed Surface producer before FFmpeg acquires the same output Surface.
        player.stop()
        selector.parameters=selector.parameters.buildUpon().setRendererDisabled(factory.nativeVideoIndex,true).build()
        player.seekTo(index,position);player.prepare();player.playWhenReady=playing
        // Track reselection can reset the previously inactive renderer after its surface message.
        // Rebind the current output when handing the same session to the software renderer.
        factory.reapplySoftwareOutput(player)
        return true
    }
    override fun close(){closed=true;handler.removeCallbacksAndMessages(null);player.removeListener(this)}
}
