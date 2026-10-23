package io.localplayer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

internal enum class NoiseStart { Started, OtherAudio, Unavailable }

/** Foreground, opt-in practice sound. No player service, file, microphone or network. */
internal class TuningNoise(context: Context, private val interrupted: () -> Unit) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start(distance: Int): NoiseStart {
        if (isPlaying) { updateDistance(distance); return NoiseStart.Started }
        stop()
        if (manager.isMusicActive) return NoiseStart.OtherAudio
        val token = ++generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && generation == token) { stop(); interrupted() }
            }, handler).build()
        focus = request
        return try {
            if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                stop(); NoiseStart.Unavailable
            } else {
                val random = Random(49)
                var smoothed = 0f
                val samples = ShortArray(22050) {
                    smoothed = smoothed * .8f + (random.nextFloat() * 2 - 1) * .2f
                    (smoothed * 6000).toInt().toShort()
                }
                val audio = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(22050).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
                track = audio
                check(audio.write(samples, 0, samples.size) == samples.size)
                check(audio.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
                updateDistance(distance)
                audio.play()
                NoiseStart.Started
            }
        } catch (_: RuntimeException) { stop(); NoiseStart.Unavailable }
    }

    fun updateDistance(distance: Int) {
        track?.setVolume((.015f + distance.coerceIn(0, 100) / 100f * .05f))
    }

    fun stop() {
        generation++
        val previous = track; track = null
        if (previous != null) { runCatching { previous.stop() }; previous.release() }
        val request = focus; focus = null
        if (request != null) manager.abandonAudioFocusRequest(request)
    }

    override fun close() = stop()
}
