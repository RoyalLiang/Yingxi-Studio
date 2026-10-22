package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
class PlaybackSpeedRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun view(root:View):PlayerView? {
        if(root is PlayerView)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)view(root.getChildAt(i))?.let{return it}
        return null
    }
    private fun pixels(surface:SurfaceView):IntArray {
        val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);val ready=CountDownLatch(1);var status=-1
        main{PixelCopy.request(surface.holder.surface,image,{status=it;ready.countDown()},Handler(Looper.getMainLooper()))}
        assertTrue(ready.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
        val data=IntArray(160*90);image.getPixels(data,0,160,0,0,160,90);image.recycle()
        assertTrue("Actual patterned video is visible",data.toSet().size>100)
        return data
    }

    @Test fun twoKFramesKeepAdvancingAtOneAndAHalfAndDoubleSpeed() {
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"speed-2k-avc.mp4");assertTrue("Seed the 2K fixture",file.isFile)
        val uri=Uri.fromFile(file);val ref=MediaRef("speed-2k-avc","2K speed.mp4","本机文件",localUri=uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,ref.title,0,ref);var activity:PlaybackActivity?=null
        val previous=PlaybackPreferences(context).speed
        main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
        ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        try {
            ui.waitUntil(20000){main{player.playerError!=null||(player.playbackState==Player.STATE_READY&&player.videoSize.width==2560&&PlaybackMetrics.snapshot().firstFrameMs!=null)}}
            val surface=main{view(activity!!.window.decorView)!!.videoSurfaceView as SurfaceView}
            for(speed in listOf(1f,1.5f,2f)) {
                main{player.pause();player.setPlaybackSpeed(speed);player.seekTo(1000);player.play()}
                ui.waitUntil(10000){main{player.playerError!=null||(player.currentPosition>=2000&&player.playbackState==Player.STATE_READY)}}
                main{assertNull(player.playerError)}
                val first=pixels(surface);val before=PlaybackMetrics.snapshot();val position=main{player.currentPosition}
                val started=SystemClock.elapsedRealtime();var lastFrames=before.videoRenderedBuffers?:0;var lastProgress=started;var maxGap=0L
                repeat(20) {
                    Thread.sleep(200)
                    val now=SystemClock.elapsedRealtime();val frames=PlaybackMetrics.snapshot().videoRenderedBuffers?:0
                    if(frames>lastFrames){maxGap=maxOf(maxGap,now-lastProgress);lastProgress=now;lastFrames=frames}
                }
                val elapsed=SystemClock.elapsedRealtime()-started;maxGap=maxOf(maxGap,SystemClock.elapsedRealtime()-lastProgress)
                val after=PlaybackMetrics.snapshot();val rendered=(after.videoRenderedBuffers?:0)-(before.videoRenderedBuffers?:0)
                val advanced=main{player.currentPosition}-position
                val second=pixels(surface)
                println("SPEED_2K speed=$speed decoder=${after.decoder} elapsed=$elapsed rendered=$rendered fps=${rendered*1000.0/elapsed} dropped=${after.droppedFrames-before.droppedFrames} maxGap=$maxGap advanced=$advanced buffer=${main{player.totalBufferedDuration}} rebuffers=${after.rebufferCount-before.rebufferCount}")
                assertFalse("The actual displayed frame changes at $speed",first.contentEquals(second))
                assertTrue("No frozen display at $speed: gap=$maxGap",maxGap<1000)
                assertTrue("Media clock scales with speed $speed: $advanced/$elapsed",advanced>elapsed*speed*.8)
                assertTrue("Decoder keeps up at $speed: $rendered frames/$elapsed ms",rendered>=elapsed/1000.0*30*speed*.8)
                main{assertNull(player.playerError)}
            }
        } finally {
            main{player.setPlaybackSpeed(previous);player.pause();activity?.finish();player.release()}
            PlaybackRequests.remove(target.playId)
        }
    }
}
