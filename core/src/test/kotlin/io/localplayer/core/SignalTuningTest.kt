package io.localplayer.core

import kotlin.test.*

class SignalTuningTest {
    private val round = SignalRound("signal_01", 1, 68, 14)

    @Test fun bothDirectionsAndToleranceSnapAndRemainSolved() {
        for (position in listOf(66, 67, 68, 69, 70)) {
            val solved = SignalTuning.move(round, SignalTuning.restore(round, null), position)
            assertEquals(68, solved.position); assertTrue(solved.revealed)
            assertEquals(solved, SignalTuning.move(round, solved, 0))
        }
        for (position in listOf(65, 71)) assertFalse(SignalTuning.move(round, SignalTuning.restore(round, null), position).revealed)
    }

    @Test fun directReadingDoesNotPretendToTuneOrPreventFurtherAdjustment() {
        val read = SignalTuning.read(round, SignalTuning.restore(round, null))
        assertTrue(read.revealed); assertEquals(14, read.position); assertFalse(round.tuned(read))
        val moved = SignalTuning.move(round, read, 19)
        assertTrue(moved.revealed); assertEquals(19, moved.position)
        assertEquals(0, SignalTuning.move(round, moved, Int.MIN_VALUE).position)
        assertEquals(100, SignalTuning.move(round, moved, Int.MAX_VALUE).position)
    }

    @Test fun staleOrCorruptProgressResetsButCompatibleProgressRestores() {
        val initial = SignalProgress(round.id, 1, 14)
        for (saved in listOf(SignalProgress("removed", 1, 50, true), SignalProgress(round.id, 9, 50), SignalProgress(round.id, 1, -1), SignalProgress(round.id, 1, 101)))
            assertEquals(initial, SignalTuning.restore(round, saved))
        val saved = SignalProgress(round.id, 1, 42, true)
        assertEquals(saved, SignalTuning.restore(round, saved))
        assertEquals(SignalProgress(round.id, 1, 68, true), SignalTuning.restore(round, saved.copy(position = 67, revealed = false)))
    }

    @Test fun rotaryGeometryHasStableEndpointsMidpointAndGap() {
        assertEquals(0, SignalTuning.positionForVector(-1f, 1f))
        assertEquals(50, SignalTuning.positionForVector(0f, -1f))
        assertEquals(100, SignalTuning.positionForVector(1f, 1f))
        assertEquals(0, SignalTuning.positionForVector(-.1f, 1f))
        assertEquals(100, SignalTuning.positionForVector(.1f, 1f))
        assertNull(SignalTuning.positionForVector(0f, 0f)); assertNull(SignalTuning.positionForVector(Float.NaN, 1f))
    }
}
