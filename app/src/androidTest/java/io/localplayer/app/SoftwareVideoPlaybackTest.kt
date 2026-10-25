package io.localplayer.app

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoftwareVideoPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun avc()=recover("software-avc.mp4")
    @Test fun avcHigh10()=recover("software-avc10.mkv")
    @Test fun vp8()=recover("software-vp8.webm")
    @Test fun vp9()=recover("software-vp9.webm")
    @Test fun vp9TenBit()=recover("software-vp9-10.webm")
    @Test fun mpeg2()=recover("software-mpeg2.mkv")

    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun recover(name:String) {
        val context=ui.activity
        val file=File(context.cacheDir,name);assertTrue("Seed $name",file.isFile)
        val frames=AtomicInteger();val nativeFailures=AtomicInteger()
        val platform=DefaultMediaCodecAdapterFactory(context)
        val factory=object:HdrRenderersFactory(context) {
            override fun getCodecAdapterFactory()=MediaCodecAdapter.Factory { config ->
                if(config.codecInfo.mimeType.startsWith("video/")) {
                    nativeFailures.incrementAndGet();throw java.io.IOException("Intentional platform decoder failure")
                }
                platform.createAdapter(config)
            }
        }.apply {
            setMediaCodecSelector(MediaCodecSelector { mime,secure,tunnel ->
                if(mime.startsWith("video/"))listOf(MediaCodecInfo.newInstance(
                    "yingxi.test.decoder",mime,mime,null,true,false,true,false,false))
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)
            })
        }
        val player=main{ExoPlayer.Builder(context,factory).build()}
        val recovery=main{VideoDecoderRecovery(player,factory)}
        lateinit var surface:SurfaceView
        main{ui.activity.setContent{AndroidView(factory={SurfaceView(it).also{view->surface=view;player.setVideoSurfaceView(view)}})}}
        ui.waitForIdle()
        try {
            main {
                player.volume=0f
                player.setVideoFrameMetadataListener{_,_,_,_->frames.incrementAndGet()}
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();player.play()
            }
            ui.waitUntil(20000){main{recovery.attempted&&player.playerError!=null}||frames.get()>=12}
            main{assertNull(player.playerError);assertTrue(recovery.attempted)}
            assertTrue("Must exercise failed native initialization",nativeFailures.get()>0)
            assertTrue("Must present advancing software frames",frames.get()>=12)
            val image=android.graphics.Bitmap.createBitmap(160,90,android.graphics.Bitmap.Config.ARGB_8888)
            val copied=java.util.concurrent.CountDownLatch(1);var result=-1
            main{android.view.PixelCopy.request(surface.holder.surface,image,{result=it;copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
            assertTrue(copied.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            val pixels=IntArray(160*90);image.getPixels(pixels,0,160,0,0,160,90);image.recycle()
            assertTrue("Decoded pixels must contain the test pattern",pixels.toSet().size>24)
            main{player.pause();player.seekTo(1500);player.setPlaybackSpeed(1.5f)}
            val before=frames.get();main{player.play()}
            ui.waitUntil(10000){main{player.playerError!=null}||frames.get()>=before+12}
            main{assertNull(player.playerError);assertEquals(1.5f,player.playbackParameters.speed);assertFalse(recovery.useSoftware("duplicate"))}
            println("SOFTWARE_VIDEO sample=$name frames=${frames.get()} native_failures=${nativeFailures.get()}")
        } finally {main{recovery.close();player.release();(surface.parent as? android.view.ViewGroup)?.removeView(surface)}}
    }
}
