package io.localplayer.app

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Bounded local counters. Never records media paths, addresses or credentials. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackMetrics {
 data class Snapshot(val firstFrameMs:Long?,val rebufferCount:Int,val droppedFrames:Int,
  val bytes:Long,val readCalls:Long,val readTimeMs:Long,val openCalls:Long=0,
  val openTimeMs:Long=0,val maxReadMs:Long=0,val decoder:String?=null,val decoderInitMs:Long=0,val videoInputBuffers:Int?=null,val videoRenderedBuffers:Int?=null,
        val videoSkippedInputs:Int?=null,val videoEnabled:Boolean=false,val videoCodecErrors:Int=0,val videoDecoderReleases:Int=0,val videoSkippedOutputs:Int?=null)
 private var sdr=false
 private var started=0L
 private var firstFrame:Long?=null
 private var played=false
 private var wasBuffering=false
 private var rebuffers=0
 private var dropped=0
 private var bytes=0L
 private var reads=0L
 private var readNanos=0L
 private var opens=0L
 private var openNanos=0L
 private var maxReadNanos=0L
 private var decoder:String?=null
 private var decoderInitMs=0L
 private var bufferingSince=0L
    private var videoCounters:androidx.media3.exoplayer.DecoderCounters?=null
    private var videoEnabled=false
    private var videoCodecErrors=0
    private var videoDecoderReleases=0
 @Synchronized fun reset(){
  sdr=false
  PlaybackIoProbe.reset()
  started=SystemClock.elapsedRealtime();firstFrame=null;played=false;wasBuffering=false
  rebuffers=0;dropped=0;bytes=0;reads=0;readNanos=0;opens=0;openNanos=0
  maxReadNanos=0;decoder=null;decoderInitMs=0;bufferingSince=0
        videoCounters=null;videoEnabled=false;videoCodecErrors=0;videoDecoderReleases=0
 }
 @Synchronized fun sdrStarted(){sdr=true;decoder="mpv.hevc.libplacebo.sdr-bt709";videoEnabled=true;videoCounters=null;firstFrame=null}
 @Synchronized fun sdrFrame(){if(firstFrame==null)firstFrame=SystemClock.elapsedRealtime()-started}
 @Synchronized fun sdrOutput(frames:Int){if(sdr){videoEnabled=true;dropped=frames}}
 @Synchronized fun read(count:Int,nanos:Long){bytes+=count;reads++;readNanos+=nanos;maxReadNanos=maxOf(maxReadNanos,nanos)}
 @Synchronized fun opened(nanos:Long){opens++;openNanos+=nanos}
 @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Synchronized fun snapshot():Snapshot {
        val counters=videoCounters;counters?.ensureUpdated()
        return Snapshot(firstFrame,rebuffers,dropped,bytes,reads,readNanos/1_000_000,opens,openNanos/1_000_000,maxReadNanos/1_000_000,decoder,decoderInitMs,
            counters?.queuedInputBufferCount,counters?.renderedOutputBufferCount,counters?.skippedInputBufferCount,videoEnabled,videoCodecErrors,videoDecoderReleases,videoCounters?.skippedOutputBufferCount)
    }
 @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
 fun listener()=object:AnalyticsListener{
        override fun onVideoEnabled(eventTime:AnalyticsListener.EventTime,counters:androidx.media3.exoplayer.DecoderCounters){synchronized(this@PlaybackMetrics){videoCounters=counters;videoEnabled=true}}
        override fun onVideoDisabled(eventTime:AnalyticsListener.EventTime,counters:androidx.media3.exoplayer.DecoderCounters){synchronized(this@PlaybackMetrics){if(videoCounters===counters)videoEnabled=false}}
        override fun onVideoCodecError(eventTime:AnalyticsListener.EventTime,error:Exception){synchronized(this@PlaybackMetrics){videoCodecErrors++}}
        override fun onVideoDecoderReleased(eventTime:AnalyticsListener.EventTime,name:String){synchronized(this@PlaybackMetrics){videoDecoderReleases++}}

  override fun onMediaItemTransition(eventTime:AnalyticsListener.EventTime,mediaItem:MediaItem?,reason:Int){reset()}
  override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,name:String,initializedTimestampMs:Long,initializationDurationMs:Long){
   synchronized(this@PlaybackMetrics){decoder=name;decoderInitMs=initializationDurationMs}
   android.util.Log.i("YingxiPlayback","decoder=$name initMs=$initializationDurationMs")
  }
  override fun onPlayWhenReadyChanged(eventTime:AnalyticsListener.EventTime,playWhenReady:Boolean,reason:Int){
   android.util.Log.i("YingxiPlayback","playWhenReady=$playWhenReady reason=$reason")
  }
  override fun onPlaybackSuppressionReasonChanged(eventTime:AnalyticsListener.EventTime,reason:Int){
   android.util.Log.i("YingxiPlayback","suppressionReason=$reason")
  }
  override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long){
   synchronized(this@PlaybackMetrics){if(firstFrame==null&&started>0)firstFrame=SystemClock.elapsedRealtime()-started;played=true}
  }
  override fun onPlaybackStateChanged(eventTime:AnalyticsListener.EventTime,state:Int){
   synchronized(this@PlaybackMetrics){
    if(state==Player.STATE_BUFFERING&&!wasBuffering&&played)rebuffers++
    if(state==Player.STATE_BUFFERING&&!wasBuffering)bufferingSince=SystemClock.elapsedRealtime()
    val waited=if(bufferingSince>0)SystemClock.elapsedRealtime()-bufferingSince else 0
    wasBuffering=state==Player.STATE_BUFFERING
    android.util.Log.i("YingxiPlayback","state=$state waitMs=$waited bufferedMs=${eventTime.totalBufferedDurationMs} metrics=${snapshot()}")
    if(!wasBuffering)bufferingSince=0
   }
  }
  override fun onDroppedVideoFrames(eventTime:AnalyticsListener.EventTime,droppedFrames:Int,elapsedMs:Long){synchronized(this@PlaybackMetrics){dropped+=droppedFrames}}
 }
}
