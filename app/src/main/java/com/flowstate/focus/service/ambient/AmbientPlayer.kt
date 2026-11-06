package com.flowstate.focus.service.ambient

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.flowstate.focus.domain.AmbientSound
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Streams [AmbientSynth] into an [AudioTrack] on its own audio-priority thread.
 *
 * The thread only runs while something is audible: once a fade-out reaches silence the track is
 * paused and the thread parks until the next play, so a paused mix costs no CPU or battery.
 * Keeping the process alive with the screen off is the job of the foreground services
 * ([AmbientService] on its own, or the timer's service during a session).
 */
@Singleton
class AmbientPlayer @Inject constructor(@ApplicationContext private val context: Context) {

    /** Called on the audio-focus thread: true = another app took the audio for good (a call, music). */
    var onFocusLost: (() -> Unit)? = null

    private val lock = Object()
    private val targets = FloatArray(AmbientSound.entries.size)
    @Volatile private var audible = false
    @Volatile private var fadeMs = 1200
    @Volatile private var duck = 1f
    @Volatile private var transientLoss = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Per-sound gains, already scaled by the master volume. */
    fun setTargets(gains: FloatArray) {
        synchronized(lock) { System.arraycopy(gains, 0, targets, 0, min(gains.size, targets.size)) }
    }

    /** Fades in or out over [fadeMillis]. Starts the audio thread on demand. */
    fun setAudible(on: Boolean, fadeMillis: Int) {
        fadeMs = fadeMillis.coerceAtLeast(50)
        synchronized(lock) {
            if (on == audible) return
            audible = on
            if (on) {
                requestFocus()
                ensureThread()
            }
            lock.notifyAll()
        }
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::loop, "flowstate-ambient").apply { isDaemon = true; start() }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val rate = SAMPLE_RATE
        val frames = 1024
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // About a quarter second of buffer: few wake-ups, and still a quick response to the play button.
        val bytes = max(minBytes, rate / 4 * 2 * 4)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = t
        val synth = AmbientSynth(rate)
        val out = FloatArray(frames * 2)
        val local = FloatArray(targets.size)
        val zero = FloatArray(targets.size)
        var fade = 0f
        try {
            while (true) {
                // Park while silent and nothing should play.
                synchronized(lock) {
                    while (!audible && fade <= 0f && synth.isSilent()) {
                        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) { t.pause(); t.flush() }
                        abandonFocus()
                        lock.wait()
                    }
                    System.arraycopy(targets, 0, local, 0, local.size)
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                val want = if (audible && !transientLoss) 1f else 0f
                val step = frames * 1000f / (fadeMs * rate)
                fade = if (want > fade) min(want, fade + step) else max(want, fade - step)
                // Equal-power-ish curve so fades sound even.
                val curve = fade * fade * duck
                synth.render(out, frames, if (fade <= 0f) zero else local, curve)
                t.write(out, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { t.stop() }
            t.release()
            track = null
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { transientLoss = false; onFocusLost?.invoke() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientLoss = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck = 0.35f
            AudioManager.AUDIOFOCUS_GAIN -> { transientLoss = false; duck = 1f }
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        transientLoss = false; duck = 1f
        runCatching { audio.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { audio.abandonAudioFocusRequest(req) }
    }

    companion object { const val SAMPLE_RATE = 48_000 }
}
