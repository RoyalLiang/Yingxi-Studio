package io.localplayer.app

import android.content.Context
import io.localplayer.core.RecordVerdict
import io.localplayer.core.RouteProgress
import io.localplayer.core.RouteReading
import io.localplayer.core.RouteRound
import org.json.JSONArray
import org.json.JSONObject

internal data class RouteRecord(
    val id: String, val claim: WorldText, val verdict: RecordVerdict, val reason: WorldText,
    val term: WorldTerm, val sourceField: String,
) {
    val original: WorldText get() = if (sourceField == "proposal") requireNotNull(term.proposal) else term.summary
}
internal data class ReadingRound(val rules: RouteRound, val title: WorldText, val place: String, val records: List<RouteRecord>)

internal object RouteRecords {
    fun load(context: Context): List<ReadingRound> {
        val world = StationWorld.load(context)
        val root = JSONObject(context.resources.openRawResource(R.raw.world_atlas).bufferedReader().use { it.readText() })
        val rounds = root.getJSONArray("readingRounds")
        fun JSONObject.words(key: String) = getJSONObject(key).let { WorldText(it.getString("zh"), it.getString("en")) }
        return (0 until rounds.length()).map { i ->
            val item = rounds.getJSONObject(i)
            val papers = item.getJSONArray("records")
            val records = (0 until papers.length()).map { j ->
                val paper = papers.getJSONObject(j)
                RouteRecord(paper.getString("id"), paper.words("claim"),
                    RecordVerdict.valueOf(paper.getString("verdict")), paper.words("reason"),
                    world.lexicon.single { it.id == paper.getString("term") }, paper.getString("field"))
            }
            ReadingRound(RouteRound(item.getString("id"), item.getInt("revision"), records.associate { it.id to it.verdict }),
                item.words("title"), item.getString("place"), records)
        }
    }
}

internal class RouteReadingStore(context: Context) {
    private val prefs = MigratingPreferences.open(context,"station-reading-v1")
    fun read(): RouteProgress? = runCatching {
        val item = JSONObject(prefs.getString("progress", null) ?: return null)
        val revision = item.get("revision")
        require(revision is Int && revision > 0)
        val choices = item.getJSONObject("choices")
        val revealed = item.getJSONArray("revealed")
        RouteProgress(item.getString("round"), revision,
            choices.keys().asSequence().associateWith { RecordVerdict.valueOf(choices.getString(it)) },
            (0 until revealed.length()).map { revealed.getString(it) }.toSet())
    }.getOrNull()
    fun save(progress: RouteProgress) {
        prefs.edit().putString("progress", JSONObject().put("round", progress.round).put("revision", progress.revision)
            .put("choices", JSONObject(progress.choices.mapValues { it.value.name }))
            .put("revealed", JSONArray(progress.revealed.toList())).toString()).apply()
    }
    fun restore(rounds: List<ReadingRound>, place: String? = null): Pair<RouteProgress, Boolean> {
        val saved = read()
        val round = rounds.firstOrNull { it.place == place } ?: rounds.firstOrNull { it.rules.id == saved?.round } ?: rounds.first()
        val restored = RouteReading.restore(round.rules, saved)
        return restored to (place == null && prefs.contains("progress") && restored != saved)
    }
}
