package io.localplayer.app

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Bounded, local diagnostics; no media paths, server addresses, accounts or passwords. */
object PlaybackMetrics {
    data class Snapshot(val firstFrameMs: Long?, val rebufferCount: Int, val droppedFrames: Int, val bytes: Long, val readCalls: Long, val readTimeMs: Long)
    private var started = 0L
    private var firstFrame: Long? = null
    private var played = false
    private var wasBuffering = false
    private var rebuffers = 0
    private var dropped = 0
    private var bytes = 0L
    private var reads = 0L
    private var readNanos = 0L
    @Synchronized fun reset() { started = SystemClock.elapsedRealtime(); firstFrame = null; played = false; wasBuffering = false; rebuffers = 0; dropped = 0; bytes = 0; reads = 0; readNanos = 0 }
    @Synchronized fun read(count: Int, nanos: Long) { bytes += count; reads++; readNanos += nanos }
    @Synchronized fun snapshot() = Snapshot(firstFrame, rebuffers, dropped, bytes, reads, readNanos / 1_000_000)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun listener() = object : AnalyticsListener {
        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) { reset() }
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            synchronized(this@PlaybackMetrics) { if (firstFrame == null && started > 0) firstFrame = SystemClock.elapsedRealtime() - started; played = true }
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            synchronized(this@PlaybackMetrics) { if (state == Player.STATE_BUFFERING && !wasBuffering && played) rebuffers++; wasBuffering = state == Player.STATE_BUFFERING }
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { synchronized(this@PlaybackMetrics) { dropped += droppedFrames } }
    }
}
