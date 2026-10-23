package io.localplayer.app

import android.content.Context
import io.localplayer.core.SignalProgress
import io.localplayer.core.SignalRound
import io.localplayer.core.SignalTuning
import org.json.JSONObject

internal data class TuningSignal(val rules: SignalRound, val title: WorldText, val line: WorldDiaryLine)

internal object TuningSignals {
    fun load(context: Context): List<TuningSignal> {
        val world = StationWorld.load(context)
        val root = JSONObject(context.resources.openRawResource(R.raw.world_atlas).bufferedReader().use { it.readText() })
        val signals = root.getJSONArray("tuningSignals")
        return (0 until signals.length()).map { i ->
            val item = signals.getJSONObject(i)
            val title = item.getJSONObject("title")
            TuningSignal(SignalRound(item.getString("id"), item.getInt("revision"), item.getInt("target"), item.getInt("start")),
                WorldText(title.getString("zh"), title.getString("en")), world.dailyLines.single { it.id == item.getString("line") })
        }
    }
}

internal class TuningStore(context: Context) {
    private val prefs = context.getSharedPreferences("station-tuning-v1", Context.MODE_PRIVATE)
    fun read(): SignalProgress? = runCatching {
        val item = JSONObject(prefs.getString("progress", null) ?: return null)
        SignalProgress(item.getString("round"), item.getInt("revision"), item.getInt("position"), item.getBoolean("revealed"))
    }.getOrNull()

    fun save(progress: SignalProgress) {
        prefs.edit().putString("progress", JSONObject().put("round", progress.round).put("revision", progress.revision)
            .put("position", progress.position).put("revealed", progress.revealed).toString()).apply()
    }

    fun restore(signals: List<TuningSignal>): Pair<SignalProgress, Boolean> {
        val saved = read()
        val signal = signals.firstOrNull { it.rules.id == saved?.round } ?: signals.first()
        val restored = SignalTuning.restore(signal.rules, saved)
        val changed = prefs.contains("progress") && restored != saved
        save(restored)
        return restored to changed
    }
}
