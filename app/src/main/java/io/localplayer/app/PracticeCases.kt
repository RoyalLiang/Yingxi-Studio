package io.localplayer.app

import android.content.Context
import io.localplayer.core.CaseProgress
import io.localplayer.core.CaseRound
import io.localplayer.core.CaseSorting
import org.json.JSONObject

internal data class PracticePair(val id: String, val title: WorldText, val clue: WorldText, val label: WorldText, val hint: WorldText)
internal data class PracticeRound(val rules: CaseRound, val title: WorldText, val note: WorldText, val pairs: List<PracticePair>)

internal object PracticeCases {
    fun load(context: Context): List<PracticeRound> {
        val root = JSONObject(context.resources.openRawResource(R.raw.world_atlas).bufferedReader().use { it.readText() })
        fun JSONObject.words(key: String) = getJSONObject(key).let { WorldText(it.getString("zh"), it.getString("en")) }
        val rounds = root.getJSONArray("practiceRounds")
        return (0 until rounds.length()).map { index ->
            val item = rounds.getJSONObject(index)
            val entries = item.getJSONArray("pairs")
            val pairs = (0 until entries.length()).map { i ->
                val pair = entries.getJSONObject(i)
                PracticePair(pair.getString("id"), pair.words("title"), pair.words("clue"), pair.words("label"), pair.words("hint"))
            }
            PracticeRound(CaseRound(item.getString("id"), item.getInt("revision"), pairs.map { it.id }.toSet()), item.words("title"), item.words("note"), pairs)
        }
    }
}

/** A single atomic preference entry; no files, history, network or account identifiers. */
internal class PracticeStore(context: Context) {
    private val prefs = MigratingPreferences.open(context,"station-practice-v1")
    fun read(): CaseProgress? = runCatching {
        val value = prefs.getString("progress", null) ?: return null
        val item = JSONObject(value)
        val matched = item.getJSONArray("matched")
        CaseProgress(item.getString("round"), item.getInt("revision"), (0 until matched.length()).map { matched.getString(it) }.toSet())
    }.getOrNull()

    fun save(progress: CaseProgress) {
        val item = JSONObject().put("round", progress.round).put("revision", progress.revision)
            .put("matched", org.json.JSONArray(progress.matched.sorted()))
        prefs.edit().putString("progress", item.toString()).apply()
    }

    fun restore(rounds: List<PracticeRound>): Pair<CaseProgress, Boolean> {
        val saved = read()
        val round = rounds.firstOrNull { it.rules.id == saved?.round } ?: rounds.first()
        val restored = CaseSorting.restore(round.rules, saved)
        val changed = (saved != null && restored != saved) || (saved == null && prefs.contains("progress"))
        save(restored)
        return restored to changed
    }
}
