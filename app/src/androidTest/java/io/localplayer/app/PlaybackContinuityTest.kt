package io.localplayer.app

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
class PlaybackContinuityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun fullHdAvcKeepsPresentingFrames()=play("continuity-avc1080.mp4",240)
    @Test fun fullHdMain10KeepsPresentingFrames()=play("hevc-main10-1080p.mp4",144)

    private fun play(name:String,expected:Int) {
        val file=File(ui.activity.cacheDir,name);assertTrue("Seed synthetic continuity fixture",file.isFile)
        val frames=Collections.synchronizedList(mutableListOf<Long>())
        val wall=Collections.synchronizedList(mutableListOf<Long>())
        val codecs=Collections.synchronizedList(mutableListOf<String>())
        var ended=false;var error:PlaybackException?=null;var dropped=0;var rebuffers=0
        lateinit var player:ExoPlayer;lateinit var recovery:VideoDecoderRecovery;lateinit var surface:SurfaceView
        ui.runOnUiThread {
            ui.activity.setContent{AndroidView(factory={SurfaceView(it).also{surface=it}})}
            val factory=playbackRenderers(ui.activity)
            player=ExoPlayer.Builder(ui.activity,factory).build();player.volume=0f
            recovery=VideoDecoderRecovery(player,factory)
            player.addListener(object:Player.Listener {
                override fun onPlaybackStateChanged(state:Int){ended=state==Player.STATE_ENDED;if(state==Player.STATE_BUFFERING&&frames.isNotEmpty())rebuffers++}
                override fun onEvents(p:Player,events:Player.Events){error=p.playerError}
            })
            player.addAnalyticsListener(object:AnalyticsListener {
                override fun onVideoDecoderInitialized(t:AnalyticsListener.EventTime,name:String,at:Long,duration:Long){codecs.add(name)}
                override fun onDroppedVideoFrames(t:AnalyticsListener.EventTime,count:Int,elapsed:Long){dropped+=count}
            })
            player.setVideoFrameMetadataListener{pts,_,_,_->frames.add(pts);wall.add(android.os.SystemClock.elapsedRealtime())}
        }
        try {
            ui.waitForIdle()
            ui.runOnUiThread {player.setVideoSurfaceView(surface);player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();player.play()}
            ui.waitUntil(25000){ended||error!=null}
            assertNull(error)
            assertEquals("No repeated loading after video starts",0,rebuffers)
            val times=frames.toList()
            val gaps=times.zipWithNext{a,b->b-a}
            val wallGaps=wall.toList().zipWithNext{a,b->b-a}
            File(ui.activity.cacheDir,"continuity-$name.json").writeText(org.json.JSONObject()
                .put("sample",name).put("rebuffers",rebuffers).put("expected",expected).put("presented",times.size).put("dropped",dropped)
                .put("maxPtsGapUs",gaps.maxOrNull()).put("maxWallGapMs",wallGaps.maxOrNull())
                .put("decoders",org.json.JSONArray(codecs)).put("recovery",recovery.reason).toString())
            println("CONTINUITY sample=$name decoders=$codecs frames=${times.size}/$expected dropped=$dropped maxPtsGapUs=${gaps.maxOrNull()} recovery=${recovery.reason}")
            assertTrue("Continuous playback must present at least 90% of the synthetic frames",times.size>=expected*.9)
            assertTrue("No multi-frame freezes in the presented timeline",gaps.all{it<=150000})
            assertTrue("Frame output must keep advancing in real time",wallGaps.all{it<=250})
            if(name.startsWith("continuity-avc"))assertTrue(codecs.none{it.lowercase().contains("ffmpeg")})
        }finally{ui.runOnUiThread{recovery.close();player.release()}}
    }
}
