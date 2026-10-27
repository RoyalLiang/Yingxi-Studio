package io.localplayer.app

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Mpv4kViewportBenchmark {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun surface(view:View):TextureView? {
        if(view is TextureView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)surface(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun actual4kP5UsesVisibleViewportAndRecordsSustainedOutput() {
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"dv5-profile5-4k.mp4");check(file.isFile)
        val prefs=EnrichmentPreferences(context);val online=prefs.subtitles;prefs.subtitles=false
        val uri=Uri.fromFile(file);val media=MediaRef("4k-p5-benchmark",file.name,"本机文件",localUri=uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,media.title,0,media)
        var activity:PlaybackActivity?=null;var controller:MediaController?=null
        try {
            main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))}
            ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val c=future.get(10,TimeUnit.SECONDS);controller=c
            try{ui.waitUntil(30000){main{c.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")==target.playId&&(c.playerError!=null||(c.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")==true&&PlaybackMetrics.snapshot().firstFrameMs!=null))}}}
            catch(e:Throwable){android.util.Log.i("Polish55State",main{playbackDiagnostic(c)});throw e}
            main{assertNull(c.playerError);assertTrue(PlaybackMetrics.snapshot().decoder?.startsWith("mpv.")==true);c.play()}
            val start=SystemClock.elapsedRealtime();Thread.sleep(3000)
            main{c.pause()}
            val m=MpvRuntime.load(context)
            assertEquals("no",m.get("hwdec"));assertEquals("yes",m.get("vd-queue-enable"));assertEquals("2",m.get("vd-queue-max-samples"))
            assertEquals("bt.709",m.get("target-prim"));assertEquals("srgb",m.get("target-trc"))
            val source=org.json.JSONObject(m.get("video-params")?:"{}")
            assertEquals(3840,source.optInt("w"));assertEquals(2160,source.optInt("h"))
            val viewport=main {
                activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()
                val root=activity!!.window.decorView;val output=surface(root)!!
                assertTrue("P5 must not rasterize a surface outside the visible window",output.width<=root.width+2&&output.height<=root.height+2)
                "position_ms=${c.currentPosition} surface=${output.width}x${output.height} window=${root.width}x${root.height} sdr=${c.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")} resize=${(output.parent as? androidx.media3.ui.AspectRatioFrameLayout)?.resizeMode}"
            }
            android.util.Log.i("Sdr4kBenchmark","wall_ms=${SystemClock.elapsedRealtime()-start} $viewport drop=${m.get("frame-drop-count")} decoder_drop=${m.get("decoder-frame-drop-count")} avsync=${m.get("avsync")} panscan=${m.get("panscan")}")

        } finally {main{controller?.let{it.stop();it.clearMediaItems();it.release()};activity?.finish()};PlaybackRequests.remove(target.playId);prefs.subtitles=online}
    }
}
