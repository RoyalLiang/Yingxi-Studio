package io.localplayer.app

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoftwareAudioPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun ac3Surround()=play("audio-ac3.mkv")
    @Test fun eac3Surround()=play("audio-eac3.mkv")
    @Test fun dtsSurround()=play("audio-dts.mkv")
    @Test fun trueHdSurround()=play("audio-truehd.mkv")
    @Test fun alac24Bit()=play("audio-alac.mkv")
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun play(name:String) {
        val file=File(ui.activity.cacheDir,name);assertTrue(file.isFile)
        val player=main {
            PlaybackMetrics.reset()
            val selector=MediaCodecSelector{mime,secure,tunnel->if(MimeTypes.isAudio(mime))emptyList()else MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)}
            ExoPlayer.Builder(ui.activity,playbackRenderers(ui.activity,selector)).build().also{
                it.volume=0f;it.addAnalyticsListener(PlaybackMetrics.listener())
            }
        }
        main{ui.activity.setContent{AndroidView(factory={SurfaceView(it).also(player::setVideoSurfaceView)})}}
        ui.waitForIdle()
        try {
            main{player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();player.play()}
            ui.waitUntil(15000){main{player.playerError!=null||(player.currentPosition>700&&(PlaybackMetrics.snapshot().audioRenderedBuffers?:0)>12)}}
            main{
                assertNull(player.playerError)
                val stats=PlaybackMetrics.snapshot()
                assertTrue("Use the real FFmpeg audio decoder",stats.audioDecoder?.contains("ffmpeg",true)==true)
                assertTrue("PCM output buffers must advance",(stats.audioRenderedBuffers?:0)>12)
                assertEquals(stats.audioDecoder,org.json.JSONObject(playbackDiagnostic(player)).getString("audioDecoder"))
                assertTrue(ui.activity.getSystemService(android.media.AudioManager::class.java).isMusicActive)
                player.pause();player.seekTo(2500);player.setPlaybackSpeed(1.5f);player.play()
            }
            ui.waitUntil(10000){main{player.playerError!=null||player.currentPosition>3200}}
            main{assertNull(player.playerError);assertEquals(1.5f,player.playbackParameters.speed)}
            println("SOFTWARE_AUDIO sample=$name decoder=${PlaybackMetrics.snapshot().audioDecoder} buffers=${PlaybackMetrics.snapshot().audioRenderedBuffers}")
        }finally{main{player.release()}}
    }
}
