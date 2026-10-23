package io.localplayer.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.localplayer.core.CaseProgress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class CaseSortingRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun cleanPracticeOnly() { ui.activity.getSharedPreferences("station-practice-v1", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun fixture(language: String = "zh", scale: Float = 1f, narrow: Boolean = false) {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            val d = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language)) }
            CompositionLocalProvider(LocalDensity provides Density(if (narrow) ui.activity.resources.displayMetrics.widthPixels / 320f else d.density, scale), LocalConfiguration provides config) {
                CaseSortingPage {}
            }
        } } }
    }
    private fun tap(tag: String) { ui.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun match(id: String) { tap("practice-label:$id"); tap("practice-case:$id") }
    private fun saved() = PracticeStore(ui.activity).read()!!

    @Test fun wrongHintAndCompletionKeepCorrectMatchesThenAdvance() {
        fixture(); match("pair_1")
        tap("practice-label:pair_2"); tap("practice-case:pair_3")
        assertEquals(setOf("pair_1"), saved().matched)
        ui.onNodeWithTag("practice-feedback").assertTextContains("这张还不合适", substring = true)
        tap("practice-label:pair_2"); tap("practice-hint")
        ui.onNodeWithTag("practice-feedback").assertTextContains("旧设备", substring = true)
        tap("practice-cancel")
        match("pair_2"); match("pair_3")
        ui.onNodeWithTag("practice-complete").performScrollTo().assertIsDisplayed()
        assertEquals(3, saved().matched.size)
        tap("practice-next")
        assertEquals("case_sorting_02", saved().round)
        assertTrue(saved().matched.isEmpty())
    }

    @Test fun longPressDragUsesTheSameMatchingRulesAsTap() {
        fixture()
        val label = ui.onNodeWithTag("practice-label:pair_2").performScrollTo()
        val target = ui.onNodeWithTag("practice-case:pair_2").fetchSemanticsNode().boundsInRoot.center
        val paper = label.fetchSemanticsNode().boundsInRoot
        label.performTouchInput {
            down(center); advanceEventTime(700)
            moveTo(target - paper.topLeft, delayMillis = 900); up()
        }
        ui.waitForIdle()
        assertEquals(setOf("pair_2"), saved().matched)
        match("pair_1"); match("pair_3")
        assertEquals(3, saved().matched.size)
    }

    @Test fun restartRequiresConfirmationAndOnlyClearsCurrentPractice() {
        fixture(); match("pair_1")
        tap("practice-reset"); ui.onNodeWithTag("practice-reset-cancel").performClick()
        assertEquals(setOf("pair_1"), saved().matched)
        tap("practice-reset"); ui.onNodeWithTag("practice-reset-confirm").performClick()
        assertTrue(saved().matched.isEmpty())
        ui.onNodeWithTag("practice-label:pair_1").assertExists()
    }

    @Test fun largeTypeDragScrollsToAnInitiallyOffscreenCaseUsingOnlyVisiblePoints() {
        for (language in listOf("zh", "en")) {
            ui.activity.getSharedPreferences("station-practice-v1", Context.MODE_PRIVATE).edit().clear().commit()
            ui.runOnUiThread { ui.activity.setContent {} }; ui.waitForIdle()
            fixture(language, 1.8f, true)
            val label = ui.onNodeWithTag("practice-label:pair_1").performScrollTo().fetchSemanticsNode().boundsInRoot
            val viewport = ui.onNodeWithTag("practice-scroll").fetchSemanticsNode().boundsInRoot
            assertTrue("case starts offscreen", ui.onNodeWithTag("practice-case:pair_1").fetchSemanticsNode().boundsInRoot.bottom < viewport.top)
            val root = ui.onNodeWithTag("case-sorting")
            val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
            root.performTouchInput {
                down(label.center - rootOrigin); advanceEventTime(700)
                moveTo(androidx.compose.ui.geometry.Offset(viewport.center.x, viewport.top + 15) - rootOrigin, delayMillis = 600)
            }
            ui.mainClock.advanceTimeBy(4000)
            val target = ui.onNodeWithTag("practice-case:pair_1").fetchSemanticsNode().boundsInRoot
            assertTrue("case scrolled inside viewport", target.center.y > viewport.top && target.center.y < viewport.bottom)
            root.performTouchInput { moveTo(target.center - rootOrigin, delayMillis = 300); up() }
            ui.waitForIdle()
            assertEquals(setOf("pair_1"), saved().matched)
        }
    }

    @Test fun persistedProgressSurvivesLeavingAndActivityRecreation() {
        ui.onNodeWithTag("nav:Explore").performClick()
        tap("practice-entry"); match("pair_1")
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        ui.onNodeWithTag("case-sorting").assertExists()
        ui.onNodeWithTag("practice-label:pair_1").assertDoesNotExist()
        assertEquals(setOf("pair_1"), saved().matched)
        ui.onNodeWithTag("practice-back").performClick()
        ui.onNodeWithTag("nav:Explore").assertIsSelected()
        ui.onNodeWithTag("practice-entry").assertIsDisplayed()
        tap("practice-entry")
        ui.onNodeWithTag("practice-label:pair_1").assertDoesNotExist()
    }

    @Test fun staleRevisionAndUnknownRoundExplainResetAndValidWorkIsKept() {
        val store = PracticeStore(ui.activity)
        val rounds = PracticeCases.load(ui.activity)
        store.save(CaseProgress("case_sorting_02", 99, setOf("pair_1")))
        fixture(); ui.onNodeWithTag("practice-feedback").assertTextContains("已更新", substring = true)
        assertEquals("case_sorting_02", saved().round)
        assertEquals(1, saved().revision); assertTrue(saved().matched.isEmpty())
        match("pair_1"); match("pair_2"); match("pair_3"); tap("practice-next")
        ui.onNodeWithTag("practice-feedback").assertDoesNotExist()
        store.save(CaseProgress("removed", 1, setOf("pair_1")))
        assertTrue(store.restore(rounds).second)
        val valid = CaseProgress("case_sorting_05", 1, setOf("pair_1", "pair_3"))
        store.save(valid); assertEquals(valid, store.restore(rounds).first)
    }

    @Test fun allSixBilingualTraysCompleteAtNarrowLargeTypeAndWrap() {
        val store = PracticeStore(ui.activity)
        for (language in listOf("zh", "en")) {
            for (round in PracticeCases.load(ui.activity)) {
                store.save(CaseProgress(round.rules.id, 1, setOf("pair_1", "pair_2")))
                // Replace the composition so this is a cold restore of each saved tray.
                ui.runOnUiThread { ui.activity.setContent {} }; ui.waitForIdle()
                fixture(language, 1.8f, true)
                match("pair_3")
                ui.onNodeWithTag("practice-complete").performScrollTo().assertIsDisplayed()
                if (round.rules.id == "case_sorting_01") capture("complete-$language-1.8")
                tap("practice-next")
                assertEquals(if (round.rules.id == "case_sorting_06") "case_sorting_01" else PracticeCases.load(ui.activity)[PracticeCases.load(ui.activity).indexOf(round) + 1].rules.id, saved().round)
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(ui.activity.getExternalFilesDir(null), "ui48-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
