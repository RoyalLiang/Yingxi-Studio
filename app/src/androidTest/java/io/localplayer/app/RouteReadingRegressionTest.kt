package io.localplayer.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class RouteReadingRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun cleanOnlyThisPractice() {
        MigratingPreferences.open(ui.activity,"station-reading-v1").edit().clear().commit()
    }
    private fun fixture(language: String = "zh", scale: Float = 1f, narrow: Boolean = false) {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            val density = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language)) }
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(if (narrow) ui.activity.resources.displayMetrics.widthPixels / 320f else density.density, scale)) {
                RouteReadingPage {}
            }
        } } }
    }
    private fun tap(tag: String) {
        val node = ui.onNodeWithTag(tag)
        if (tag.endsWith("-back")) node.performClick() else node.performScrollTo().performClick()
    }
    private fun saved() = RouteReadingStore(ui.activity).read()!!
    private fun choose(record: String, verdict: RecordVerdict) = tap("reading-choice:$record:${verdict.name}")
    private fun capture(name: String) {
        ui.waitForIdle()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        try { File(ui.activity.getExternalFilesDir(null), "ui50-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    @Test fun incorrectChoiceCanBeRevisedAndCompletionTracksCurrentChoices() {
        fixture(); ui.onNodeWithTag("route-reading").assertIsDisplayed(); capture("start-zh")
        choose("record_01a", RecordVerdict.Confirmed)
        ui.onNodeWithTag("reading-feedback:record_01a").assertTextContains("仍可改选", substring = true)
        choose("record_01a", RecordVerdict.Pending)
        ui.onNodeWithTag("reading-choice:record_01a:Pending").assertIsSelected()
        choose("record_01b", RecordVerdict.Confirmed)
        ui.onNodeWithTag("reading-complete").performScrollTo().assertIsDisplayed(); capture("complete-zh")
        choose("record_01a", RecordVerdict.Confirmed)
        ui.onNodeWithTag("reading-complete").assertDoesNotExist()
        assertEquals(RecordVerdict.Confirmed, saved().choices["record_01b"])
    }

    @Test fun directEvidenceAndOriginalReadingDoNotPretendToComplete() {
        fixture(); tap("reading-evidence")
        assertTrue(saved().choices.isEmpty()); assertEquals(2, saved().revealed.size)
        ui.onNodeWithTag("reading-complete").assertDoesNotExist()
        tap("reading-source:record_01a")
        ui.onNodeWithTag("reading-source-status").assertTextContains("尚未采用", substring = true)
        ui.onNodeWithTag("reading-original-text").assertTextEquals(RouteRecords.load(ui.activity).first().records.first().original.zh)
        ui.onNodeWithTag("reading-original-back").performClick()
        assertTrue(saved().choices.isEmpty())
        ui.onNodeWithTag("reading-feedback:record_01a").assertTextEquals("先看依据")
    }

    @Test fun shellRoundTripRecreationAndOlderGamesRetainIndependentProgress() {
        PracticeStore(ui.activity).save(CaseProgress("case_sorting_02", 1, setOf("pair_1")))
        TuningStore(ui.activity).save(SignalProgress("signal_tuning_01", 1, 37, true))
        ui.onNodeWithTag("nav:Explore").performClick(); tap("reading-entry")
        choose("record_01a", RecordVerdict.Pending); tap("reading-source:record_01a")
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        ui.onNodeWithTag("reading-original").assertExists()
        ui.onNodeWithTag("reading-original-back").performClick()
        assertEquals(RecordVerdict.Pending, saved().choices["record_01a"])
        tap("reading-back"); tap("tuning-entry")
        ui.onNodeWithTag("tuning-position").assertTextContains("37", substring = true)
        tap("tuning-back"); tap("practice-entry")
        assertEquals(setOf("pair_1"), PracticeStore(ui.activity).read()!!.matched)
        tap("practice-back"); tap("reading-entry")
        ui.onNodeWithTag("reading-choice:record_01a:Pending").assertIsSelected()
    }

    @Test fun mapLaunchStartsSelectedPlaceAndRecreationKeepsNextPair() {
        ui.onNodeWithTag("nav:Explore").performClick()
        tap("world-node:beacon"); tap("reading-place")
        ui.onNodeWithTag("reading-round").assertTextContains("5 / 6", substring = true)
        tap("reading-next"); assertEquals("route_reading_06", saved().round)
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        ui.onNodeWithTag("reading-round").assertTextContains("6 / 6", substring = true)
        tap("reading-back")
        ui.onNodeWithTag("world-node:beacon").assertIsSelected()
    }

    @Test fun resetRequiresConfirmationAndInvalidSavedDataExplainsFreshStart() {
        val store = RouteReadingStore(ui.activity)
        store.save(RouteProgress("route_reading_01", 99))
        fixture(); ui.onNodeWithTag("reading-refreshed").assertIsDisplayed()
        choose("record_01a", RecordVerdict.Pending)
        tap("reading-reset"); ui.onNodeWithTag("reading-reset-cancel").performClick()
        assertEquals(1, saved().choices.size)
        tap("reading-reset"); ui.onNodeWithTag("reading-reset-confirm").performClick()
        assertTrue(saved().choices.isEmpty()); assertTrue(saved().revealed.isEmpty())
        MigratingPreferences.open(ui.activity,"station-reading-v1").edit().putString("progress", "broken").commit()
        assertTrue(store.restore(RouteRecords.load(ui.activity)).second)
        for (bad in listOf("\"1\"", "1.5", "true", "-1")) {
            MigratingPreferences.open(ui.activity,"station-reading-v1").edit().putString("progress",
                """{"round":"route_reading_01","revision":$bad,"choices":{},"revealed":[]}""").commit()
            assertNull(store.read()); assertTrue(store.restore(RouteRecords.load(ui.activity)).second)
        }

    }

    @Test fun allSixBilingualPairsAndOriginalsAreReadableAtNarrowLargeType() {
        val rounds = RouteRecords.load(ui.activity)
        val store = RouteReadingStore(ui.activity)
        for (language in listOf("zh", "en")) for (round in rounds) {
            store.save(RouteReading.restore(round.rules, null))
            ui.runOnUiThread { ui.activity.setContent {} }; ui.waitForIdle()
            fixture(language, 1.8f, true)
            ui.onNodeWithTag("route-reading").assertIsDisplayed()
            if (round == rounds.first()) capture("large-$language")
            tap("reading-evidence"); assertTrue(saved().choices.isEmpty())
            for (record in round.records) {
                ui.onNodeWithTag("reading-reason:${record.id}").performScrollTo().assertTextEquals(record.reason.inLanguage(language))
                tap("reading-source:${record.id}")
                ui.onNodeWithTag("reading-original-text").performScrollTo().assertTextEquals(record.original.inLanguage(language))
                if (round == rounds.first() && record == round.records.first()) capture("large-source-$language")
                ui.onNodeWithTag("reading-original-back").performClick()
                choose(record.id, record.verdict)
                val confirmed = ui.onNodeWithTag("reading-choice:${record.id}:Confirmed").fetchSemanticsNode().boundsInRoot
                val pending = ui.onNodeWithTag("reading-choice:${record.id}:Pending").fetchSemanticsNode().boundsInRoot
                assertTrue("Large type choices must stack", pending.top >= confirmed.bottom)
                if (round == rounds.first() && record == round.records.first()) capture("large-choice-$language")
            }
            ui.onNodeWithTag("reading-complete").performScrollTo().assertIsDisplayed()
            tap("reading-next")
            assertEquals(rounds[(rounds.indexOf(round) + 1) % rounds.size].rules.id, saved().round)
        }
    }

    @Test fun gameDoesNotMutateWorldEvidenceOrNeedAudioResources() {
        val before = StationWorld.load(ui.activity)
        fixture(); tap("reading-evidence")
        choose("record_01a", RecordVerdict.Pending); choose("record_01b", RecordVerdict.Confirmed)
        tap("reading-next")
        val after = StationWorld.load(ui.activity)
        assertEquals(before.edges, after.edges); assertEquals(before.lexicon, after.lexicon)
        assertEquals(before.chapters, after.chapters)
        assertEquals(1, before.edges.count { it.known })
    }
}
