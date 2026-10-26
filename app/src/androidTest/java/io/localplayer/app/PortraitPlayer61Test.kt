package io.localplayer.app

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PortraitPlayer61Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun portraitKeepsTrackIconsAboveLargeTextControlsAndSwipesWithinEpisodeBounds(){
        main{ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(5000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        val future=main{MediaController.Builder(ui.activity,SessionToken(ui.activity,ComponentName(ui.activity,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        try{
            val fixture=java.io.File(ui.activity.cacheDir,"direction-portrait.mp4").takeIf{it.isFile}
            main{
                player.setMediaItems((0..2).map{MediaItem.Builder().setUri(fixture?.let(android.net.Uri::fromFile)?.toString()?:"file:///unprepared-episode-$it.mp4").setMediaId("episode-$it").build()},1,0)
                if(fixture!=null)player.prepare()
                ui.activity.setContent{PlayerTheme{CompositionLocalProvider(LocalDensity provides Density(ui.activity.resources.displayMetrics.density,1.8f)){
                    FullscreenPlayer(player,"竖屏选集",null,{},{},{},{},false,null,{},{})
                }}}
            }
            ui.onNodeWithTag("portrait-episode-bar").assertIsDisplayed()
            ui.onNodeWithText("选集 2/3").assertIsDisplayed()
            ui.onNodeWithContentDescription("暂停").assertDoesNotExist()
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            ui.onNodeWithContentDescription("前进10秒").assertDoesNotExist()
            ui.onNodeWithContentDescription("后退10秒").assertDoesNotExist()
            val controls=ui.onNodeWithTag("portrait-playback-controls").fetchSemanticsNode().boundsInRoot
            val rail=ui.onNodeWithTag("portrait-track-actions").fetchSemanticsNode().boundsInRoot
            assertTrue("Track icons leave the bottom controls clear",rail.bottom<=controls.top)
            for(tag in listOf("player-audio","player-subtitles"))ui.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            ui.onNodeWithText("音轨").assertDoesNotExist();ui.onNodeWithText("字幕").assertDoesNotExist()
            val surface=ui.onNodeWithTag("playback-gesture-surface")
            fun swipe(up:Boolean){surface.performTouchInput{swipe(Offset(width*.5f,height*(if(up).65f else .3f)),Offset(width*.5f,height*(if(up).3f else .65f)),350)}}
            swipe(true);ui.waitUntil(3000){main{player.currentMediaItemIndex==2}}
            ui.onNodeWithContentDescription("下一集").assertIsNotEnabled()
            swipe(true);assertEquals(2,main{player.currentMediaItemIndex})
            swipe(false);ui.waitUntil(3000){main{player.currentMediaItemIndex==1}}
            java.io.File(ui.activity.getExternalFilesDir(null),"portrait-player61.png").outputStream().use{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ui.onNodeWithContentDescription("选集").performClick()
            java.io.File(ui.activity.getExternalFilesDir(null),"portrait-queue61.png").outputStream().use{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ui.onNodeWithTag("playback-queue-drawer").assertIsDisplayed()
        }finally{main{player.stop();player.clearMediaItems();player.release()}}
    }
}
