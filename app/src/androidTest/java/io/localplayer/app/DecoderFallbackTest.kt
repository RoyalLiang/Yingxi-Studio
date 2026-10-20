package io.localplayer.app

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DecoderFallbackTest {
    @Test fun brokenFirstDecoderFallsBackToAWorkingDecoder(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val sample=File(context.getExternalFilesDir(null),"mkv-avc.mkv")
        assertTrue("Seed synthetic MKV before testing",sample.isFile)
        // The control proves the synthetic first decoder really fails; the production factory recovers.
        for(enabled in listOf(false,true)){
            val done=CountDownLatch(1)
            var error:PlaybackException?=null;var rendered=false
            lateinit var player:ExoPlayer;lateinit var texture:SurfaceTexture;lateinit var surface:Surface
            val selector=MediaCodecSelector{mime,secure,tunnel->
                val real=MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)
                if(mime.startsWith("video/")&&real.isNotEmpty())
                    listOf(MediaCodecInfo.newInstance("c2.yingxi.unavailable.decoder",mime,real.first().codecMimeType,real.first().capabilities,false,true,false,false,false))+real
                else real
            }
            instrumentation.runOnMainSync{
                texture=SurfaceTexture(0);surface=Surface(texture)
                player=ExoPlayer.Builder(context,playbackRenderers(context,selector).setEnableDecoderFallback(enabled)).build()
                player.addListener(object:Player.Listener{
                    override fun onPlayerError(e:PlaybackException){error=e;done.countDown()}
                    override fun onRenderedFirstFrame(){rendered=true;done.countDown()}
                })
                player.setVideoSurface(surface);player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(sample)))
                player.prepare();player.play()
            }
            try{
                assertTrue("Decoder must terminate or render",done.await(12,TimeUnit.SECONDS))
                if(enabled){assertNull(error);assertTrue(rendered)}else{assertNotNull(error);assertFalse(rendered)}
            }finally{instrumentation.runOnMainSync{player.release();surface.release();texture.release()}}
        }
    }
}
