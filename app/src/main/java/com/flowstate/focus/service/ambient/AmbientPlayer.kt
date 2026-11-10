package com.flowstate.focus.service.ambient

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.flowstate.focus.domain.AmbientSound
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Streams [AmbientSynth] (synthesised voices plus the recorded loops from [RawSampleLoader]) into an
 * [AudioTrack] on its own audio-priority thread.
 *
 * The thread only runs while something is audible: once a fade-out reaches silence the track is
 * paused and the thread parks until the next play, so a paused mix costs no CPU or battery.
 * Keeping the process alive with the screen off is the job of the foreground services
 * ([com.flowstate.focus.service.TimerService], which also hosts the timer).
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
    /** Decodes the recorded loops on first use and keeps them for the life of the process. */
    private val samples = RawSampleLoader(context)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Per-sound gains, already scaled by the master volume. */
    fun setTargets(gains: FloatArray) {
        synchronized(lock) { System.arraycopy(gains, 0, targets, 0, min(gains.size, targets.size)) }
        // Decode a recording as soon as its sound is turned up, off the audio thread.
        for (i in 0 until min(gains.size, prefetched.size)) {
            if (gains[i] > 0f && !prefetched[i]) {
                prefetched[i] = true
                SampleVoice.prefetch(samples, AmbientSound.entries[i])
            }
        }
    }
    private val prefetched = BooleanArray(AmbientSound.entries.size)

    /** Fades in or out over [fadeMillis]. Starts the audio thread on demand. */
    fun setAudible(on: Boolean, fadeMillis: Int) {
        fadeMs = fadeMillis.coerceAtLeast(50)
        synchronized(lock) {
            if (on == audible) {
                // The audio thread may have given up on a broken output; try again on the next change.
                if (on && thread?.isAlive != true) { requestFocus(); ensureThread() }
                return
            }
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
        val synth = AmbientSynth(rate, samples)
        var rebuilds = 0
        try {
            while (true) {
                val t = buildTrack(rate) ?: return
                track = t
                val ok = try { play(t, synth) } finally {
                    runCatching { t.stop() }
                    t.release()
                    track = null
                }
                // 1.7.2: write() fails straight away (instead of blocking) once the audio server has died or
                // the output went away. That used to spin this thread at full speed; rebuild the track instead.
                if (ok) return
                if (++rebuilds > MAX_REBUILDS) { Log.w("Flowstate", "Ambient audio output keeps failing; giving up"); return }
                Thread.sleep(250L * rebuilds)
            }
        } catch (_: InterruptedException) {
        } finally {
            synchronized(lock) { abandonFocus() }
        }
    }

    private fun buildTrack(rate: Int): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // About a quarter second of buffer: few wake-ups, and still a quick response to the play button.
        val bytes = max(minBytes, rate / 4 * 2 * 4)
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.onFailure { Log.w("Flowstate", "Ambient AudioTrack not available", it) }.getOrNull()
    }

    /** Renders into [t] until a write fails (returns false). Parks while silent. */
    private fun play(t: AudioTrack, synth: AmbientSynth): Boolean {
        val frames = 1024
        val out = FloatArray(frames * 2)
        val local = FloatArray(targets.size)
        val zero = FloatArray(targets.size)
        var fade = 0f
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
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }.onFailure { return false }
            val want = if (audible && !transientLoss) 1f else 0f
            val step = frames * 1000f / (fadeMs * SAMPLE_RATE)
            fade = if (want > fade) min(want, fade + step) else max(want, fade - step)
            // Equal-power-ish curve so fades sound even.
            val curve = fade * fade * duck
            synth.render(out, frames, if (fade <= 0f) zero else local, curve)
            if (t.write(out, 0, frames * 2, AudioTrack.WRITE_BLOCKING) < 0) return false
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

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val MAX_REBUILDS = 5
    }
}
