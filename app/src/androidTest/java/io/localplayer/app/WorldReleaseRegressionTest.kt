package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorldReleaseRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun homeArchiveEntranceOpensTheIllustratedCharactersWithoutEnteringSettings() {
        ui.onNodeWithTag("home-archives").assertIsDisplayed().performClick()
        ui.onNodeWithTag("station-world").assertIsDisplayed()
        ui.onNodeWithTag("world-person:axi").assertIsDisplayed().assertIsSelected()
        ui.onNodeWithTag("world-person:chideng").performClick().assertIsSelected()
        capture("home-archives")
        ui.onNodeWithTag("archives-back").performClick()
        ui.onNodeWithTag("home-archives").assertIsDisplayed()
    }
    @Test fun longCurrentLetterScrollsWithoutMovingTheCheckButton() {
        ui.runOnUiThread { val vm=SettingsViewModel(ui.activity.application);ui.activity.setContent { PlayerTheme {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) { VersionSettingsPage(vm,true,{},{},{}) }
            }
        } } }
        val button=ui.onNodeWithTag("check-updates").assertIsDisplayed()
        val before=button.fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("current-letter-scroll").performTouchInput{swipeUp()}
        assertEquals(before,button.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithText("前往发布页").assertDoesNotExist()
        ui.onNodeWithTag("version-more").performClick()
        ui.onNodeWithText("前往发布页").assertIsDisplayed()
    }
    @Test fun worldGraphAndCharactersAreInteractive() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme { StationWorldPage{} } } }
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-node:archive"))
        ui.onNodeWithTag("world-node:archive").performClick().assertIsSelected()
        ui.onNodeWithTag("world-place-detail").assertExists()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-person:mitu"))
        ui.onNodeWithTag("world-person:mitu").performClick().assertIsSelected()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-quote"))
        val first=ui.onNodeWithTag("world-quote").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        ui.onNodeWithTag("world-quote").performClick()
        val second=ui.onNodeWithTag("world-quote").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assertNotEquals(first,second)
        capture("person")
    }
    @Test fun graphNodesRemainInsideNarrowLargeTextViewport() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f)) {Box(Modifier.width(320.dp).fillMaxHeight()){StationWorldPage{}}}
        } } }
        val screen=ui.onNodeWithTag("station-world").fetchSemanticsNode().boundsInRoot
        listOf("station","salt","archive","sea","beacon").forEach { id ->
            ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-node:$id"))
            val node=ui.onNodeWithTag("world-node:$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(node.left>=screen.left&&node.right<=screen.right)
        }
    }
    @Test fun worldNodeLabelsAreCenteredWithinTheirCardsInBothLanguages() {
        for(language in listOf("zh","en")) {
            ui.runOnUiThread {ui.activity.setContent {PlayerTheme {
                val d=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f),
                    androidx.compose.ui.platform.LocalConfiguration provides android.content.res.Configuration(ui.activity.resources.configuration).apply{setLocale(java.util.Locale.forLanguageTag(language))}) {
                    Box(Modifier.width(320.dp).fillMaxHeight()){StationWorldPage{}}
                }
            }}}
            for(id in listOf("station","salt","archive","sea","beacon")) {
                ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-node:$id"))
                val card=ui.onNodeWithTag("world-node:$id").fetchSemanticsNode().boundsInRoot
                val label=ui.onNodeWithTag("world-node-label:$id",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(card.center.x-label.center.x)<2f)
                assertTrue(kotlin.math.abs(card.center.y-label.center.y)<2f)
                ui.onNodeWithTag("world-node-label:$id",useUnmergedTree=true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(layouts)
                        assertEquals(androidx.compose.ui.text.style.TextAlign.Center,layouts.single().layoutInput.style.textAlign)
                    }
            }
            capture("graph-centered-$language")
        }
    }
    @Test fun letterParserKeepsAttributedQuotesAndSeparateRepairs() {
        val raw="## 中文\n\n【修缮记录】\n1. 修好柜台。\n\n> 灯还亮着。——迟灯\n\n## English\n\n[Repairs]\n1. Repair the counter.\n\n> Keep the light on. — Chideng"
        val zh=releaseLetterBlocks(raw,"zh");val en=releaseLetterBlocks(raw,"en")
        assertEquals(listOf(LetterKind.Heading,LetterKind.Repair,LetterKind.Quote),zh.map{it.kind})
        assertTrue(zh.last().text.contains("迟灯"));assertFalse(zh.any{it.text.contains("Chideng")})
        assertTrue(en.last().text.contains("Chideng"))
    }
    private fun capture(name:String) {
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.takeScreenshot().let{b->java.io.File(ui.activity.cacheDir,"world19-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    }
}
