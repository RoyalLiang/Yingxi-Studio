package io.localplayer.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Artificial practice marks, never a real radio frequency. */
data class SignalRound(val id: String, val revision: Int, val target: Int, val start: Int, val tolerance: Int = 2) {
    init {
        require(id.isNotBlank() && revision > 0 && target in 10..90 && start in 0..100 && tolerance in 0..3)
        require(abs(start - target) > tolerance + 10)
    }
    fun tuned(progress: SignalProgress) = abs(progress.position - target) <= tolerance
}

data class SignalProgress(val round: String, val revision: Int, val position: Int, val revealed: Boolean = false)

object SignalTuning {
    fun restore(round: SignalRound, saved: SignalProgress?): SignalProgress {
        val valid = saved?.takeIf { it.round == round.id && it.revision == round.revision && it.position in 0..100 }
            ?: SignalProgress(round.id, round.revision, round.start)
        return if (round.tuned(valid)) valid.copy(position = round.target, revealed = true) else valid
    }

    fun move(round: SignalRound, saved: SignalProgress, position: Int): SignalProgress {
        val current = restore(round, saved)
        if (round.tuned(current)) return current
        val next = current.copy(position = position.coerceIn(0, 100))
        return if (round.tuned(next)) next.copy(position = round.target, revealed = true) else next
    }

    fun read(round: SignalRound, saved: SignalProgress) = restore(round, saved).copy(revealed = true)

    /** 270 degree dial, with a 90 degree gap below; clamp a gap touch to its nearer endpoint. */
    fun positionForVector(x: Float, y: Float): Int? {
        if (!x.isFinite() || !y.isFinite() || (x == 0f && y == 0f)) return null
        val angle = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())) - 135 + 360) % 360
        return if (angle <= 270) (angle / 270 * 100).roundToInt() else if (angle > 315) 0 else 100
    }
}
