package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var value:Any?=null
        instrumentation.runOnMainSync { value=block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    @Test fun nativeDragPreviewDoesNotSeekThePlayingSessionUntilRelease() {
        val file=File(context.cacheDir,"seek-performance.mp4")
        check(file.isFile)
        val uri=Uri.fromFile(file)
        val media=MediaRef("preview-playback-${UUID.randomUUID()}","真实解码预览验证","本机文件",localUri=uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,media.title,0,media)
        var scenario:ActivityScenario<PlaybackActivity>?=null
        var controller:MediaController?=null
        try {
            scenario=ActivityScenario.launch(PlaybackActivity.intent(context,target))
            val future=main { MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync() }
            val player=future.get(10,TimeUnit.SECONDS);controller=player
            main{assertTrue("App controller must retain media queue commands",player.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))}
            try{ui.waitUntil(15_000){main{player.playbackState==Player.STATE_READY&&player.videoSize.width>0}}}
            catch(e:Throwable){android.util.Log.i("Polish55State",main{playbackDiagnostic(player)});throw e}
            main{player.pause();player.seekTo(2000)}
            ui.waitUntil(10_000){main{player.playbackState==Player.STATE_READY&&player.currentPosition in 1500..2500}}
            Thread.sleep(1000)
            ui.onNodeWithContentDescription("后退10秒").assertIsDisplayed()
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput {click(Offset(width*.18f,height*.22f))}
            ui.waitUntil(2000){ui.onAllNodesWithContentDescription("后退10秒").fetchSemanticsNodes().isEmpty()}
 // Let the independent reader warm while the video session remains paused.
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput {
                down(Offset(width*.3f,height*.34f));moveTo(Offset(width*.34f,height*.34f),300)
            }
            ui.waitUntil(2000){ui.onAllNodesWithContentDescription("进度预览").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("前进10秒").assertDoesNotExist()
            ui.onNodeWithContentDescription("后退10秒").assertDoesNotExist()
            val first=ui.onNodeWithContentDescription("进度预览").fetchSemanticsNode().boundsInRoot
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput { moveTo(Offset(width*.39f,height*.34f),300) }
            ui.waitForIdle()
            val second=ui.onNodeWithContentDescription("进度预览").fetchSemanticsNode().boundsInRoot
            assertTrue("Preview must follow the moving seek thumb",second.center.x>first.center.x+40)
            val thumb=ui.onNodeWithTag("progress-thumb",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("Preview belongs above the timeline",second.bottom<thumb.top)
            ui.onNodeWithText("拖动以调整进度").assertDoesNotExist()
            ui.onNodeWithText("约 ",substring=true).assertDoesNotExist()
            ui.onNodeWithText("正在生成预览…").assertDoesNotExist()
            val screen=ui.onNodeWithTag("playback-gesture-surface").fetchSemanticsNode().boundsInRoot
            for(fraction in listOf(.27f,.55f)) {
                ui.onNodeWithTag("playback-gesture-surface").performTouchInput { moveTo(Offset(width*fraction,height*.34f),300) }
                ui.waitForIdle()
                val edge=ui.onNodeWithContentDescription("进度预览").fetchSemanticsNode().boundsInRoot
                assertTrue("Preview must remain inside the screen at both seek extremes",edge.left>=screen.left&&edge.right<=screen.right)
            }
            assertTrue("Extracting a preview must not seek or resume the playing session",main{!player.isPlaying&&player.currentPosition in 1500..2500})
            instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/home-polish-real-preview.png").use{
                java.io.FileInputStream(it.fileDescriptor).use{stream->stream.readBytes()}
            }
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput { up() }
            ui.waitUntil(5000){main{player.currentPosition>2500}}
            ui.onNodeWithContentDescription("前进10秒").assertDoesNotExist()
            ui.onNodeWithContentDescription("后退10秒").assertDoesNotExist()
            assertNull(main{player.playerError})
        } finally {
            main { controller?.let{it.stop();it.clearMediaItems();it.release()} }
            scenario?.close();PlaybackRequests.remove(target.playId)
            HistoryStore(context).remove(media.key);MediaThumbnails.remove(context,media)
        }
    }
}
