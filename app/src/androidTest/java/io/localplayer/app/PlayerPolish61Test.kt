package io.localplayer.app

import androidx.activity.compose.setContent
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

    @Test fun bitratePropertiesDistinguishMeasuredPeakAndContainerEstimate(){
        assertEquals("2.0 Mbps",playbackBitrateLabel(Format.Builder().setAverageBitrate(2_000_000).build(),1_000_000,1000))
        assertEquals("峰值 3.0 Mbps",playbackBitrateLabel(Format.Builder().setPeakBitrate(3_000_000).build()))
        assertEquals("约 8.0 Mbps",playbackBitrateLabel(null,1_000_000,1000))
        assertEquals("256 Kbps",playbackBitrateLabel(Format.Builder().setAverageBitrate(256_000).build()))
        assertNull(playbackBitrateLabel(null,1_000_000,0))
        assertNull(playbackBitrateLabel(null,-1,1000))
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

}
