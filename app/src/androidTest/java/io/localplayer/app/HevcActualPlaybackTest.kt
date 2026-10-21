package io.localplayer.app

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HevcActualPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun avcControlActuallyPresentsFrames()=render("hevc-control.mp4",false)
    @Test fun main10ActuallyPresentsFramesAndAdvancesAudio()=render("hevc-main10-audio.mp4",true)
    @Test fun failedNativeMain10DecoderRecoversToRealFrames()=render("hevc-main10-audio.mp4",true,1)
    @Test fun audioWithoutFirstVideoFrameRecoversToRealFrames()=render("hevc-main10-audio.mp4",true,2)
    @Test fun reportedH1153CodecStringStillRunsRealMain10Decoding()=render("hevc-main10-audio.mp4",true,3)
    @Test fun main10FullHdPresentsRealFrames()=render("hevc-main10-1080p.mp4",true)
    private fun factoryFor(player:ExoPlayer)=(player.trackSelector as androidx.media3.exoplayer.trackselection.DefaultTrackSelector).parameters
    private fun advertisedCodecs()=object:androidx.media3.extractor.ExtractorsFactory {
        override fun createExtractors()=androidx.media3.extractor.DefaultExtractorsFactory().createExtractors().map { extractor ->
            object:androidx.media3.extractor.Extractor by extractor {
                override fun init(output:androidx.media3.extractor.ExtractorOutput) {
                    extractor.init(object:androidx.media3.extractor.ExtractorOutput by output {
                        override fun track(id:Int,type:Int):androidx.media3.extractor.TrackOutput {
                            val track=output.track(id,type)
                            return object:androidx.media3.extractor.TrackOutput by track {
                                override fun format(format:Format){track.format(if(type==C.TRACK_TYPE_VIDEO)format.buildUpon().setCodecs("hvc1.2.4.H1153.B0").build()else format)}
                            }
                        }
                    })
                }
            }
        }.toTypedArray()
    }
    private fun render(name:String,expectAudio:Boolean,mode:Int=0) {
        val context=ui.activity.applicationContext
        val sample=File(context.cacheDir,name);assertTrue("Seed synthetic playback fixture",sample.isFile)
        val frames=Collections.synchronizedSet(mutableSetOf<Long>())
        val videos=Collections.synchronizedList(mutableListOf<String>())
        val audios=Collections.synchronizedList(mutableListOf<String>())
        var error:PlaybackException?=null
        val failures=Collections.synchronizedList(mutableListOf<PlaybackException>())
        lateinit var recovery:VideoDecoderRecovery
        var ended=false
        lateinit var player:ExoPlayer
        lateinit var view:SurfaceView
        ui.runOnUiThread {
            ui.activity.setContent { AndroidView(factory={SurfaceView(it).also{surface->view=surface}}) }
            val factory=if(mode==2)object:HdrRenderersFactory(context) {
                override fun buildVideoRenderers(context:Context,extensionRendererMode:Int,selector:androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                    enableDecoderFallback:Boolean,eventHandler:android.os.Handler,eventListener:androidx.media3.exoplayer.video.VideoRendererEventListener,
                    allowedVideoJoiningTimeMs:Long,out:ArrayList<androidx.media3.exoplayer.Renderer>) {
                    super.buildVideoRenderers(context,extensionRendererMode,selector,enableDecoderFallback,eventHandler,eventListener,allowedVideoJoiningTimeMs,out)
                    out[nativeVideoIndex]=nativeGate(object:androidx.media3.exoplayer.BaseRenderer(C.TRACK_TYPE_VIDEO) {
                        override fun getName()="NoFirstFrameTestRenderer"
                        override fun supportsFormat(format:Format)=if(format.sampleMimeType==MimeTypes.VIDEO_H265)C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
                        override fun isReady()=true
                        override fun isEnded()=false
                        override fun render(positionUs:Long,elapsedRealtimeUs:Long){}
                    })
                }
            } else playbackRenderers(context)
            val builder=ExoPlayer.Builder(context,factory)
            if(mode==3)builder.setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context),advertisedCodecs()))
            player=builder.build()
            recovery=VideoDecoderRecovery(player,factory,if(mode==2)1000 else 6000)
            player.volume=0f
            player.addAnalyticsListener(object:AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,name:String,initializedTimestampMs:Long,initializationDurationMs:Long){videos.add(name)}
                override fun onAudioDecoderInitialized(eventTime:AnalyticsListener.EventTime,name:String,initializedTimestampMs:Long,initializationDurationMs:Long){audios.add(name)}
            })
            player.addListener(object:Player.Listener {
                override fun onPlayerError(e:PlaybackException){failures.add(e)}
                override fun onEvents(value:Player,events:Player.Events){error=value.playerError}
                override fun onPlaybackStateChanged(state:Int){ended=state==Player.STATE_ENDED}
            })
            player.setVideoFrameMetadataListener{pts,_,_,_->frames.add(pts)}
        }
        ui.waitForIdle()
        ui.runOnUiThread {
            player.setVideoSurfaceView(view)
            val items=mutableListOf(MediaItem.Builder().setUri(Uri.fromFile(sample)).setMediaId("main10-test").build())
            if(mode==1)items.add(MediaItem.Builder().setUri(Uri.fromFile(File(context.cacheDir,"hevc-control.mp4"))).setMediaId("native-avc-test").build())
            player.setMediaItems(items);player.prepare();player.play()
        }
        try {
            ui.waitUntil(15000){error!=null||ended||frames.size>=24}
            println("ACTUAL_DECODE sample=$name video=$videos audio=$audios presented=${frames.size} pts=${frames.sorted()} error=${error?.errorCodeName} cause=${error?.cause}")
            assertNull("Actual decoder failure: ${error?.cause}",error)
            if(mode in 1..2)assertTrue("The same player must recover after native failure or no first frame",recovery.attempted)
            if(mode==1)assertTrue("Exercise a real native decoder error, not only track selection",failures.isNotEmpty())
            if(mode==2){
                assertEquals("missing-first-frame",recovery.reason)
                assertTrue("An audio clock must not skip video that never appeared",frames.minOrNull()!!<=50000)
            }
            assertTrue("Actual video timestamps must advance; audio-only readiness is not playback",frames.size>=12)
            if(expectAudio)assertTrue("Audio decoder must run with video",audios.isNotEmpty())
            val image=android.graphics.Bitmap.createBitmap(320,180,android.graphics.Bitmap.Config.ARGB_8888)
            val copied=java.util.concurrent.CountDownLatch(1);var status=-1
            ui.runOnUiThread {android.view.PixelCopy.request(view.holder.surface,image,{result->status=result;copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
            assertTrue("Actual displayed pixels must be readable",copied.await(4,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS,status)
            val colors=mutableSetOf<Int>();var bright=0
            for(y in 20 until 160 step 7)for(x in 20 until 300 step 7){val c=image.getPixel(x,y);colors.add(c);bright=maxOf(bright,android.graphics.Color.red(c),android.graphics.Color.green(c),android.graphics.Color.blue(c))}
            println("ACTUAL_PIXELS sample=$name distinct=${colors.size} maxChannel=$bright")
            assertTrue("Video surface must contain the generated pattern, not a black frame",colors.size>24&&bright>80)
            File(context.cacheDir,"hevc27-$name-mode$mode.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
            if(mode==3)ui.runOnUiThread{assertEquals("hvc1.2.4.H1153.B0",player.videoFormat?.codecs)}
            if(mode==1)ui.runOnUiThread{assertFalse("Recovery must not loop",recovery.useSoftware("duplicate"))}
            frames.clear()
            ui.runOnUiThread{player.pause();player.seekTo(1500);player.play()}
            ui.waitUntil(10000){error!=null||frames.size>=12}
            assertNull("Seeking must not break real decoding",error)
            assertTrue("Video timestamps must advance again after seek",frames.size>=12)
            if(mode==1) {
                ui.runOnUiThread {
                    assertEquals("Recovery preserves the playlist",2,player.mediaItemCount)
                    assertEquals(0,player.currentMediaItemIndex)
                    player.pause();frames.clear();player.seekToNextMediaItem()
                    assertFalse("Episode changes preserve pause state",player.playWhenReady)
                    assertFalse("New episode can use hardware again",factoryFor(player).getRendererDisabled(0))
                    player.play()
                }
                ui.waitUntil(15000){error!=null||frames.size>=24}
                assertNull(error);assertTrue(frames.size>=12)
                assertFalse("AVC must return to platform decoding",videos.last().lowercase().contains("ffmpeg"))
            }
        } finally {ui.runOnUiThread{recovery.close();player.release()}}
    }
}
