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
        val selected = world.dailyLines[(LocalDate.now().toEpochDay() % world.dailyLines.size).toInt()]
        assertEquals(selected.text.zh, preferences.dailyLine("zh"))
        assertEquals(selected.text.en, preferences.dailyLine("en"))
        assertTrue(world.dailyLines.all { it.text.zh.isNotBlank() && it.text.en.isNotBlank() })
        assertEquals(listOf("chapter_01", "chapter_02"), world.chapters.map { it.id })
        assertEquals(4, world.chapters.sumOf { it.letters.size })
    }

    @Test fun characterSwitchResetsQuoteProgressAndShowsTheSelectedPersonality() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme { StationWorldPage {} } } }
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-quote"))
        ui.onNodeWithTag("world-quote-progress", useUnmergedTree = true).assertTextContains("1 / 12", substring = true)
        ui.onNodeWithTag("world-quote").performClick()
        ui.onNodeWithTag("world-quote-progress", useUnmergedTree = true).assertTextContains("2 / 12", substring = true)
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-person:mitu"))
        ui.onNodeWithTag("world-person:mitu").performClick()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-person-personality"))
        ui.onNodeWithTag("world-person-personality").assertExists()
        capture("personality")
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-quote"))
        ui.onNodeWithTag("world-quote-progress", useUnmergedTree = true).assertTextContains("1 / 6", substring = true)
    }

    @Test fun overviewAndFunctionalGlossaryWrapInBothLanguagesAtLargeText() {
        for (language in listOf("zh", "en")) {
            ui.runOnUiThread {
                ui.activity.setContent {
                    PlayerTheme {
                        val density = LocalDensity.current
                        val config = Configuration(ui.activity.resources.configuration).apply { setLocale(Locale(language)) }
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f), LocalConfiguration provides config) {
                            androidx.compose.runtime.key(language) { Box(Modifier.width(320.dp).fillMaxHeight()) { StationWorldPage(embedded = true) {} } }
                        }
                    }
                }
            }
            ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-canon-boundary"))
            ui.onNodeWithTag("world-canon-boundary").assertIsDisplayed()
            capture("overview-$language")
            ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-app-glossary-toggle"))
            ui.onNodeWithTag("world-app-glossary-toggle").performClick()
            val names = StationWorld.load(ui.activity).appNames
            for (name in listOf(names.first(), names.last())) {
                ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-app-name:${name.id}"))
                val bounds = ui.onNodeWithTag("world-app-name:${name.id}").fetchSemanticsNode().boundsInRoot
                val screen = ui.onNodeWithTag("station-world").fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= screen.left && bounds.right <= screen.right)
                ui.onNodeWithText(name.meaning.inLanguage(language), useUnmergedTree = true).assertExists()
                capture("glossary-$language-${name.id}")
            }
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
        ui.onNodeWithTag("station-local-notice").assertExists()
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
