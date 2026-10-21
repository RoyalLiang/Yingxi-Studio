package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HevcSessionPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var v:Result<T>?=null;ui.runOnUiThread{v=runCatching(block)};return v!!.getOrThrow()}
    private fun playerView(view:View):PlayerView? {
        if(view is PlayerView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)playerView(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun pixels(surface:SurfaceView,name:String,context:android.content.Context):IntArray {
        val image=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);val latch=CountDownLatch(1);var status=-1
        main{PixelCopy.request(surface.holder.surface,image,{status=it;latch.countDown()},Handler(Looper.getMainLooper()))}
        assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
        val pixels=IntArray(320*180);image.getPixels(pixels,0,320,0,0,320,180)
        assertTrue("Actual app must display the video pattern",pixels.toSet().size>100)
        File(context.cacheDir,"hevc27-session-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle();return pixels
    }
    @Test fun fullscreenSessionActuallyShowsMain10FramesBeforeAndAfterSeek() {
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"hevc-main10-1080p.mp4");assertTrue(file.isFile)
        val uri=Uri.fromFile(file);val ref=MediaRef("hevc-session","HEVC Main10 session","本机文件",localUri=uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,ref.title,0,ref)
        main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
        var activity:PlaybackActivity?=null
        ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val c=future.get(10,TimeUnit.SECONDS)
        try {
            ui.waitUntil(15000){main{c.playerError!=null||(c.playbackState==Player.STATE_READY&&c.videoSize.width==1920&&PlaybackMetrics.snapshot().firstFrameMs!=null)}}
            main{assertNull(c.playerError);c.pause();c.seekTo(1000)}
            ui.waitUntil(5000){main{c.currentPosition==1000L}}
            val surface=main{playerView(activity!!.window.decorView)!!.videoSurfaceView as SurfaceView}
            val first=pixels(surface,"first",context)
            main{c.seekTo(2500);c.play()}
            ui.waitUntil(10000){main{c.playerError!=null||c.currentPosition>=3300}}
            main{assertNull(c.playerError);c.pause()}
            val second=pixels(surface,"seek",context)
            assertFalse("The displayed video must change as playback progresses",first.contentEquals(second))
            println("HEVC_SESSION_REAL_FRAMES metrics=${PlaybackMetrics.snapshot()}")
        } finally {main{activity?.finish();c.release()};PlaybackRequests.remove(target.playId)}
    }
}
