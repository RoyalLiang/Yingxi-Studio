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

    @Test fun dictionaryDataRetainsCanonAndProposalBoundariesWithoutEntryButtons() {
        val world=StationWorld.load(ui.activity)
        org.junit.Assert.assertTrue(world.lexicon.first{it.id=="long_tide"}.proposal!=null)
        org.junit.Assert.assertTrue(world.lexicon.first{it.id=="awakened"}.proposal!=null)
        showWorld(Locale.SIMPLIFIED_CHINESE)
        ui.onNodeWithTag("world-dictionary-toggle").assertDoesNotExist()
        ui.onNodeWithTag("world-app-glossary-toggle").assertDoesNotExist()
        showWorld(Locale.ENGLISH)
        ui.onNodeWithTag("world-dictionary-toggle").assertDoesNotExist()
        ui.onNodeWithTag("world-app-glossary-toggle").assertDoesNotExist()
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
