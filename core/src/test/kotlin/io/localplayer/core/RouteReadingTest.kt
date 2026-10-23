package io.localplayer.core

import kotlin.test.*

class RouteReadingTest {
    private val round = RouteRound("route_01", 1, mapOf("a" to RecordVerdict.Confirmed, "b" to RecordVerdict.Pending))

    @Test fun readingEvidenceDoesNotInventAnswersOrCompletion() {
        val read = RouteReading.read(round, RouteReading.restore(round, null))
        assertEquals(setOf("a", "b"), read.revealed)
        assertTrue(read.choices.isEmpty())
        assertFalse(round.complete(read))
    }

    @Test fun correctionsAreAllowedAndOtherChoicesArePreserved() {
        var p = RouteReading.choose(round, RouteReading.restore(round, null), "a", RecordVerdict.Pending)
        p = RouteReading.choose(round, p, "b", RecordVerdict.Pending)
        assertFalse(round.complete(p))
        p = RouteReading.choose(round, p, "a", RecordVerdict.Confirmed)
        assertTrue(round.complete(p))
        assertEquals(setOf("a", "b"), p.revealed)
        assertEquals(p, RouteReading.restore(round, p))
        p = RouteReading.choose(round, p, "a", RecordVerdict.Pending)
        assertFalse(round.complete(p))
        assertEquals(RecordVerdict.Pending, p.choices["b"])
    }

    @Test fun staleAndCorruptRecordsResetWithoutReclassifyingEvidence() {
        val fresh = RouteReading.restore(round, null)
        for (bad in listOf(RouteProgress("removed", 1), RouteProgress(round.id, 2),
            RouteProgress(round.id, 1, mapOf("unknown" to RecordVerdict.Pending), setOf("unknown")),
            RouteProgress(round.id, 1, emptyMap(), setOf("unknown")),
            RouteProgress(round.id, 1, mapOf("a" to RecordVerdict.Confirmed)))) {
            assertEquals(fresh, RouteReading.restore(round, bad))
        }
        assertFailsWith<IllegalArgumentException> { RouteReading.choose(round, fresh, "unknown", RecordVerdict.Pending) }
    }

    @Test fun validPartialOrReadOnlyProgressRestoresAndRoundContractRejectsInvalidIdentity() {
        val p = RouteReading.choose(round, RouteReading.read(round, RouteReading.restore(round, null)), "b", RecordVerdict.Pending)
        assertEquals(p, RouteReading.restore(round, p))
        assertFailsWith<IllegalArgumentException> { RouteRound("", 1, round.records) }
        assertFailsWith<IllegalArgumentException> { RouteRound("x", 0, round.records) }
        assertFailsWith<IllegalArgumentException> { RouteRound("x", 1, emptyMap()) }
    }
}
