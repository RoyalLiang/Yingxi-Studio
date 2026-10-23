package io.localplayer.core

/** Only fictional paper IDs live here. Matching never touches a media library. */
data class CaseRound(val id: String, val revision: Int, val pairs: Set<String>) {
    init {
        require(id.isNotBlank() && revision > 0 && pairs.size == 3 && pairs.none(String::isBlank))
    }
}

data class CaseProgress(val round: String, val revision: Int, val matched: Set<String> = emptySet())

enum class CaseMatch { Correct, Complete, Wrong, Unavailable }
data class CaseMove(val progress: CaseProgress, val result: CaseMatch)

object CaseSorting {
    fun restore(round: CaseRound, saved: CaseProgress?): CaseProgress =
        if (saved?.round == round.id && saved.revision == round.revision && round.pairs.containsAll(saved.matched)) saved
        else CaseProgress(round.id, round.revision)

    fun place(round: CaseRound, saved: CaseProgress, label: String, target: String): CaseMove {
        val progress = restore(round, saved)
        if (label !in round.pairs || target !in round.pairs || label in progress.matched || target in progress.matched)
            return CaseMove(progress, CaseMatch.Unavailable)
        if (label != target) return CaseMove(progress, CaseMatch.Wrong)
        val next = progress.copy(matched = progress.matched + label)
        return CaseMove(next, if (next.matched == round.pairs) CaseMatch.Complete else CaseMatch.Correct)
    }
}
