package io.localplayer.app

import android.net.Uri
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleSeekRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun longExternalCueSurvivesSeekingWithMergedSources() {
        val context=ui.activity.applicationContext
        val video=File(context.cacheDir,"seek-performance.mp4");check(video.isFile)
        val caption=File(context.cacheDir,"cue-seek55.srt").apply{writeText("1\n00:00:00,000 --> 00:01:29,000\nSIDECAR cue after seek\n")}
        val subtitle=MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(caption)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("zh").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val item=MediaItem.Builder().setUri(Uri.fromFile(video)).setSubtitleConfigurations(listOf(subtitle)).build()
        val outcomes=mutableListOf<String>()
        for(kind in listOf("fixed","merged")) {
            val factory=DefaultMediaSourceFactory(DefaultDataSource.Factory(context),if(kind=="stock")androidx.media3.extractor.DefaultExtractorsFactory()else PlaybackExtractors(context))
            val player=main{ExoPlayer.Builder(context).setMediaSourceFactory(SidecarMediaSourceFactory(factory,DefaultDataSource.Factory(context))).build()}
            val surface=main{SurfaceView(ui.activity).also{ui.activity.addContentView(it,ViewGroup.LayoutParams(-1,-1));player.setVideoSurfaceView(it)}}
            try {
                val target=if(kind=="merged")SidecarAudio.attach(item,listOf(SidecarAudio(Uri.fromFile(File(context.cacheDir,"sidecar-dts.mka")),"DTS")))else item
                main{player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredTextLanguage("zh").build();player.setMediaItem(target);player.volume=0f;player.prepare();player.play()}
                ui.waitUntil(15000){main{player.playerError!=null||player.playbackState==Player.STATE_READY}}
                for(position in listOf(500L,25000L,5000L)) {
                    main{player.seekTo(position);player.play()}
                    ui.waitUntil(10000){main{player.playerError!=null||(player.playbackState==Player.STATE_READY&&player.currentPosition>=position)}}
                    var found=false
                    try{ui.waitUntil(2000){main{player.currentCues.cues.any{it.text?.contains("SIDECAR")==true}}};found=true}catch(_:androidx.compose.ui.test.ComposeTimeoutException){}
                    outcomes+="$kind/$position=$found"
                }
            } finally {main{player.release();(surface.parent as? ViewGroup)?.removeView(surface)}}
        }
        assertTrue(outcomes.joinToString(),outcomes.all{it.endsWith("true")})
    }
}
