package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerStyleTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("screencap -p /sdcard/Download/style-$name.png").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }

    @Test fun glassSamplesTheBackdropAtInitialSizeAndAfterResize() {
        val width=mutableStateOf(260.dp)
        ui.activity.setContent {
            PlayerTheme {
                val scene=rememberGraphicsLayer()
                Box(Modifier.fillMaxSize()) {
                    // Only the glass draws this recorded scene, so underlying pixels cannot mask a broken effect.
                    Box(Modifier.fillMaxSize().drawWithContent { scene.record { this@drawWithContent.drawContent() } }) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Red))
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Blue))
                        }
                    }
                    CompositionLocalProvider(LocalGlassBackdrop provides scene) {
                        GlassSurface(Modifier.align(androidx.compose.ui.Alignment.Center).size(width.value,120.dp).testTag("glass-sample"),dark=true){}
                    }
                }
            }
        }
        fun assertBackdrop() {
            ui.waitForIdle()
            // Capture the compositor output: layer RenderEffects do not appear in software Canvas captures.
            val bounds=ui.onNodeWithTag("glass-sample").fetchSemanticsNode().boundsInRoot
            val origin=IntArray(2)
            ui.runOnIdle{ui.activity.window.decorView.getLocationOnScreen(origin)}
            val y=origin[1]+bounds.center.y.toInt()
            var left=Color.Transparent
            var right=Color.Transparent
            // Compose idleness precedes SurfaceFlinger composition. Poll actual pixels, retaining
            // the effect assertions so a persistently missing backdrop still fails.
            ui.waitUntil(5_000) {
                val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    left=Color(image.getPixel(origin[0]+(bounds.left+bounds.width*.25f).toInt(),y))
                    right=Color(image.getPixel(origin[0]+(bounds.left+bounds.width*.75f).toInt(),y))
                    left.red-left.blue>.1f && right.blue-right.red>.1f
                } finally { image.recycle() }
            }
            assertTrue("left backdrop remains red: $left",left.red-left.blue>.1f)
            assertTrue("right backdrop remains blue: $right",right.blue-right.red>.1f)
        }
        assertBackdrop()
        ui.runOnIdle{width.value=320.dp}
        assertBackdrop()
        capture("glass-resized")
    }

    @Test fun compactResumeHandlesLongTitlesAndLargeText() {
        val title = "海边的漫长夏日 · 特别收藏版 · 第十二集 2160p.mkv"
        val entry = HistoryEntry(MediaRef("style-preview", title, "私人片库"), 1_245_000, 6_480_000, 0, false)
        var opened = 0
        for (fontScale in listOf(1f, 1.5f)) {
            ui.runOnUiThread {
                ui.activity.setContent {
                    PlayerTheme {
                        CompositionLocalProvider(LocalDensity provides Density(ui.activity.resources.displayMetrics.density, fontScale)) {
                            Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding().padding(top=24.dp)) {
                                ContinueCard(entry) { opened++ }
                            }
                        }
                    }
                }
            }
            val bounds = ui.onNodeWithTag("continue-player").assertHasClickAction().fetchSemanticsNode().boundsInRoot
            assertTrue("resume card must remain compact", bounds.height / ui.density.density <= 100f)
            ui.onNodeWithText(io.localplayer.core.mediaDisplayTitle(title)).assertIsDisplayed()
            capture("resume-${(fontScale*100).toInt()}")
            ui.onNodeWithTag("continue-player").performClick()
        }
        assertEquals(2, opened)
    }

    @Test fun connectionSegmentsKeepHostAndExposeScrollableForm() {
        capture("home")
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithContentDescription("添加避难所").performClick()
        ui.onNodeWithText("SMB").assertIsSelected()
        val host = (hasText("主机 IP") or hasText("服务器地址")) and hasSetTextAction()
        ui.onNode(host).performTextInput("nas.local")
        ui.onNodeWithText("SMB").performClick()
        ui.onNode(host).assertTextContains("nas.local")
        ui.onNodeWithText("SFTP").performClick()
        ui.onNodeWithText("SFTP").assertIsSelected()
        ui.onNode(host).assertTextContains("nas.local")
        ui.onNodeWithTag("connection-form").performScrollToNode(hasText("线路设置"))
        ui.onNodeWithText("线路设置").performClick()
        ui.onNodeWithTag("connection-form").performScrollToNode(hasText("端口") and hasSetTextAction())
        ui.onNode(hasText("端口") and hasSetTextAction()).assertTextContains("22")
        ui.onNodeWithTag("connection-form").performScrollToNode(hasText("连接并浏览"))
        ui.onNodeWithText("连接并浏览").assertIsDisplayed()
        ui.onNodeWithTag("connection-form").performScrollToNode(hasContentDescription("显示密码"))
        ui.onNodeWithContentDescription("显示密码").performClick()
        ui.onNodeWithContentDescription("隐藏密码").assertIsDisplayed()
        capture("connection")
        ui.onNodeWithTag("connection-form").performScrollToIndex(0)
        capture("connection-top")
    }

    @Test fun tabAndTrackSelectionsExposeTheirState() {
        ui.onAllNodesWithText("拾荒记录").filter(hasClickAction()).onFirst().performClick()
        capture("history")
        ui.onNodeWithText("放映站").performClick()
        ui.onNodeWithText("映汐").assertIsDisplayed()
        ui.runOnUiThread {
            ui.activity.setContent {
                PlayerTheme {
                    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF202128)).statusBarsPadding().padding(20.dp)) {
                        TrackChoice("中文 · AAC", true) {}
                        Spacer(Modifier.height(8.dp))
                        TrackChoice("日语 · AAC", false) {}
                    }
                }
            }
        }
        ui.onNodeWithText("中文 · AAC").assertIsSelected().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithText("日语 · AAC").assertIsNotSelected()
        capture("selections")
    }

    @Test fun homeResumeAndGlassRemainReadableOverContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = HistoryStore(context)
        val keys = (1..8).map { "style-preview-$it" }
        try {
            keys.forEachIndexed { index, key ->
                val ref = MediaRef(key, "海边的夏日 · 第${index+1}集.mkv", "私人片库")
                history.update(ref, history.token(key), 1_245_000, 6_480_000, false)
            }
            ui.runOnIdle { androidx.lifecycle.ViewModelProvider(ui.activity)[LibraryViewModel::class.java].refreshHistory() }
            ui.onNodeWithTag("continue-player").assertIsDisplayed()
            capture("home-resume")
            ui.onAllNodesWithText("拾荒记录").filter(hasClickAction()).onFirst().performClick()
            capture("history-glass")
        } finally {
            keys.forEach(history::remove)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun playbackSheetSupportsLargeTextAndRealSelections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PlaybackPreferences(context)
        val previousSpeed = prefs.speed
        val previousSubtitles = prefs.subtitlesDisabled
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
        ui.runOnUiThread {
            future = androidx.media3.session.MediaController.Builder(context,
                androidx.media3.session.SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
        try {
            ui.runOnUiThread {
                ui.activity.setContent {
                    PlayerTheme {
                        CompositionLocalProvider(LocalDensity provides Density(ui.activity.resources.displayMetrics.density, 1.5f)) {
                            PlaybackOptions(controller, {}, {})
                        }
                    }
                }
            }
            ui.onNodeWithText("播放速度").assertDoesNotExist()
            ui.onNodeWithText("1.5×").assertDoesNotExist()
            val header = ui.onNodeWithText("音轨与字幕").captureToImage().toPixelMap()
            var brightPixels = 0
            for (y in 0 until header.height) for (x in 0 until header.width) {
                val pixel = header[x,y]
                if (pixel.red > .65f && pixel.green > .65f && pixel.blue > .65f) brightPixels++
            }
            assertTrue("dark sheet headings need visible contrast", brightPixels > header.width*header.height/100)
            capture("playback-settings-large")
            ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("关闭字幕"))
            ui.onNodeWithText("关闭字幕").performClick()
            ui.onNodeWithText("关闭字幕").assertIsSelected()
            assertEquals(previousSpeed, prefs.speed)
            assertTrue(prefs.subtitlesDisabled)
        } finally {
            ui.runOnUiThread { controller.release() }
            prefs.speed = previousSpeed
            prefs.subtitlesDisabled = previousSubtitles
        }
    }
}
