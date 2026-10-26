package io.localplayer.app

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.common.FlagSet
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OrientationFreshness61Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun oldVideoSizeCannotOverrideTheNextItemsKnownDirection(){
        ui.runOnUiThread{
            val engine=ExoPlayer.Builder(ui.activity).build()
            var item=MediaItem.Builder().setMediaId("wide").build()
            var size=VideoSize(640,360)
            val player=object:ForwardingPlayer(engine){override fun getCurrentMediaItem()=item;override fun getVideoSize()=size}
            val state=PlaybackOrientationState()
            fun events(event:Int)=Player.Events(FlagSet.Builder().add(event).build())
            try{
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,state.update(player,events(Player.EVENT_VIDEO_SIZE_CHANGED),null))
                item=MediaItem.Builder().setMediaId("tall").build()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,state.update(player,events(Player.EVENT_TIMELINE_CHANGED),ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))
                size=VideoSize(360,640)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,state.update(player,events(Player.EVENT_VIDEO_SIZE_CHANGED),null))
                item=MediaItem.Builder().setMediaId("unknown").build()
                assertNull(state.update(player,events(Player.EVENT_MEDIA_ITEM_TRANSITION),null))
            }finally{engine.release()}
        }
    }
    @Test fun newlyConnectedControllerUsesItsCurrentTrackSnapshot(){
        ui.runOnUiThread{
            val engine=ExoPlayer.Builder(ui.activity).build()
            try{
                val format=Format.Builder().setSampleMimeType("video/avc").setWidth(360).setHeight(640).build()
                val tracks=Tracks(listOf(Tracks.Group(TrackGroup(format),false,intArrayOf(C.FORMAT_HANDLED),booleanArrayOf(true))))
                val player=object:ForwardingPlayer(engine){
                    override fun getCurrentMediaItem()=MediaItem.Builder().setMediaId("restored-tall").build()
                    override fun getCurrentTracks()=tracks
                    override fun getVideoSize()=VideoSize(640,360)
                }
                val state=PlaybackOrientationState();state.restoreSnapshot(player)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,state.update(player,null,null))
            }finally{engine.release()}
        }
    }

}
