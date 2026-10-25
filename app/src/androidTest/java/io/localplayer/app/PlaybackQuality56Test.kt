package io.localplayer.app

import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackQuality56Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}

    @Test fun realHlsVariantsSwitchWithoutReplacingQueuePositionOrOtherPreferences(){
        val url=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("quality_url")
        assumeNotNull("Provide a two-variant HLS fixture",url)
        val player=main{ExoPlayer.Builder(ui.activity).build()}
        try{
            main{
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("zh").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                player.setMediaItems(listOf(MediaItem.fromUri(url!!),MediaItem.fromUri(url)))
                player.setPlaybackSpeed(1.25f)
                ui.activity.setContent{PlayerTheme{
                    var tracks by remember{mutableStateOf(player.currentTracks)}
                    var parameters by remember{mutableStateOf(player.trackSelectionParameters)}
                    DisposableEffect(player){
                        val listener=object:Player.Listener{override fun onEvents(p:Player,e:Player.Events){tracks=p.currentTracks;parameters=p.trackSelectionParameters}}
                        player.addListener(listener);onDispose{player.removeListener(listener)}
                    }
                    Column{
                        AndroidView(factory={SurfaceView(it).also(player::setVideoSurfaceView)},modifier=Modifier.height(160.dp).fillMaxWidth())
                        PlaybackQualityChoices(player,player.currentMediaItem,tracks,parameters)
                    }
                }}
                player.prepare();player.play()
            }
            ui.waitUntil(20000){main{player.playerError!=null||player.currentTracks.groups.any{it.type==C.TRACK_TYPE_VIDEO&&it.length==2}}}
            main{assertNull(player.playerError);player.seekTo(3000)}
            ui.onNodeWithTag("video-quality-0-1").performClick()
            ui.waitUntil(15000){main{player.videoFormat?.width==320&&player.currentPosition>=3000}}
            main{
                assertEquals(2,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex)
                assertEquals(1.25f,player.playbackParameters.speed)
                assertEquals(listOf("zh"),player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
                assertEquals(listOf(1),player.trackSelectionParameters.overrides.values.single{it.type==C.TRACK_TYPE_VIDEO}.trackIndices)
            }
            ui.onNodeWithTag("video-quality-0-0").performClick()
            ui.waitUntil(15000){main{player.videoFormat?.width==640}}
            ui.onNodeWithTag("video-quality-auto").performClick()
            main{assertTrue(player.trackSelectionParameters.overrides.keys.none{it.type==C.TRACK_TYPE_VIDEO});assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));assertNull(player.playerError)}
        }finally{main{player.release()}}
    }
}
