package io.localplayer.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Inject at the existing reflection boundary, leaving the actual native player and surface intact. */
object SlowMpvGetter {
    lateinit var native:Method
    var entered=CountDownLatch(1)
    var unblock=CountDownLatch(1)
    val armed=AtomicBoolean(false)
    @Volatile var property="time-pos"
    @Volatile var staleEof=false
    @JvmStatic fun read(name:String):String? {
        if(name==property&&armed.compareAndSet(true,false)){
            entered.countDown();check(unblock.await(5,TimeUnit.SECONDS))
            if(staleEof)return "yes"
        }
        return native.invoke(null,name) as String?
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MpvPollingPerformanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun slowNativePropertyReadLeavesMainResponsiveAndCannotResurrectStoppedPlayback() = slowRead(false)
    @Test fun preSeekEndOfFileSampleCannotEndPlaybackAtTheNewPosition() = slowRead(true)

    private fun slowRead(seek:Boolean) {
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"dv5-profile5.mp4");check(file.isFile)
        val engine=main{MpvPlayer(context,Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.05.05").build())}
        val surface=main{SurfaceView(ui.activity).also{ui.activity.addContentView(it,ViewGroup.LayoutParams(-1,-1));engine.setVideoSurfaceView(it)}}
        var methods:MutableMap<String,Method>?=null
        try {
            main{engine.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));engine.prepare();engine.play()}
            ui.waitUntil(30000){main{engine.playerError!=null||(engine.playbackState==Player.STATE_READY&&engine.videoSize.width==1920)}}
            main{assertNull(engine.playerError)}
            val runtime=MpvRuntime.load(context)
            @Suppress("UNCHECKED_CAST")
            val cache=MpvRuntime::class.java.getDeclaredField("methods").apply{isAccessible=true}.get(runtime) as MutableMap<String,Method>
            methods=cache;SlowMpvGetter.native=cache.getValue("getPropertyString")
            SlowMpvGetter.entered=CountDownLatch(1);SlowMpvGetter.unblock=CountDownLatch(1)
            cache["getPropertyString"]=SlowMpvGetter::class.java.getMethod("read",String::class.java)
            SlowMpvGetter.property=if(seek)"eof-reached"else"time-pos";SlowMpvGetter.staleEof=seek
            SlowMpvGetter.armed.set(true)
            assertTrue("A real poll reaches the delayed getter",SlowMpvGetter.entered.await(5,TimeUnit.SECONDS))
            val heartbeat=CountDownLatch(1)
            Handler(Looper.getMainLooper()).post{heartbeat.countDown()}
            val responsive=heartbeat.await(180,TimeUnit.MILLISECONDS)
            if(!responsive)SlowMpvGetter.unblock.countDown()
            assertTrue("Native property reads must not block the application looper",responsive)
            main{if(seek){engine.seekTo(4000);engine.play()}else{engine.stop();assertEquals(Player.STATE_IDLE,engine.playbackState)}}
            SlowMpvGetter.unblock.countDown()
            Thread.sleep(350)
            main{
                if(seek){assertNotEquals("Pre-seek EOF must be discarded",Player.STATE_ENDED,engine.playbackState);assertTrue("Pre-seek EOF must not clear play intent",engine.playWhenReady);assertTrue(engine.currentPosition>=4000)}
                else assertEquals("Late snapshot cannot restore the stopped state",Player.STATE_IDLE,engine.playbackState)
                assertNull(engine.playerError)
            }
        } finally {
            SlowMpvGetter.armed.set(false);SlowMpvGetter.unblock.countDown()
            methods?.set("getPropertyString",SlowMpvGetter.native)
            main{engine.release();(surface.parent as? ViewGroup)?.removeView(surface)}
        }
    }
}
