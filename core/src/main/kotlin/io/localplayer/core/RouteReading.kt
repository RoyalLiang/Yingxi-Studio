package io.localplayer.core

enum class RecordVerdict { Confirmed, Pending }

data class RouteRound(val id: String, val revision: Int, val records: Map<String, RecordVerdict>) {
    init {
        require(id.isNotBlank() && revision > 0 && records.size == 2 && records.keys.all { it.isNotBlank() })
    }
    fun complete(progress: RouteProgress) = records.all { (id, verdict) -> progress.choices[id] == verdict }
}

data class RouteProgress(
    val round: String,
    val revision: Int,
    val choices: Map<String, RecordVerdict> = emptyMap(),
    val revealed: Set<String> = emptySet(),
)

object RouteReading {
    fun restore(round: RouteRound, saved: RouteProgress?): RouteProgress = saved?.takeIf {
        it.round == round.id && it.revision == round.revision &&
            round.records.keys.containsAll(it.choices.keys) && round.records.keys.containsAll(it.revealed) &&
            it.revealed.containsAll(it.choices.keys)
    } ?: RouteProgress(round.id, round.revision)

    fun choose(round: RouteRound, saved: RouteProgress, record: String, verdict: RecordVerdict): RouteProgress {
        require(record in round.records)
        val current = restore(round, saved)
        return current.copy(choices = current.choices + (record to verdict), revealed = current.revealed + record)
    }

    fun read(round: RouteRound, saved: RouteProgress) = restore(round, saved).copy(revealed = round.records.keys.toSet())
}
