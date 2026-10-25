package io.localplayer.app

import android.content.res.Configuration
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
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WorldIntegrationRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun diarySelectsTheSameRecordInBothLanguagesAndKeepsChapterIds() {
        val world = StationWorld.load(ui.activity)
        val preferences = StationPreferences(ui.activity, "world-integration-test")
        val selected = preferences.diary()
        assertEquals(selected.text.zh, preferences.dailyLine("zh"))
        assertEquals(selected.text.en, preferences.dailyLine("en"))
        assertTrue(world.dailyLines.all { it.text.zh.isNotBlank() && it.text.en.isNotBlank() })
        assertEquals(listOf("chapter_01", "chapter_02"), world.chapters.map { it.id })
        assertEquals(4, world.chapters.sumOf { it.letters.size })
    }

    @Test fun characterSwitchResetsQuoteProgressAndShowsTheSelectedPersonality() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme { StationWorldPage {} } } }
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-quote"))
        val count=StationWorld.load(ui.activity).people.first{it.id=="axi"}.quotes.size
        ui.onNodeWithTag("world-quote-progress", useUnmergedTree = true).assertTextContains("1 / $count", substring = true)
        ui.onNodeWithTag("world-quote").performClick()
        ui.onNodeWithTag("world-quote-progress", useUnmergedTree = true).assertTextContains("2 / $count", substring = true)
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-person:mitu"))
        ui.onNodeWithTag("world-person:mitu").performClick()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-person-personality"))
        ui.onNodeWithTag("world-person-personality").assertExists()
        capture("personality")
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-quote"))
        ui.onNodeWithTag("world-quote-progress", useUnmergedTree = true).assertTextContains("1 / 6", substring = true)
    }

    @Test fun overviewRemainsReadableWithoutGlossaryButtonsInBothLanguagesAtLargeText() {
        for(language in listOf("zh","en")) {
            ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
                val density=LocalDensity.current
                val config=Configuration(ui.activity.resources.configuration).apply{setLocale(Locale.forLanguageTag(language))}
                CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f),LocalConfiguration provides config) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {StationWorldPage(embedded=true){}}
                }
            } } }
            ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-canon-boundary"))
            ui.onNodeWithTag("world-canon-boundary").assertIsDisplayed()
            ui.onNodeWithTag("world-app-glossary-toggle").assertDoesNotExist()
            ui.onNodeWithTag("world-dictionary-toggle").assertDoesNotExist()
        }
    }

    @Test fun emptyHistoryNoteKeepsBothActionsReachableAtLargeText() {
        ui.runOnUiThread {
            val player = ViewModelProvider(ui.activity)[LibraryViewModel::class.java]
            val files = ViewModelProvider(ui.activity)[LocalFilesViewModel::class.java]
            player.clearHistory()
            ui.activity.setContent {
                PlayerTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            AppShell(player, files, {}, {}, {})
                        }
                    }
                }
            }
        }
        ui.onAllNodesWithText("拾荒记录").onLast().performClick()
        ui.onNodeWithTag("history-keeper-note").assertExists()
        capture("history")
        ui.onNodeWithText("打开本机视频").performScrollTo().performClick()
        ui.onNodeWithText("我的片架").assertExists()
        capture("local")
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("查看已登记信源").performScrollTo().performClick()
        ui.onNodeWithTag("history-keeper-note").assertDoesNotExist()
    }
    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(ui.activity.cacheDir, "world-integration-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
