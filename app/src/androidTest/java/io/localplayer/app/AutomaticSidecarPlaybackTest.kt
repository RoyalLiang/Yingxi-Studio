package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutomaticSidecarPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun playerView(view:View):PlayerView? {
        if(view is PlayerView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)playerView(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun directoryAudioSharesRealSeekSpeedAndPauseWhileSubtitlesStayInsideViewport() {
        val context=ui.activity.applicationContext
        val prefs=EnrichmentPreferences(context);val online=prefs.subtitles;prefs.subtitles=false
        val playbackPrefs=PlaybackPreferences(context);val disabled=playbackPrefs.subtitlesDisabled;playbackPrefs.subtitlesDisabled=false
        val folder=File(context.cacheDir,"sidecar-clock-55").apply{mkdirs()}
        val video=File(folder,"Film.2024.mp4").also{File(context.cacheDir,"seek-performance.mp4").copyTo(it,overwrite=true)}
        val audio=File(folder,"Film.2024.en.mka").also{File(context.cacheDir,"sidecar-dts.mka").copyTo(it,overwrite=true)}
        File(folder,"Film.2024.zh-Hans.srt").writeText("1\n00:00:00,000 --> 00:01:29,000\nSIDECAR SAFE VIEWPORT 字幕应完整显示在屏幕可见范围内，不能随视频铺满而裁掉。\n")
        val uri=Uri.fromFile(video);val ref=MediaRef("sidecar-${UUID.randomUUID()}",video.name,"本机文件",localUri=uri.toString(),sizeBytes=video.length())
        val target=PlayTarget(uri,video.name,0,ref)
        var c:MediaController?=null;var activity:PlaybackActivity?=null
        try {
            main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))}
            ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val player=future.get(10,TimeUnit.SECONDS);c=player
            ui.waitUntil(20000){main{player.playerError!=null||(player.currentTracks.groups.count{it.type==C.TRACK_TYPE_AUDIO}>=2&&player.currentCues.cues.isNotEmpty())}}
            main {
                assertNull(player.playerError)
                assertEquals(audio.name,SidecarAudio.from(player.currentMediaItem!!).single().name)
                val group=player.currentTracks.groups.first{it.type==C.TRACK_TYPE_AUDIO&&it.getTrackFormat(0).sampleMimeType==MimeTypes.AUDIO_DTS}
                assertTrue("Installed FFmpeg audio fallback must handle DTS",group.isTrackSupported(0))
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,0)).build()
                player.seekTo(25000);player.play()
            }
            ui.waitUntil(10000){main{player.playerError!=null||(player.isPlaying&&player.currentPosition>=25500&&(PlaybackMetrics.snapshot().audioRenderedBuffers?:0)>0)}}
            main{assertNull(player.playerError);assertTrue("Actual DTS output must use software fallback",PlaybackMetrics.snapshot().audioDecoder?.contains("ffmpeg",true)==true);player.pause();player.setPlaybackSpeed(1.5f);player.seekTo(5000);player.play()}
            ui.waitUntil(10000){main{player.currentPosition in 5500..9000&&player.isPlaying}}
            main{player.pause();assertEquals(1.5f,player.playbackParameters.speed,.001f)}
            ui.waitUntil(10000){main{player.currentCues.cues.any{it.text?.contains("SIDECAR")==true}}}
            ui.waitForIdle()
            val bitmap=main {
                val view=playerView(activity!!.window.decorView)!!;val subtitle=view.subtitleView!!
                assertSame("Subtitles must be outside the scaled video content frame",view.overlayFrameLayout,subtitle.parent)
                assertEquals(activity!!.window.decorView.width,subtitle.width)
                Bitmap.createBitmap(subtitle.width,subtitle.height,Bitmap.Config.ARGB_8888).also{subtitle.draw(Canvas(it))}
            }
            var left=bitmap.width;var right=0;var top=bitmap.height;var bottom=0;var pixels=0
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width)if((bitmap.getPixel(x,y) ushr 24)>32){left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y);pixels++}
            assertTrue("Actual subtitle glyphs must be drawn",pixels>100)
            assertTrue("Glyphs must remain within visible safe viewport",left>8&&right<bitmap.width-8&&top>8&&bottom<bitmap.height-8)
            context.openFileOutput("polish55-native-subtitle.png",0).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val position=main{player.currentPosition}
            main{player.replaceMediaItem(player.currentMediaItemIndex,SidecarAudio.attach(player.currentMediaItem!!,emptyList()))}
            ui.waitUntil(10000){main{player.playbackState==Player.STATE_READY&&player.currentTracks.groups.count{it.type==C.TRACK_TYPE_AUDIO}==1}}
            main{assertFalse(player.playWhenReady);assertTrue(kotlin.math.abs(player.currentPosition-position)<750)}
        } finally {
            main{c?.let{it.stop();it.clearMediaItems();it.release()};activity?.finish()}
            PlaybackRequests.remove(target.playId);HistoryStore(context).remove(ref.key)
            folder.deleteRecursively();prefs.subtitles=online;playbackPrefs.subtitlesDisabled=disabled
        }
    }
}
