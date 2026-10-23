package io.localplayer.app

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import io.localplayer.core.CaseProgress
import io.localplayer.core.SignalProgress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class SignalTuningRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun cleanTuningOnly() { ui.activity.getSharedPreferences("station-tuning-v1", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun fixture(language: String = "zh", scale: Float = 1f, narrow: Boolean = false, open: (String) -> Unit = {}) {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            val d = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language)) }
            CompositionLocalProvider(LocalDensity provides Density(if (narrow) ui.activity.resources.displayMetrics.widthPixels / 320f else d.density, scale), LocalConfiguration provides config) {
                SignalTuningPage(open) {}
            }
        } } }
    }
    private fun tap(tag: String) { ui.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun saved() = TuningStore(ui.activity).read()!!
    private fun setMark(mark: Int) { ui.onNodeWithTag("tuning-dial").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(mark.toFloat())) } }
    private fun dragTo(mark: Int) {
        val node = ui.onNodeWithTag("tuning-dial").performScrollTo()
        val size = node.fetchSemanticsNode().boundsInRoot.size
        fun point(position: Int): Offset {
            val angle = Math.toRadians(135 + position * 2.7)
            return Offset(size.width / 2, size.height / 2) + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (size.width * .39f)
        }
        node.performTouchInput { down(point(saved().position)); moveTo(point(mark), delayMillis = 600); up() }
    }

    @Test fun stepButtonsHintsAndDirectReadingDoNotInventCompletion() {
        fixture(); ui.onNodeWithTag("signal-tuning").assertIsDisplayed(); capture("start-zh"); tap("tuning-hint")
        ui.onNodeWithTag("tuning-message").assertTextContains("68", substring = true)
        tap("tuning-read"); assertTrue(saved().revealed); assertEquals(14, saved().position)
        ui.onNodeWithTag("tuning-quality").assertTextEquals("杂讯未散")
        ui.onNodeWithTag("tuning-note").assertTextContains("纸签裁齐", substring = true)
        repeat(11) { tap("tuning-higher") }
        assertEquals(68, saved().position)
        ui.onNodeWithTag("tuning-quality").assertTextEquals("信号对上了")
        ui.onNodeWithTag("tuning-message").assertDoesNotExist()
        ui.onNodeWithTag("tuning-higher").assertIsNotEnabled()
        ui.onNodeWithTag("tuning-note").performScrollTo(); capture("aligned-zh")
        tap("tuning-next"); assertEquals("signal_tuning_02", saved().round); assertEquals(84, saved().position)
    }

    @Test fun rotaryGestureAndAccessibleProgressBothSolveIncludingAfterSkippingATray() {
        fixture(); dragTo(68)
        assertEquals(68, saved().position); assertTrue(saved().revealed)
        tap("tuning-next"); tap("tuning-read"); tap("tuning-next")
        assertEquals("signal_tuning_03", saved().round)
        dragTo(76); assertEquals(76, saved().position)
        tap("tuning-next"); setMark(24)
        assertTrue(saved().revealed); assertEquals(24, saved().position)
    }

    @Test fun archiveRoundTripRecreationAndBothGamesPreserveSeparateProgress() {
        val cases = PracticeStore(ui.activity)
        cases.save(CaseProgress("case_sorting_02", 1, setOf("pair_1")))
        ui.onNodeWithTag("nav:Explore").performClick(); tap("tuning-entry")
        setMark(37); tap("tuning-read")
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        ui.onNodeWithTag("signal-tuning").assertExists(); assertEquals(37, saved().position); assertTrue(saved().revealed)
        tap("tuning-person")
        ui.onNodeWithTag("world-person:chideng").assertIsSelected()
        ui.onNodeWithTag("archives-back").performClick()
        assertEquals(37, saved().position); ui.onNodeWithTag("tuning-note").assertExists()
        ui.onNodeWithTag("tuning-sound").assertIsOff()
        ui.onNodeWithTag("tuning-back").performClick()
        ui.onNodeWithTag("nav:Explore").assertIsSelected()
        tap("practice-entry"); ui.onNodeWithTag("case-sorting").assertExists()
        assertEquals(setOf("pair_1"), cases.read()!!.matched)
        ui.onNodeWithTag("practice-back").performClick(); tap("tuning-entry")
        assertEquals(37, saved().position)
    }

    @Test fun resetConfirmationAndIncompatibleContentHaveHonestBoundaries() {
        val store = TuningStore(ui.activity)
        store.save(SignalProgress("signal_tuning_01", 99, 42, true))
        fixture(); ui.onNodeWithTag("tuning-message").assertTextContains("重新整理", substring = true)
        assertEquals(14, saved().position); setMark(37); tap("tuning-read")
        tap("tuning-reset"); ui.onNodeWithTag("tuning-reset-cancel").performClick()
        assertEquals(37, saved().position); assertTrue(saved().revealed)
        tap("tuning-reset"); ui.onNodeWithTag("tuning-reset-confirm").performClick()
        assertEquals(14, saved().position); assertFalse(saved().revealed)
        tap("tuning-read"); tap("tuning-next")
        ui.onNodeWithTag("tuning-message").assertDoesNotExist()
        store.save(SignalProgress("removed", 1, 50)); assertTrue(store.restore(TuningSignals.load(ui.activity)).second)
    }

    @Test fun allSixNotesRemainReadableWithoutTuningAtBilingualNarrowLargeType() {
        val store = TuningStore(ui.activity)
        val signals = TuningSignals.load(ui.activity)
        var opened: String? = null
        for (language in listOf("zh", "en")) for (signal in signals) {
            store.save(SignalProgress(signal.rules.id, 1, signal.rules.start))
            ui.runOnUiThread { ui.activity.setContent {} }; ui.waitForIdle()
            fixture(language, 1.8f, true) { opened = it }
            ui.onNodeWithTag("tuning-dial").performScrollTo().assertIsDisplayed()
            tap("tuning-read")
            ui.onNodeWithTag("tuning-note").performScrollTo().assertTextEquals("“${signal.line.text.inLanguage(language)}”")
            assertEquals(signal.rules.start, saved().position)
            tap("tuning-person"); assertEquals(signal.line.author, opened)
            if (signal == signals.first()) capture("large-$language")
            tap("tuning-next")
            assertEquals(signals[(signals.indexOf(signal) + 1) % signals.size].rules.id, saved().round)
        }
    }

    @Test fun optionalNoiseDefaultsOffAndStopsOnPauseWithoutAutoResuming() {
        fixture(); ui.onNodeWithTag("tuning-sound").assertIsOff()
        tap("tuning-sound"); ui.onNodeWithTag("tuning-sound").assertIsOn()
        ui.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); ui.waitForIdle()
        ui.onNodeWithTag("tuning-sound").assertIsOff()
        val manager = ui.activity.getSystemService(AudioManager::class.java)
        ui.waitUntil(5000) { !manager.isMusicActive }
    }

    @Test fun actualAudioFocusLossStopsStaticAndDoesNotResumeOnGain() {
        var stopped = false
        lateinit var noise: TuningNoise
        val manager = ui.activity.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener {}.build()
        try {
            ui.runOnIdle { noise = TuningNoise(ui.activity) { stopped = true }; assertEquals(NoiseStart.Started, noise.start(50)); assertTrue(noise.isPlaying) }
            ui.runOnIdle { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other)) }
            ui.waitUntil(5000) { stopped }
            ui.runOnIdle { assertFalse(noise.isPlaying); manager.abandonAudioFocusRequest(other) }
            ui.waitForIdle(); assertFalse(noise.isPlaying)
        } finally { ui.runOnIdle { noise.close(); manager.abandonAudioFocusRequest(other) } }
    }

    @Test fun existingMediaAudioIsLeftPlayingWhenStaticIsRequested() {
        val audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(22050).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(44100).build()
        val samples = ShortArray(22050)
        val noise = TuningNoise(ui.activity) {}
        try {
            assertEquals(samples.size, audio.write(samples, 0, samples.size)); audio.setLoopPoints(0, samples.size, -1); audio.setVolume(0f); audio.play()
            val manager = ui.activity.getSystemService(AudioManager::class.java)
            ui.waitUntil(5000) { manager.isMusicActive }
            ui.runOnIdle { assertEquals(NoiseStart.OtherAudio, noise.start(50)); assertFalse(noise.isPlaying) }
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, audio.playState)
        } finally { noise.close(); audio.stop(); audio.release() }
    }

    private fun capture(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(ui.activity.getExternalFilesDir(null), "ui49-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
