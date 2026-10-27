package io.localplayer.app

import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AttachmentBufferRegressionTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;instrumentation.runOnMainSync{result=runCatching(block)};return result!!.getOrThrow()}
    private fun await(condition:()->Boolean){val end=System.nanoTime()+15_000_000_000;while(System.nanoTime()<end){if(main(condition))return;Thread.sleep(20)};fail("Playback did not settle")}
    @Test fun manualEmbeddedSubtitleChoiceSurvivesAnAttachedDefaultCaption() {
        val context=instrumentation.targetContext
        val video=File(context.cacheDir,"embedded-subtitle.mkv");check(video.isFile)
        val caption=File(context.cacheDir,"external-choice.srt").apply{writeText("1\n00:00:00,000 --> 00:00:30,000\nExternal choice\n")}
        val data=DefaultDataSource.Factory(context)
        val player=main{CompatibilityPlayer(context,ExoPlayer.Builder(context).setMediaSourceFactory(SidecarMediaSourceFactory(DefaultMediaSourceFactory(data),data)).build())}
        try {
            main{player.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)));player.prepare();player.play()}
            await{player.isPlaying}
            main{OnlineSubtitles.attach(player,caption)}
            await{player.currentCues.cues.any{it.text.toString().contains("External choice")}}
            main{val embedded=player.currentTracks.groups.first{it.type==C.TRACK_TYPE_TEXT&&!it.mediaTrackGroup.id.contains(":caption:")};player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(embedded.mediaTrackGroup,0)).build()}
            await{player.currentCues.cues.any{it.text.toString().contains("Embedded choice")}}
            main{val external=player.currentTracks.groups.first{it.type==C.TRACK_TYPE_TEXT&&it.mediaTrackGroup.id.contains(":caption:")};player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(external.mediaTrackGroup,0)).build()}
            await{player.currentCues.cues.any{it.text.toString().contains("External choice")}}
            main{player.replaceMediaItem(0,player.currentMediaItem!!.buildUpon().setSubtitleConfigurations(emptyList()).build())}
            await{player.currentCues.cues.any{it.text.toString().contains("Embedded choice")}}
        }finally{main{player.release()};caption.delete()}
    }
    @Test fun playingSubtitleReplacementKeepsVideoSourceBufferAndDecoder() {
        val context=instrumentation.targetContext
        val video=File(context.cacheDir,"seek-performance.mp4");check(video.isFile)
        val uri=Uri.fromFile(video);val opens=AtomicInteger();val buffering=AtomicInteger();val releases=AtomicInteger()
        val nextVideo=File(context.cacheDir,"buffer-next.mp4").also{video.copyTo(it,overwrite=true)}
        val data=DefaultDataSource.Factory(context).setTransferListener(object:TransferListener {
            override fun onTransferInitializing(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
            override fun onTransferStart(source:DataSource,spec:DataSpec,isNetwork:Boolean){if(spec.uri==uri){opens.incrementAndGet();println("VIDEO_OPEN position=${spec.position}")}}
            override fun onBytesTransferred(source:DataSource,spec:DataSpec,isNetwork:Boolean,bytes:Int){}
            override fun onTransferEnd(source:DataSource,spec:DataSpec,isNetwork:Boolean){}
        })
        val player=main{
            val native=ExoPlayer.Builder(context).setMediaSourceFactory(SidecarMediaSourceFactory(DefaultMediaSourceFactory(data),data)).build()
            native.addAnalyticsListener(object:AnalyticsListener{override fun onVideoDecoderReleased(time:AnalyticsListener.EventTime,name:String){releases.incrementAndGet()}})
            CompatibilityPlayer(context,native).also{p->p.addListener(object:Player.Listener{override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_BUFFERING)buffering.incrementAndGet()}})}
        }
        val files=(1..3).map{File(context.cacheDir,"buffer-caption-$it.srt").apply{writeText("1\n00:00:00,000 --> 00:00:30,000\nLate caption $it\n")}}
        try {
            main{val item=MediaItem.Builder().setMediaId("buffer-original").setUri(uri).build();player.setMediaItems(listOf(item,item.buildUpon().setMediaId("next").setUri(Uri.fromFile(nextVideo)).build()),0,2500);player.prepare();player.play()}
            await{player.isPlaying&&player.currentPosition>2700}
            val before=opens.get();buffering.set(0);releases.set(0)
            main{OnlineSubtitles.attach(player,files.first())}
            await{player.currentCues.cues.any{it.text.toString().contains("Late caption 1")}}
            main{OnlineSubtitles.attach(player,files[1]);OnlineSubtitles.attach(player,files[2])}
            await{player.currentCues.cues.any{it.text.toString().contains("Late caption 3")}}
            main{assertNull(player.playerError);assertTrue(player.isPlaying);assertTrue(player.currentPosition>2700);assertEquals(2,player.mediaItemCount)}
            assertEquals("A/V rebuffered",0,buffering.get());assertEquals("Video decoder released",0,releases.get());assertEquals("Video source reopened",before,opens.get())
            main{player.seekTo(5000)};await{player.currentPosition>=5000&&player.currentCues.cues.any{it.text.toString().contains("Late caption 3")}}
            main{OnlineSubtitles.attach(player,files.first());player.removeMediaItem(0)}
            await{player.currentMediaItem?.mediaId=="next"&&player.isPlaying}
            Thread.sleep(150)
            main{assertEquals("next",player.currentMediaItem!!.mediaId);assertEquals(1,player.mediaItemCount);assertTrue(player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.isEmpty())}
        }finally{main{player.release()};files.forEach{it.delete()};nextVideo.delete()}
    }
}
