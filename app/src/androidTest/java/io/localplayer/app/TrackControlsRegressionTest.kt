package io.localplayer.app

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackControlsRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var value:Result<T>?=null;ui.runOnUiThread{value=runCatching(block)};return value!!.getOrThrow()}
    @Test fun audioAndSubtitlePanelsAreDirectAndKeepTheirOwnChoicesAtLargeFont() {
        lateinit var pending:com.google.common.util.concurrent.ListenableFuture<MediaController>
        ui.runOnUiThread{pending=MediaController.Builder(ui.activity.applicationContext,SessionToken(ui.activity.applicationContext,ComponentName(ui.activity,PlaybackService::class.java))).buildAsync()}
        val controller=pending.get(10,TimeUnit.SECONDS)
        val fixture=java.io.File(ui.activity.cacheDir,"track58-languages.mkv");assertTrue("Seed multitrack language fixture",fixture.isFile)
        try{
            main{controller.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(fixture)));controller.prepare();controller.pause()}
            ui.waitUntil(15000){main{controller.playerError!=null||(controller.currentTracks.groups.count{it.type==C.TRACK_TYPE_AUDIO}>=2&&controller.currentTracks.groups.count{it.type==C.TRACK_TYPE_TEXT}>=2)}}
            main{assertNull(controller.playerError)}
            for(orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)){
                ui.runOnUiThread{ui.activity.requestedOrientation=orientation}
                ui.waitUntil(5000){(ui.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT)==(orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)}
                ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                    CompositionLocalProvider(LocalDensity provides Density(ui.activity.resources.displayMetrics.density,1.8f)){
                        FullscreenPlayer(controller,"轨道面板测试",null,{}, {}, {}, {},false,null,{}, {})
                    }
                }}}
                val start=main{controller.currentPosition}
                main{controller.volume=0f;controller.play()}
                ui.waitUntil(10000){main{controller.playerError!=null||controller.currentPosition>start+400}}
                main{assertNull(controller.playerError);controller.pause()}
                ui.onNodeWithTag("player-audio").assertIsDisplayed().performClick()
                ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("英语",substring=true))
                ui.onNodeWithText("英语",substring=true).assertIsDisplayed()
                ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("中文",substring=true))
                ui.onNodeWithText("中文",substring=true).assertIsDisplayed()
                ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("载入音轨文件"))
                ui.onNodeWithText("载入音轨文件").assertIsDisplayed()
                ui.onNodeWithText("关闭字幕").assertDoesNotExist()
                ui.onNodeWithText("按片名搜配音音轨 · 必应网页").assertDoesNotExist()
                ui.onNodeWithTag("playback-settings-list").performScrollToIndex(0)
                ui.onNodeWithContentDescription("关闭播放选项").performClick()
                ui.waitUntil(5000){runCatching{ui.onNodeWithTag("audio-subtitle-panel").assertDoesNotExist()}.isSuccess}
                ui.onNodeWithTag("player-subtitles").assertIsDisplayed().performClick()
                ui.onNodeWithText("关闭字幕").assertExists()
                ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("英语",substring=true))
                ui.onNodeWithText("英语",substring=true).assertIsDisplayed()
                ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("中文",substring=true))
                ui.onNodeWithText("中文",substring=true).assertIsDisplayed()
                java.io.File(ui.activity.cacheDir,"track58-panel-$orientation.png").outputStream().use{out->androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)}
                ui.onNodeWithText("载入音轨文件").assertDoesNotExist()
                ui.onNodeWithText("按片名搜字幕 · 必应网页").assertDoesNotExist()
                ui.onNodeWithTag("playback-settings-list").performScrollToIndex(0)
                ui.onNodeWithContentDescription("关闭播放选项").performClick()
                ui.waitUntil(5000){runCatching{ui.onNodeWithTag("audio-subtitle-panel").assertDoesNotExist()}.isSuccess}
                ui.waitForIdle();Thread.sleep(250)
                java.io.File(ui.activity.cacheDir,"track58-controls-$orientation.png").outputStream().use{out->androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)}
                for(tag in listOf("player-audio","player-subtitles")){
                    val bounds=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    val density=ui.activity.resources.displayMetrics.density
                    assertTrue("Direct track controls retain48dp touch height",bounds.height/density>=47f)
                    assertTrue("Direct track controls fit visible width",bounds.left>=0&&bounds.right<=ui.activity.resources.displayMetrics.widthPixels)
                }
            }
        }finally{ui.runOnUiThread{controller.volume=1f;controller.stop();controller.clearMediaItems();controller.release();ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}}
    }
    @Test fun defaultSourceCanBeChangedAndPersistedInSettings() {
        val prefs=EnrichmentPreferences(ui.activity);val old=prefs.subtitleSource
        try{
            prefs.subtitleSource="subhd"
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{EnrichmentSettings()}}}
            ui.onNodeWithTag("subtitle-source-shooter").performClick()
            assertEquals("shooter",EnrichmentPreferences(ui.activity).subtitleSource)
            ui.onNodeWithTag("subtitle-source-subhd").performClick()
            assertEquals("subhd",EnrichmentPreferences(ui.activity).subtitleSource)
        }finally{prefs.subtitleSource=old}
    }
}
