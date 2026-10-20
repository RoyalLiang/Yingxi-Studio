package io.localplayer.app

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackPolishRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    private fun landscape() {
        ui.runOnUiThread { ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(5000) { ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
    }

    private fun player(fontScale:Float=1f) {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            CompositionLocalProvider(LocalDensity provides Density(ui.activity.resources.displayMetrics.density,fontScale)) {
                FullscreenPlayer(null,"控件回归",null,{},{},{},{},false,null,{},{})
            }
        } } }
    }

    @Test fun lockedControlsAppearOnlyAfterATapAndHideAgain() {
        landscape();player()
        ui.onNodeWithContentDescription("锁定方向与控件").performClick()
        ui.onNodeWithContentDescription("解锁方向与控件").assertDoesNotExist()
        ui.onNodeWithTag("playback-gesture-surface").performTouchInput { click(center) }
        ui.onNodeWithContentDescription("解锁方向与控件").assertIsDisplayed()
        ui.mainClock.advanceTimeBy(3100)
        ui.onNodeWithContentDescription("解锁方向与控件").assertDoesNotExist()
        ui.onNodeWithTag("playback-gesture-surface").performTouchInput { click(center) }
        capture("unlock")
        ui.onNodeWithContentDescription("解锁方向与控件").performClick()
        ui.onNodeWithContentDescription("锁定方向与控件").assertIsDisplayed()
    }

    @Test fun toolbarKeepsTimeLeftAndActionsAtRightIncludingLargeText() {
        landscape()
        listOf(1f,1.5f).forEach { scale ->
            player(scale)
            val toolbar=ui.onNodeWithTag("playback-toolbar").fetchSemanticsNode().boundsInRoot
            val time=ui.onNodeWithTag("playback-time").fetchSemanticsNode().boundsInRoot
            val actions=ui.onNodeWithTag("playback-actions").fetchSemanticsNode().boundsInRoot
            val rotation=ui.onNodeWithContentDescription("旋转屏幕").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(time.left<actions.left)
            assertTrue("Actions must reach the toolbar right edge",kotlin.math.abs(actions.right-toolbar.right)<2)
            assertTrue("Rotation should end at the right edge",kotlin.math.abs(rotation.right-toolbar.right)<2)
            ui.onNodeWithTag("playback-speed").assertIsDisplayed()
            ui.onNodeWithContentDescription("播放设置").assertIsDisplayed()
            ui.onNodeWithContentDescription("画面比例").assertIsDisplayed()
            ui.onNodeWithText("选集 1/0").assertIsDisplayed()
        }
    }

    @Test fun portraitLargeTextKeepsEveryActionInsideTheToolbar() {
        ui.runOnUiThread { ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(5000) { ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT }
        player(1.5f)
        val toolbar=ui.onNodeWithTag("playback-toolbar").fetchSemanticsNode().boundsInRoot
        listOf(ui.onNodeWithTag("playback-speed"),ui.onNodeWithContentDescription("播放设置"),
            ui.onNodeWithContentDescription("画面比例"),ui.onNodeWithText("选集 1/0"),ui.onNodeWithContentDescription("旋转屏幕")).forEach { node ->
            val bounds=node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right<=toolbar.right+2&&bounds.left>=toolbar.left)
            assertTrue(bounds.bottom<=toolbar.bottom+2)
        }
        capture("portrait-large")
    }

    @Test fun queueBlankVideoDismissesButPanelBlankDoesNot() {
        landscape()
        val show=mutableStateOf(true)
        val items=listOf(MediaItem.Builder().setMediaMetadata(MediaMetadata.Builder().setTitle("第一集").build()).build())
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            if(show.value) PlaybackQueueSheet(items,0,false,{show.value=false},{})
        } } }
        ui.onNodeWithText("名称升序 · 同名按可用创建时间排序").assertDoesNotExist()
        ui.onNodeWithTag("playback-queue-drawer").performTouchInput { click(Offset(width-4f,4f)) }
        ui.onNodeWithTag("playback-queue-drawer").assertIsDisplayed()
        ui.onNodeWithTag("queue-dismiss-backdrop").performTouchInput { click(Offset(width*.08f,height*.5f)) }
        ui.onNodeWithTag("playback-queue-drawer").assertDoesNotExist()
    }

    @Test fun settingsBlankVideoDismissesButPanelBlankDoesNot() {
        landscape()
        lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
        ui.runOnUiThread { future=MediaController.Builder(ui.activity,SessionToken(ui.activity,ComponentName(ui.activity,PlaybackService::class.java))).buildAsync() }
        val controller=future.get(10,TimeUnit.SECONDS)
        val show=mutableStateOf(true)
        try {
            ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
                if(show.value)PlaybackOptions(controller,{show.value=false},{})
            } } }
            ui.onNodeWithTag("audio-subtitle-panel").performTouchInput { click(Offset(4f,4f)) }
            ui.onNodeWithTag("audio-subtitle-panel").assertIsDisplayed()
            ui.onNodeWithTag("settings-dismiss-backdrop").performTouchInput { click(Offset(width*.08f,height*.5f)) }
            ui.onNodeWithTag("audio-subtitle-panel").assertDoesNotExist()
        } finally { ui.runOnUiThread { controller.release() } }
    }

    @Test fun realVerticalGesturesPutBrightnessAndVolumeOnOppositeEdges() {
        val context=ui.activity.applicationContext
        val file=java.io.File(context.cacheDir,"seek-performance.mp4")
        check(file.isFile)
        val uri=android.net.Uri.fromFile(file)
        val media=MediaRef("side-hud-${java.util.UUID.randomUUID()}","侧边刻度实播","本机文件",localUri=uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,media.title,0,media)
        val audio=context.getSystemService(android.media.AudioManager::class.java)
        val original=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var scenario:androidx.test.core.app.ActivityScenario<PlaybackActivity>?=null
        var controller:MediaController?=null
        try {
            scenario=androidx.test.core.app.ActivityScenario.launch(PlaybackActivity.intent(context,target))
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            ui.runOnUiThread { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync() }
            val player=future.get(10,TimeUnit.SECONDS);controller=player
            ui.waitUntil(15000) { main { player.playbackState==androidx.media3.common.Player.STATE_READY&&player.videoSize.width>0 } }
            main { player.pause();player.seekTo(2000) }
            val surface=ui.onNodeWithTag("playback-gesture-surface")
            surface.performTouchInput { swipe(Offset(width*.2f,height*.58f),Offset(width*.2f,height*.28f),350) }
            val bounds=surface.fetchSemanticsNode().boundsInRoot
            val light=ui.onNodeWithTag("brightness-hud").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Brightness must leave the center clear",light.right<bounds.width*.3f)
            capture("brightness")
            surface.performTouchInput { swipe(Offset(width*.8f,height*.58f),Offset(width*.8f,height*.28f),350) }
            val volume=ui.onNodeWithTag("volume-hud").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Volume must leave the center clear",volume.left>bounds.width*.7f)
            ui.onNodeWithTag("brightness-hud").assertDoesNotExist()
            assertNull(main { player.playerError })
            capture("volume")
        } finally {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,original,0)
            main { controller?.let{it.stop();it.clearMediaItems();it.release()} }
            scenario?.close();PlaybackRequests.remove(target.playId)
            HistoryStore(context).remove(media.key);MediaThumbnails.remove(context,media)
        }
    }

    private fun <T> main(block:()->T):T {
        var value:Result<T>?=null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{value=runCatching(block)}
        return value!!.getOrThrow()
    }

    private fun capture(name:String) {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("screencap -p /sdcard/Download/playback15-$name.png").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }
}
