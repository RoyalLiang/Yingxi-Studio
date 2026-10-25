package io.localplayer.app

import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MpvPollingPerformanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun actualSdrPollingBenchmark() {
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"dv5-profile5.mp4");check(file.isFile)
        val engine=main{MpvPlayer(context,Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.05.05").build())}
        val surface=main{SurfaceView(ui.activity).also{ui.activity.addContentView(it,ViewGroup.LayoutParams(-1,-1));engine.setVideoSurfaceView(it)}}
        try {
            main{engine.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));engine.prepare();engine.play()}
            ui.waitUntil(30000){main{engine.playerError!=null||(engine.playbackState==Player.STATE_READY&&engine.videoSize.width==1920&&engine.videoSize.height==1080)}}
            main{assertNull(engine.playerError);engine.pause()}
            val refresh=MpvPlayer::class.java.getDeclaredMethod("refresh").apply{isAccessible=true}
            val times=main{LongArray(100){val start=SystemClock.elapsedRealtimeNanos();refresh.invoke(engine);SystemClock.elapsedRealtimeNanos()-start}}
            android.util.Log.i("Polish55State",main{"mpv source=${MpvRuntime.load(context).get("video-params")} output=${MpvRuntime.load(context).get("video-out-params")}"})
            main{assertNull(engine.playerError);assertEquals(1920,engine.videoSize.width);assertEquals(1080,engine.videoSize.height)}
            val sorted=times.sorted()
            println("SDR_POLL_BENCH count=100 total_ms=${times.sum()/1e6} p95_ms=${sorted[94]/1e6} max_ms=${sorted.last()/1e6}")
        } finally {main{engine.release();(surface.parent as? ViewGroup)?.removeView(surface)}}
    }
}
