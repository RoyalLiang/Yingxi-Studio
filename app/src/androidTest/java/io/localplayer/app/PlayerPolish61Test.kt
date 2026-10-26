package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerPolish61Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}

    @Test fun bitratePropertiesDistinguishAveragePeakAndMissingValues(){
        assertEquals("2.0 Mbps",playbackBitrateLabel(Format.Builder().setAverageBitrate(2_000_000).build()))
        assertEquals("峰值 3.0 Mbps",playbackBitrateLabel(Format.Builder().setPeakBitrate(3_000_000).build()))
        assertEquals("256 Kbps",playbackBitrateLabel(Format.Builder().setAverageBitrate(256_000).build()))
        assertNull(playbackBitrateLabel(null))
        assertNull(playbackBitrateLabel(Format.Builder().build()))
    }

    @Test fun fixedVideoAlternativesCanBeSelectedWithoutAdaptiveSupport(){
        val formats=listOf(1_000_000,3_000_000).map{Format.Builder().setSampleMimeType("video/avc").setAverageBitrate(it).setWidth(640).setHeight(360).build()}
        val group=TrackGroup("fixed-video",*formats.toTypedArray())
        val tracks=Tracks(listOf(Tracks.Group(group,false,intArrayOf(C.FORMAT_HANDLED,C.FORMAT_HANDLED),booleanArrayOf(true,false))))
        val player=main{ExoPlayer.Builder(ui.activity).build()}
        try{
            main{
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("zh").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                ui.activity.setContent{PlayerTheme{PlaybackQualityChoices(player,null,tracks,player.trackSelectionParameters)}}
            }
            ui.onNodeWithTag("video-quality-0-1").assertIsDisplayed().performClick()
            main{
                assertEquals(listOf(1),player.trackSelectionParameters.overrides[group]?.trackIndices)
                assertEquals(listOf("zh"),player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            }
        }finally{main{player.release()}}
    }
    @Test fun portraitQueueIsBottomAnchoredAndCurrentEpisodeDoesNotRestart(){
        val items=(1..24).map{MediaItem.Builder().setMediaId("episode-$it").setMediaMetadata(MediaMetadata.Builder().setTitle("第${it}集 山谷").build()).build()}
        var closed=false;val selected=mutableListOf<Int>()
        main{ui.activity.setContent{PlayerTheme{PlaybackQueueSheet(items,3,false,{closed=true},{selected+=it})}}}
        val bounds=ui.onNodeWithTag("playback-queue-drawer").getUnclippedBoundsInRoot()
        val height=ui.activity.resources.configuration.screenHeightDp.toFloat()
        assertTrue("Portrait queue should leave the upper film visible",bounds.top.value>height*.12f)
        assertTrue("Portrait queue should sit at the bottom",bounds.bottom.value>height-40)
        ui.onNodeWithText("第4集 山谷").performClick()
        assertTrue(closed);assertTrue(selected.isEmpty())
    }

    @Test fun actualFileSwitchesItsTwoVideoTracksWithoutChangingQueueOrProgress(){
        val file=java.io.File(ui.activity.cacheDir,"two-video-tracks.mp4")
        org.junit.Assume.assumeTrue("Generated dual-video-track fixture required",file.isFile)
        val player=main{ExoPlayer.Builder(ui.activity).build()}
        try{
            main{
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("zh").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                player.setMediaItems(listOf(MediaItem.fromUri(android.net.Uri.fromFile(file)),MediaItem.fromUri(android.net.Uri.fromFile(file))))
                player.setPlaybackSpeed(1.25f)
                ui.activity.setContent{PlayerTheme{
                    var tracks by androidx.compose.runtime.remember{androidx.compose.runtime.mutableStateOf(player.currentTracks)}
                    var parameters by androidx.compose.runtime.remember{androidx.compose.runtime.mutableStateOf(player.trackSelectionParameters)}
                    androidx.compose.runtime.DisposableEffect(player){
                        val listener=object:Player.Listener{override fun onEvents(p:Player,e:Player.Events){tracks=p.currentTracks;parameters=p.trackSelectionParameters}}
                        player.addListener(listener);onDispose{player.removeListener(listener)}
                    }
                    androidx.compose.foundation.layout.Column{
                        androidx.compose.ui.viewinterop.AndroidView(factory={android.view.SurfaceView(it).also(player::setVideoSurfaceView)},modifier=androidx.compose.ui.Modifier.height(160.dp).fillMaxWidth())
                        PlaybackQualityChoices(player,player.currentMediaItem,tracks,parameters)
                    }
                }}
                player.prepare();player.play()
            }
            ui.waitUntil(15000){main{player.playerError!=null||player.playbackState==Player.STATE_READY&&player.videoFormat!=null}}
            val choice=main{
                assertNull(player.playerError);player.pause();player.seekTo(3000)
                val groups=player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_VIDEO}
                assertEquals(2,groups.sumOf{it.length})
                val g=groups.indexOfFirst{it.getTrackFormat(0).width==320};assertTrue(g>=0)
                "video-quality-$g-0"
            }
            ui.onNodeWithTag(choice).performClick()
            ui.waitUntil(10000){main{player.videoFormat?.width==320}}
            main{
                assertEquals(2,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex)
                assertTrue(player.currentPosition>=2900);assertEquals(1.25f,player.playbackParameters.speed)
                assertFalse(player.playWhenReady)
                assertEquals(listOf("zh"),player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));assertNull(player.playerError)
            }
        }finally{main{player.release()}}
    }

}
