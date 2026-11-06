package com.flowstate.focus.service.ambient

import com.flowstate.focus.domain.AmbientSound
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Ambient sounds. Since 1.7.1 Rain, Waves, Café and Crickets are short recorded loops
 * (res/raw/ambient_*.ogg, see NOTICE.md) played by [SampleVoice]; Library, Brown noise and Fireplace
 * are still synthesised in real time: filtered or modulated noise plus a few randomly timed events
 * (crackles, page turns, footsteps). Nothing allocates while rendering (apart from handing a
 * recording's first decode to a background thread), and a voice whose gain is zero is skipped.
 */

/** xorshift32: fast white noise in -1..1. */
internal class Rng(seed: Int) {
    private var s = if (seed == 0) 0x2545F491 else seed
    fun next(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return x * 4.656613e-10f
    }
    /** 0..1 */
    fun unit(): Float = (next() + 1f) * 0.5f
    fun range(a: Float, b: Float): Float = a + (b - a) * unit()
    /** True with probability [p] (per call). */
    fun chance(p: Float): Boolean = unit() < p
}

internal fun onePoleCoef(fc: Float, sr: Float): Float = 1f - exp(-2.0 * PI * fc / sr).toFloat()

internal class OnePole(var a: Float) {
    var y = 0f
    fun lp(x: Float): Float { y += a * (x - y); return y }
    fun hp(x: Float): Float { y += a * (x - y); return x - y }
}

/** RBJ band-pass (0 dB peak gain). */
internal class BandPass(private val sr: Float) {
    private var b0 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    fun set(fc: Float, q: Float) {
        val w = 2.0 * PI * min(fc, sr * 0.45f) / sr
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (alpha / a0).toFloat(); b2 = (-alpha / a0).toFloat()
        a1 = (-2.0 * cos(w) / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
    }
    fun run(x: Float): Float {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
}

internal class Pink(private val rng: Rng) {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    fun next(): Float {
        val w = rng.next()
        b0 = 0.99765f * b0 + w * 0.0990460f
        b1 = 0.96300f * b1 + w * 0.2965164f
        b2 = 0.57000f * b2 + w * 1.0526913f
        return (b0 + b1 + b2 + w * 0.1848f) * 0.18f
    }
}

internal class Brown(private val rng: Rng) {
    private var b = 0f
    fun next(): Float {
        b = (b + 0.02f * rng.next()) / 1.02f
        return b * 3.5f
    }
}

/** Sine by rotation: no trig per sample. */
internal class Osc {
    private var c = 1f; private var s = 0f; private var cw = 1f; private var sw = 0f
    fun start(freq: Float, sr: Float) {
        val w = 2.0 * PI * freq / sr
        cw = cos(w).toFloat(); sw = sin(w).toFloat(); c = 1f; s = 0f
    }
    fun next(): Float {
        val nc = c * cw - s * sw
        s = s * cw + c * sw
        c = nc
        return s
    }
}

/** One sound. Adds into [l]/[r] with a gain ramping from [g0] to [g1] across the block. */
internal abstract class Voice(protected val sr: Float, seed: Int) {
    protected val rng = Rng(seed)
    abstract fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float)
    /** Random wait in samples before an event with an average rate of [perSecond]. */
    protected fun wait(perSecond: Float): Int {
        val u = max(rng.unit(), 1e-4f)
        return (-kotlin.math.ln(u) / perSecond * sr).toInt().coerceAtLeast(1)
    }
}

/** Decoded 16-bit PCM, interleaved L/R when [channels] is 2. */
class SamplePcm(val data: ShortArray, val channels: Int, val sampleRate: Int) {
    init { require(channels == 1 || channels == 2) { "1 or 2 channels, not $channels" } }
    val frames: Int get() = data.size / channels
}

/**
 * Supplies the recording behind a sound, or null when it has none. [load] may block (it decodes);
 * it is only called from a background thread, once per voice.
 */
fun interface SampleLoader {
    fun load(sound: AmbientSound): SamplePcm?
}

/**
 * Plays a recorded loop. The loop files are already seamless (the tail is crossfaded into the head
 * when they are made), so playback just wraps the read position; reads interpolate across the wrap,
 * so a buffer at another sample rate also loops without a click. The recording is requested the
 * first time the voice is asked to sound; until it arrives the voice is silent, and it then fades
 * in over a quarter second so a late arrival under a raised slider does not pop. A mono recording
 * plays at full level on both channels.
 */
internal class SampleVoice private constructor(
    sr: Float,
    private val sound: AmbientSound?,
    private val loader: SampleLoader?,
    ready: SamplePcm?,
) : Voice(sr, 0) {
    /** Loads [sound] through [loader] on first use. */
    constructor(sr: Float, sound: AmbientSound, loader: SampleLoader?) : this(sr, sound, loader, null)
    /** Plays [pcm] straight away (tests, previews). */
    constructor(sr: Float, pcm: SamplePcm) : this(sr, null, null, pcm)

    @Volatile private var pcm: SamplePcm? = ready?.takeIf { it.frames > 1 }
    @Volatile private var requested = ready != null
    private var pos = 0.0
    private var onset = if (pcm != null) 1f else 0f
    private val onsetStep = 1f / (0.25f * sr)

    val isReady: Boolean get() = pcm != null

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val p = pcm
        if (p == null) {
            if (!requested && g1 > 0f) request()
            return
        }
        val d = p.data
        val frames = p.frames
        val step = p.sampleRate / sr.toDouble()
        val stereo = p.channels == 2
        val dg = (g1 - g0) / n
        var g = g0
        var x = pos
        for (i in 0 until n) {
            val i0 = x.toInt()
            val f = (x - i0).toFloat()
            val i1 = if (i0 + 1 >= frames) 0 else i0 + 1
            val a: Float
            val b: Float
            if (stereo) {
                val a0 = d[i0 * 2].toFloat(); val b0 = d[i0 * 2 + 1].toFloat()
                a = a0 + (d[i1 * 2] - a0) * f
                b = b0 + (d[i1 * 2 + 1] - b0) * f
            } else {
                val a0 = d[i0].toFloat()
                a = a0 + (d[i1] - a0) * f
                b = a
            }
            if (onset < 1f) onset = min(1f, onset + onsetStep)
            val k = g * onset * PCM_SCALE
            l[i] += a * k; r[i] += b * k
            g += dg
            x += step
            if (x >= frames) x -= frames
        }
        pos = x
    }

    private fun request() {
        requested = true
        val s = sound ?: return
        val ld = loader ?: return
        DECODER.execute {
            val got = runCatching { ld.load(s) }.getOrNull()
            if (got != null && got.frames > 1) pcm = got
        }
    }

    companion object {
        private const val PCM_SCALE = 1f / 32768f
        /** One low-priority daemon thread for every first decode. */
        private val DECODER: ExecutorService = Executors.newSingleThreadExecutor { job ->
            Thread(job, "flowstate-ambient-decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}

// ---------------------------------------------------------------------------------------------

internal class BrownVoice(sr: Float) : Voice(sr, 11) {
    private val bl = Brown(Rng(101)); private val br = Brown(Rng(202))
    private val lpL = OnePole(onePoleCoef(900f, sr)); private val lpR = OnePole(onePoleCoef(900f, sr))
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val dg = (g1 - g0) / n
        var g = g0
        for (i in 0 until n) {
            l[i] += lpL.lp(bl.next()) * g * 0.9f
            r[i] += lpR.lp(br.next()) * g * 0.9f
            g += dg
        }
    }
}

internal class FireVoice(sr: Float) : Voice(sr, 41) {
    private val rumble = Brown(Rng(1101)); private val rumbleLp = OnePole(onePoleCoef(170f, sr))
    private val roar = Pink(Rng(1202)); private val roarBp = BandPass(sr).apply { set(600f, 0.7f) }
    private var roarLevel = 0.2f; private var roarTarget = 0.2f; private var roarCount = 0
    private val pool = 16
    private val cEnv = FloatArray(pool); private val cDecay = FloatArray(pool)
    private val cPanL = FloatArray(pool); private val cPanR = FloatArray(pool)
    private val cPrev = FloatArray(pool)
    private val cOsc = Array(pool) { Osc() }; private val cTone = FloatArray(pool)
    private var nextCrackle = 1
    private var burst = 0

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val dg = (g1 - g0) / n
        var g = g0
        for (i in 0 until n) {
            if (roarCount-- <= 0) { roarCount = (sr * 0.25f).toInt(); roarTarget = rng.range(0.10f, 0.34f) }
            roarLevel += (roarTarget - roarLevel) * 0.00005f
            val bed = rumbleLp.lp(rumble.next()) * 0.9f + roarBp.run(roar.next()) * roarLevel * 2.2f
            var sl = bed; var sr2 = bed
            if (--nextCrackle <= 0) {
                if (burst <= 0 && rng.chance(0.08f)) burst = (rng.range(3f, 9f)).toInt()
                nextCrackle = if (burst > 0) { burst--; (sr * rng.range(0.008f, 0.05f)).toInt() } else wait(7f)
                spawn(pop = rng.chance(0.06f))
            }
            for (k in 0 until pool) {
                val e = cEnv[k]
                if (e < 1e-4f) continue
                val w = rng.next()
                val hp = w - cPrev[k]; cPrev[k] = w
                val v = (hp * 0.5f + cOsc[k].next() * cTone[k]) * e
                sl += v * cPanL[k]; sr2 += v * cPanR[k]
                cEnv[k] = e * cDecay[k]
            }
            l[i] += sl * g; r[i] += sr2 * g
            g += dg
        }
    }

    private fun spawn(pop: Boolean) {
        for (k in 0 until pool) {
            if (cEnv[k] < 1e-4f) {
                val u = rng.unit()
                cEnv[k] = if (pop) rng.range(0.5f, 0.8f) else 0.08f + 0.6f * u * u
                val tau = if (pop) rng.range(0.006f, 0.012f) else rng.range(0.0003f, 0.0016f)
                cDecay[k] = exp(-1f / (tau * sr))
                cOsc[k].start(if (pop) rng.range(180f, 420f) else rng.range(2500f, 6000f), sr)
                cTone[k] = if (pop) 0.9f else 0.2f
                val p = rng.range(0.2f, 0.8f)
                cPanL[k] = sqrt(1f - p); cPanR[k] = sqrt(p)
                return
            }
        }
    }
}

/** Library: quiet room tone with a page turning now and then, pencil strokes and distant footsteps. */
internal class LibraryVoice(sr: Float) : Voice(sr, 67) {
    private val hum = Brown(Rng(1601)); private val humLp = OnePole(onePoleCoef(380f, sr))
    private val air = Pink(Rng(1702)); private val airLp = OnePole(onePoleCoef(1100f, sr))
    // Page turn
    private val page = BandPass(sr); private var pageLeft = 0; private var pageLen = 1; private var nextPage = 0
    private var pageFlutter = 20f; private var pageGain = 0.15f; private var pagePan = 0.5f
    // Pencil
    private val pencil = BandPass(sr).apply { set(4800f, 1.5f) }
    private var pencilLeft = 0; private var strokeLeft = 0; private var strokeGap = 0; private var nextPencil = 0
    // Footsteps
    private var stepsLeft = 0; private var stepTimer = 0; private var stepTotal = 1; private var nextSteps = 0
    private val thump = Osc(); private var thumpEnv = 0f; private var thumpDecay = 0.999f
    private val thumpLp = OnePole(onePoleCoef(700f, sr))
    private var count = 0

    init {
        nextPage = wait(1f / 6f); nextPencil = wait(1f / 12f); nextSteps = wait(1f / 25f)
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val dg = (g1 - g0) / n
        var g = g0
        for (i in 0 until n) {
            val tone = humLp.lp(hum.next()) * 0.55f + airLp.lp(air.next()) * 0.10f
            var sl = tone; var sr2 = tone

            if (pageLeft > 0) {
                val x = 1f - pageLeft.toFloat() / pageLen
                if (count-- <= 0) { count = 64; page.set(4200f - 2600f * x, 0.9f) }
                val env = min(1f, x * 12f) * (1f - x) * (1f - x)
                val flutter = 0.55f + 0.45f * abs(sin(x * pageFlutter * pageLen / sr * 6.28f))
                val v = page.run(rng.next()) * env * flutter * pageGain * 3f
                sl += v * (1f - pagePan); sr2 += v * pagePan
                pageLeft--
            } else if (--nextPage <= 0) {
                nextPage = wait(1f / 8f)
                pageLen = (sr * rng.range(0.35f, 0.6f)).toInt(); pageLeft = pageLen
                pageFlutter = rng.range(14f, 28f); pageGain = rng.range(0.10f, 0.2f); pagePan = rng.range(0.25f, 0.75f)
            }

            if (pencilLeft > 0) {
                pencilLeft--
                if (strokeLeft > 0) {
                    strokeLeft--
                    val v = pencil.run(rng.next()) * 0.08f
                    sl += v * 0.6f; sr2 += v * 0.4f
                } else if (--strokeGap <= 0) {
                    strokeLeft = (sr * rng.range(0.05f, 0.15f)).toInt(); strokeGap = (sr * rng.range(0.04f, 0.2f)).toInt()
                }
            } else if (--nextPencil <= 0) {
                nextPencil = wait(1f / 15f); pencilLeft = (sr * rng.range(0.6f, 1.6f)).toInt()
            }

            if (stepsLeft > 0) {
                if (--stepTimer <= 0) {
                    stepsLeft--
                    stepTimer = (sr * rng.range(0.5f, 0.62f)).toInt()
                    val pos = 1f - stepsLeft.toFloat() / stepTotal
                    thump.start(rng.range(70f, 105f), sr)
                    thumpEnv = 0.25f * sin(pos * PI.toFloat()).coerceAtLeast(0.15f)
                    thumpDecay = exp(-1f / (0.045f * sr))
                }
            } else if (--nextSteps <= 0) {
                nextSteps = wait(1f / 35f); stepTotal = rng.range(5f, 9f).toInt(); stepsLeft = stepTotal; stepTimer = 1
            }
            if (thumpEnv > 1e-4f) {
                val v = (thump.next() + thumpLp.lp(rng.next()) * 0.4f) * thumpEnv
                sl += v; sr2 += v * 0.8f
                thumpEnv *= thumpDecay
            }
            l[i] += sl * g; r[i] += sr2 * g
            g += dg
        }
    }
}

/**
 * The mixer: one voice per [AmbientSound], each with its own smoothed gain, summed and soft-limited.
 * Recorded sounds come from [samples]; without a loader they stay silent. [render] is called from
 * the audio thread only.
 */
class AmbientSynth(sampleRate: Int, samples: SampleLoader? = null) {
    private val sr = sampleRate.toFloat()
    private val voices: Array<Voice> = AmbientSound.entries.map { s ->
        when (s) {
            AmbientSound.RAIN, AmbientSound.CAFE, AmbientSound.WAVES, AmbientSound.CRICKETS -> SampleVoice(sr, s, samples)
            AmbientSound.LIBRARY -> LibraryVoice(sr)
            AmbientSound.BROWN -> BrownVoice(sr)
            AmbientSound.FIRE -> FireVoice(sr)
        }
    }.toTypedArray()
    private val current = FloatArray(voices.size)
    private val l = FloatArray(MAX_BLOCK); private val r = FloatArray(MAX_BLOCK)

    /**
     * Renders [frames] interleaved stereo frames into [out]. [targets] are per-voice gains (0..1);
     * each voice glides to its target within the block so volume changes never click.
     */
    fun render(out: FloatArray, frames: Int, targets: FloatArray, master: Float) {
        val n = min(frames, MAX_BLOCK)
        java.util.Arrays.fill(l, 0, n, 0f); java.util.Arrays.fill(r, 0, n, 0f)
        for (v in voices.indices) {
            val from = current[v]
            val to = from + (targets[v] - from) * 0.25f
            val snapped = if (abs(to - targets[v]) < 1e-3f) targets[v] else to
            current[v] = snapped
            if (from <= 0f && snapped <= 0f) continue
            voices[v].render(l, r, n, from, snapped)
        }
        var j = 0
        for (i in 0 until n) {
            out[j++] = soft(l[i] * master)
            out[j++] = soft(r[i] * master)
        }
    }

    /** True when [sound] is synthesised, or its recording has been decoded and is playing. */
    internal fun isReady(sound: AmbientSound): Boolean = (voices[sound.ordinal] as? SampleVoice)?.isReady ?: true

    /** True once every voice has glided to silence. */
    fun isSilent(): Boolean = current.all { it <= 0f }

    private fun soft(x: Float): Float {
        val c = min(abs(x), 3f)
        val y = c * (27f + c * c) / (27f + 9f * c * c)
        return if (x < 0) -y else y
    }

    companion object { const val MAX_BLOCK = 2048 }
}
