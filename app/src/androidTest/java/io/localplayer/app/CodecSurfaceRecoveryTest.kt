package io.localplayer.app

import android.net.Uri
import android.view.Surface
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CodecSurfaceRecoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun releasedCodecDuringOutputSwitchRebuildsAndKeepsRendering() {
        val sample=File(ui.activity.cacheDir,"continuity-avc1080.mp4");assertTrue(sample.isFile)
        val failNext=AtomicBoolean();val created=AtomicInteger();val frames=AtomicInteger()
        val platform=DefaultMediaCodecAdapterFactory(ui.activity)
        val adapterFactory=MediaCodecAdapter.Factory { configuration ->
            created.incrementAndGet()
            val adapter=platform.createAdapter(configuration)
            object:MediaCodecAdapter by adapter {
                override fun setOutputSurface(surface:Surface) {
                    if(failNext.compareAndSet(true,false)) {
                        // Replay the framework failure captured during real HEVC session recovery.
                        throw IllegalStateException("setSurface() is valid only at Executing states; currently at Released state")
                            .apply{stackTrace=arrayOf(StackTraceElement("android.media.MediaCodec","setOutputSurface","MediaCodec.java",1))}
                    }
                    adapter.setOutputSurface(surface)
                }
            }
        }
        var error:PlaybackException?=null
        lateinit var player:ExoPlayer;lateinit var recovery:VideoDecoderRecovery;lateinit var first:SurfaceView;lateinit var second:SurfaceView
        ui.runOnUiThread {
            ui.activity.setContent { Column {
                AndroidView(factory={SurfaceView(it).also{first=it}},modifier=Modifier.fillMaxWidth().height(160.dp))
                AndroidView(factory={SurfaceView(it).also{second=it}},modifier=Modifier.fillMaxWidth().height(160.dp))
            } }
            val factory=object:HdrRenderersFactory(ui.activity) {override fun getCodecAdapterFactory()=adapterFactory}
            player=ExoPlayer.Builder(ui.activity,factory).build();player.volume=0f
            recovery=VideoDecoderRecovery(player,factory)
            player.addListener(object:Player.Listener{override fun onEvents(p:Player,events:Player.Events){error=p.playerError}})
            player.setVideoFrameMetadataListener{_,_,_,_->frames.incrementAndGet()}
        }
        try {
            ui.waitForIdle()
            ui.runOnUiThread{player.setVideoSurfaceView(first);player.setMediaItem(MediaItem.fromUri(Uri.fromFile(sample)));player.prepare();player.play()}
            ui.waitUntil(10000){error!=null||frames.get()>=12};assertNull(error)
            frames.set(0);failNext.set(true)
            ui.runOnUiThread{player.setVideoSurfaceView(second)}
            ui.waitUntil(10000){error!=null||(!failNext.get()&&frames.get()>=12)}
            assertNull(error);assertFalse("Exercise the captured surface-state failure",failNext.get())
            assertTrue("Reload samples from a keyframe at the current position",recovery.outputRecoveryAttempted)
            assertTrue("Replace the failed codec",created.get()>=2)
            assertTrue("Present real frames after the output switch",frames.get()>=12)
        }finally{ui.runOnUiThread{recovery.close();player.release()}}
    }
}
