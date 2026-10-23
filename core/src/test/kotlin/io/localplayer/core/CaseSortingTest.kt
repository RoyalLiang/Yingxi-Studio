package io.localplayer.core

import kotlin.test.*

class CaseSortingTest {
    private val round = CaseRound("sorting_01", 1, setOf("a", "b", "c"))

    @Test fun wrongAndRepeatedMovesNeverEraseOrOverwriteCorrectWork() {
        val first = CaseSorting.place(round, CaseSorting.restore(round, null), "a", "a")
        assertEquals(CaseMatch.Correct, first.result)
        for ((label, target, result) in listOf(Triple("b", "c", CaseMatch.Wrong), Triple("a", "b", CaseMatch.Unavailable), Triple("b", "a", CaseMatch.Unavailable), Triple("unknown", "c", CaseMatch.Unavailable))) {
            val move = CaseSorting.place(round, first.progress, label, target)
            assertEquals(result, move.result)
            assertEquals(setOf("a"), move.progress.matched)
        }
    }

    @Test fun completeOnlyWhenAllThreeDistinctPairsArePlaced() {
        var progress = CaseSorting.restore(round, null)
        for (id in listOf("c", "a", "b")) {
            val move = CaseSorting.place(round, progress, id, id)
            progress = move.progress
            assertEquals(if (id == "b") CaseMatch.Complete else CaseMatch.Correct, move.result)
        }
        assertEquals(round.pairs, progress.matched)
        assertEquals(CaseMatch.Unavailable, CaseSorting.place(round, progress, "b", "b").result)
    }

    @Test fun staleRevisionUnknownIdsAndDifferentRoundResetWithoutMigratingGuesses() {
        for (saved in listOf(CaseProgress(round.id, 2, setOf("a")), CaseProgress("old", 1, setOf("a")), CaseProgress(round.id, 1, setOf("missing")))) {
            assertEquals(CaseProgress(round.id, 1), CaseSorting.restore(round, saved))
        }
        val valid = CaseProgress(round.id, 1, setOf("a", "b"))
        assertEquals(valid, CaseSorting.restore(round, valid))
    }
}
