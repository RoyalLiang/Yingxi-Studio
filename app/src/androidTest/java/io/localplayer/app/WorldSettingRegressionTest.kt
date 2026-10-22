package io.localplayer.app

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class WorldSettingRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    private fun dictionary() {
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-dictionary-toggle"))
        ui.onNodeWithTag("world-dictionary-toggle").performClick()
    }

    @Test fun dictionarySeparatesEstablishedFactsRumorsAndExtensions() {
        showWorld(Locale.SIMPLIFIED_CHINESE)
        capture("map")
        dictionary()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-term:long_tide"))
        ui.onNode(hasText("演进提案") and hasAnyAncestor(hasTestTag("world-term:long_tide")),useUnmergedTree=true).assertExists()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-term:archive"))
        ui.onNode(hasText("正式设定 · 站内传闻",substring=true) and hasAnyAncestor(hasTestTag("world-term:archive")),useUnmergedTree=true).assertExists()
        capture("dictionary-rumor")
    }

    @Test fun dictionaryUsesEnglishDefinitionsAndProposalLabels() {
        showWorld(Locale.ENGLISH)
        dictionary()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-term:awakened"))
        ui.onNode(hasText("Proposal") and hasAnyAncestor(hasTestTag("world-term:awakened")),useUnmergedTree=true).assertExists()
        ui.onNodeWithText("The Awakened").assertExists()
        capture("dictionary-english")
    }

    @Test fun dictionaryWrapsAtLargeFontOnNarrowScreen() {
        val config=Configuration(ui.activity.resources.configuration).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
        ui.runOnUiThread { ui.activity.setContent { CompositionLocalProvider(LocalConfiguration provides config) { PlayerTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) { StationWorldPage(embedded=true){} }
            }
        } } } }
        dictionary()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-term:spores"))
        val page=ui.onNodeWithTag("station-world").fetchSemanticsNode().boundsInRoot
        val term=ui.onNodeWithTag("world-term:spores").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(term.left>=page.left && term.right<=page.right)
        capture("dictionary-large-font")
    }

    @Test fun themedEntryNamesKeepTheirFunctionalMeaning() {
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithTag("add-source").assertExists()
        ui.onNodeWithTag("add-tower").assertDoesNotExist()
        ui.onNodeWithText("幸存者据点").assertDoesNotExist()
 ui.onNodeWithText("信号塔").assertDoesNotExist()
 ui.onNodeWithTag("add-source").performClick()
 ui.onNodeWithTag("source-category-outpost").assertExists()
 ui.onNodeWithTag("source-category-tower").assertExists()
 ui.onNodeWithTag("connection-close").performClick()
 ui.onNodeWithText("放映站").performClick()
        ui.onNodeWithTag("home-archives").performClick()
        ui.onNodeWithTag("world-person:axi").assertIsDisplayed()
        ui.onNodeWithTag("world-map").assertDoesNotExist()
        capture("character-archives")
    }

    private fun showWorld(locale:Locale) {
        val config=Configuration(ui.activity.resources.configuration).apply { setLocales(LocaleList(locale)) }
        ui.runOnUiThread { ui.activity.setContent {
            CompositionLocalProvider(LocalConfiguration provides config) { PlayerTheme { StationWorldPage(embedded=true){} } }
        } }
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            java.io.File(ui.activity.getExternalFilesDir(null),"world32-$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        } finally { image.recycle() }
    }
}
