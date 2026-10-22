package io.localplayer.app

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import android.content.res.Configuration
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RoomEntryRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun playerStartsFilledAndStillOffersTheCompleteFrame() {
        lateinit var view: PlayerView
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            FullscreenPlayer(null,"起播画幅",null,{},{},{},{},false,null,{},{view=it})
        } } }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM,view.resizeMode) }
        ui.onNodeWithContentDescription("画面比例").performClick()
        ui.runOnIdle { assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT,view.resizeMode) }
        ui.onNodeWithContentDescription("画面比例").performClick()
        ui.runOnIdle { assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM,view.resizeMode) }
    }

    @Test fun playbackStartingWindowIsLandscapeBeforeSessionOrMetadata() {
        val info=ui.activity.packageManager.getActivityInfo(ComponentName(ui.activity,PlaybackActivity::class.java),0)
        assertEquals("The launch window must not inherit the portrait library",ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,info.screenOrientation)
        // A server that deliberately sends no headers keeps video dimensions unknown.
        val gate=CountDownLatch(1)
        ServerSocket(0).use { server ->
            val worker=Thread { runCatching { server.accept().use { gate.await(15,TimeUnit.SECONDS) } } }.apply { isDaemon=true;start() }
            val uri=Uri.parse("http://127.0.0.1:${server.localPort}/waiting.mp4")
            val media=MediaRef("room-waiting","等待片库响应","测试避难所",localUri=uri.toString())
            try {
                ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(ui.activity,PlayTarget(uri,media.title,0,media))).use { scenario ->
                    scenario.onActivity {
                        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,it.requestedOrientation)
                        assertEquals(Configuration.ORIENTATION_LANDSCAPE,it.resources.configuration.orientation)
                    }
                    ui.onNodeWithTag("station-decoding").assertIsDisplayed()
                }
            } finally { gate.countDown();server.close();worker.join(2000) }
        }
    }

    @Test fun appNameStaysOnOneBaselineAndRoomCaptionKeepsItsSize() {
        val name=ui.onNodeWithText("映汐",useUnmergedTree=true)
        val room=ui.onNodeWithTag("home-brand-caption",useUnmergedTree=true)
        val nameLayout=mutableListOf<TextLayoutResult>()
        val roomLayout=mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(nameLayout)}
        room.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(roomLayout)}
        val nameBounds=name.fetchSemanticsNode().boundsInRoot
        val roomBounds=room.fetchSemanticsNode().boundsInRoot
        assertEquals(13.sp,roomLayout.single().layoutInput.style.fontSize)
        assertEquals(nameBounds.top+nameLayout.single().firstBaseline,roomBounds.top+roomLayout.single().firstBaseline,2f)
        assertTrue(roomBounds.left>=nameBounds.right)
        capture("home")
    }

    @Test fun narrowLargeTextHeaderAndShelterRouteStayReadable() {
        val vm=ViewModelProvider(ui.activity)[LibraryViewModel::class.java]
        val files=ViewModelProvider(ui.activity)[LocalFilesViewModel::class.java]
        ui.runOnUiThread { ui.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(ui.activity.resources.displayMetrics.density,2f)) {
                PlayerTheme { Box(Modifier.width(320.dp)) { AppShell(vm,files,{},{},{}) } }
            }
        } }
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("home-brand-caption",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        capture("home-large")
        assertEquals(1,layouts.single().lineCount)
        assertFalse(layouts.single().isLineEllipsized(0))
        assertTrue(layouts.single().getLineRight(0)<=layouts.single().size.width+1f)
        assertEquals(13.sp,layouts.single().layoutInput.style.fontSize)
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithTag("add-outpost").assertIsDisplayed();ui.onNodeWithTag("add-tower").assertIsDisplayed()
        capture("shelters-large")
        ui.onNodeWithTag("add-outpost").performClick()
        ui.onNodeWithTag("connection-title").assertTextEquals("连接幸存者据点");ui.onNodeWithText("弥途 · 避难所路标").assertDoesNotExist()
        ui.onNodeWithText("连接来源").assertDoesNotExist()
        capture("connection-large")
    }

    @Test fun shelterRouteUsesTheWorldbookTerms() {
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithTag("add-outpost").assertIsDisplayed();ui.onNodeWithTag("add-tower").assertIsDisplayed()
        ui.onNodeWithText("点击右上角新建",substring=true).assertDoesNotExist()
        capture("shelters")
        ui.onNodeWithTag("add-outpost").performClick()
        ui.onNodeWithTag("connection-title").assertIsDisplayed()
        ui.onNodeWithTag("connection-title").assertTextEquals("连接幸存者据点");ui.onNodeWithText("弥途 · 避难所路标").assertDoesNotExist()
        capture("connection")
    }

    private fun capture(name:String) {
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.getExternalFilesDir(null),"room27-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
