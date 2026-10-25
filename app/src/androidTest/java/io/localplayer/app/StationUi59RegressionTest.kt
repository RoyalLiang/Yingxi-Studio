package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StationUi59RegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun longPressFindingOffersPlaybackAndRoundedSelection() {
        val finding = StationFinding(MediaRef("ui59-found", "旧日放映.mp4", "fixture"), 0)
        var played = 0
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            Box(Modifier.width(320.dp)) { FindingCard(finding) { played++ } }
        } } }
        ui.onNodeWithTag("found:${finding.media.key}").performTouchInput { longClick() }
        ui.onNodeWithTag("found:${finding.media.key}").assertIsSelected()
        ui.onNodeWithText("播放影片").assertIsDisplayed().performClick()
        ui.onNodeWithTag("found:${finding.media.key}").assertIsNotSelected()
        ui.runOnIdle { assertEquals(1, played) }
    }

    @Test fun findingMenuAtLargeFontProvidesSealingWithoutPlaying() {
        val finding = StationFinding(MediaRef("ui59-seal", "片架前沿的短木签.mp4", "fixture"), 0)
        var played = 0
        var sealed = 0
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color=Paper) {
                    Box(Modifier.padding(20.dp).width(320.dp)) { FindingCard(finding, seal={sealed++}) { played++ } }
                }
            }
        } } }
        ui.onNodeWithContentDescription("影像操作 片架前沿的短木签").performClick()
        ui.onNodeWithText(sealActionLabel(false)).assertIsDisplayed()
        val frame = ui.onNodeWithTag("found-poster:${finding.media.key}", true).getUnclippedBoundsInRoot()
        assertEquals((frame.right.value-frame.left.value) / 1.6f, frame.bottom.value-frame.top.value, 1f)
        capture("finding-large")
        ui.onNodeWithText(sealActionLabel(false)).performClick()
        ui.runOnIdle { assertEquals(1, sealed); assertEquals(0, played) }
    }

    @Test fun registeredSourcesDoNotRepeatCountsOrReadOnlyCopy() {
        val models = ViewModelStore()
        val store = SourceStore(ui.activity)
        val source = io.localplayer.core.Source(label="UI59 云片架", protocol=io.localplayer.core.Protocol.ALIYUN, host="openapi.alipan.com")
        try {
            store.save(source,"",false)
            ui.runOnUiThread {
                val vm=LibraryViewModel(ui.activity.application).also{models.put("source",it)}
                ui.activity.setContent { PlayerTheme { SourcesCatalog(vm) } }
            }
            ui.onNodeWithText(source.label).assertIsDisplayed()
            ui.onNode(hasText("已登记",substring=true)).assertDoesNotExist()
            ui.onNode(hasText("只读授权",substring=true)).assertDoesNotExist()
            capture("sources")
        } finally { ui.runOnIdle{models.clear()}; store.delete(source.id) }
    }

    @Test fun keyboardFocusMarksTheRoundedCoverAndEnterPlays() {
        val finding=StationFinding(MediaRef("ui59-focus","键盘片匣.mp4","fixture"),0)
        var played=0
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
            Box(Modifier.width(320.dp)){FindingCard(finding){played++}}
        }}}
        fun corner()=ui.onNodeWithTag("found-poster:${finding.media.key}",true).captureToImage().asAndroidBitmap().let{it.getPixel(it.width/2,1)}
        val before=corner()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
        ui.onNodeWithTag("found:${finding.media.key}").assertIsFocused()
        assertNotEquals(before,corner())
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        ui.runOnIdle{assertEquals(1,played)}
    }

    @Test fun accessDropdownContainsEveryAvailableCloudChoice() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            var selected by remember { mutableStateOf("阿里云盘") }
            Box(Modifier.width(320.dp)) { ConnectionProtocolSelector(true, selected) { selected = it } }
        } } }
        ui.onNodeWithTag("connection-protocol-selector").performClick()
        ui.onNodeWithText("百度网盘").assertIsDisplayed().performClick()
        ui.onNodeWithTag("connection-protocol-selector").assertTextContains("百度网盘")
    }

    @Test fun archiveUsesShelfNamesWithoutExplanatorySmallPrint() {
        val models = ViewModelStore()
        try {
            ui.runOnUiThread {
                val app = ui.activity.application
                val files = LocalFilesViewModel(app).also { models.put("files", it) }
                val player = LibraryViewModel(app).also { models.put("player", it) }
                ui.activity.setContent { PlayerTheme { LocalFilesScreen(files, player, {}, {}, {}) } }
            }
            ui.onNodeWithTag("local-library-description").assertDoesNotExist()
            ui.onNodeWithText("由你选择可访问的文件夹，影片留在原处。").assertDoesNotExist()
            ui.onNodeWithText("我的片架").assertIsDisplayed()
            capture("archive")
        } finally { ui.runOnIdle { models.clear() } }
    }

    @Test fun diaryPerforationKeepsMovingAfterFirstSweep() {
        org.junit.Assume.assumeTrue("Requires enabled system animations",android.animation.ValueAnimator.areAnimatorsEnabled())
        ui.mainClock.autoAdvance = false
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            Box(Modifier.width(320.dp)) { StationNotice("值班札记", "拾荒者日记", "旧片架上的灯还亮着。") }
        } } }
        ui.mainClock.advanceTimeBy(9000)
        val first = strip()
        ui.mainClock.advanceTimeBy(3000)
        assertNotEquals("The diary strip must continue after its first sweep", first, strip())
    }

    private fun strip():Int {
        val image=ui.onNodeWithTag("station-film-perforation",true).captureToImage().asAndroidBitmap()
        var hash=1
        for(y in image.height*4/5 until image.height)for(x in 0 until image.width)hash=31*hash+image.getPixel(x,y)
        return hash
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { java.io.File(ui.activity.cacheDir,"ui59-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        finally { image.recycle() }
    }
}

// Run in its own instrumentation process with animator_duration_scale=0.
class StationReducedMotion59Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun diaryRemainsStillWhenSystemAnimationsAreDisabled(){
        org.junit.Assume.assumeFalse("Requires disabled system animations",android.animation.ValueAnimator.areAnimatorsEnabled())
        ui.mainClock.autoAdvance=false
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
            Box(Modifier.width(320.dp)){StationNotice("值班札记","拾荒者日记","木签留在片架前沿。")}
        }}}
        fun strip():Int{
            val image=ui.onNodeWithTag("station-film-perforation",true).captureToImage().asAndroidBitmap()
            var hash=1
            for(y in image.height*4/5 until image.height)for(x in 0 until image.width)hash=31*hash+image.getPixel(x,y)
            return hash
        }
        ui.mainClock.advanceTimeBy(9000)
        val first=strip()
        ui.mainClock.advanceTimeBy(3000)
        assertEquals(first,strip())
    }
}
