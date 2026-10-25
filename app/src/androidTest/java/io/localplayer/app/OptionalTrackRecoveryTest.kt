package io.localplayer.app

import android.net.Uri
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OptionalTrackRecoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun corruptAudioAndCaptionNeverStopTheMainMovie() {
        val context=ui.activity.applicationContext
        val video=File(context.cacheDir,"seek-performance.mp4");check(video.isFile)
        for(audio in listOf(true,false)) {
            val bad=File(context.cacheDir,if(audio)"corrupt55.mka"else"corrupt55.vtt").apply{
                writeText(if(audio)"this is not an audio file"else"00:00:00.000 --> 00:00:30.000\nMissing WEBVTT header\n")
            }
            val data=DefaultDataSource.Factory(context)
            val factory=SidecarMediaSourceFactory(DefaultMediaSourceFactory(data,PlaybackExtractors(context)),data)
            val player=main{CompatibilityPlayer(context,ExoPlayer.Builder(context).setMediaSourceFactory(factory).build())}
            val view=main{SurfaceView(ui.activity).also{ui.activity.addContentView(it,ViewGroup.LayoutParams(-1,-1));player.setVideoSurfaceView(it)}}
            var item=MediaItem.fromUri(Uri.fromFile(video))
            item=if(audio)SidecarAudio.attach(item,listOf(SidecarAudio(Uri.fromFile(bad),bad.name)))else item.buildUpon().setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(bad)).setMimeType(MimeTypes.TEXT_VTT).setLanguage("zh").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())).build()
            try {
                main{player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredTextLanguage("zh").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build();player.setMediaItem(item,6000);player.volume=0f;player.prepare();player.play()}
                try {ui.waitUntil(15000){main{player.isPlaying&&player.currentPosition>7500&&(!audio||SidecarAudio.from(player.currentMediaItem!!).isEmpty())}}}
                catch(e:androidx.compose.ui.test.ComposeTimeoutException){throw AssertionError(main{"Optional track recovery audio=$audio state=${player.playbackState} pos=${player.currentPosition} play=${player.playWhenReady} suppression=${player.playbackSuppressionReason} error=${player.playerError} audio=${SidecarAudio.from(player.currentMediaItem!!).size} text=${player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.size} tracks=${player.currentTracks.groups.map{Triple(it.type,it.isSelected,it.getTrackFormat(0).sampleMimeType)}}"},e)}
                main{assertNull(player.playerError);assertTrue(player.videoSize.width>0);assertTrue(player.currentPosition<13000)}
            } finally {main{player.release();(view.parent as? ViewGroup)?.removeView(view)};bad.delete()}
        }
    }
}
