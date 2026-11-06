package com.flowstate.focus.service.ambient

import com.flowstate.focus.domain.AmbientSound
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 1.7.0 ambient sounds, synthesised in real time. No recordings ship with the app, so there is
 * nothing to license and the APK does not grow. Every voice is filtered or modulated noise plus a
 * few randomly timed events (drops, crackles, clinks, page turns). Nothing allocates while
 * rendering, and a voice whose gain is zero is skipped entirely.
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

internal class RainVoice(sr: Float) : Voice(sr, 23) {
    private val pinkL = Pink(Rng(303)); private val pinkR = Pink(Rng(404))
    private val far = Brown(Rng(505))
    private val hpL = OnePole(onePoleCoef(450f, sr)); private val hpR = OnePole(onePoleCoef(450f, sr))
    private val lpL = OnePole(onePoleCoef(7000f, sr)); private val lpR = OnePole(onePoleCoef(7000f, sr))
    private val farLp = OnePole(onePoleCoef(700f, sr))
    private val drops = 24
    private val dOsc = Array(drops) { Osc() }
    private val dEnv = FloatArray(drops); private val dDecay = FloatArray(drops)
    private val dPanL = FloatArray(drops); private val dPanR = FloatArray(drops)
    private val dClick = IntArray(drops)
    private var nextDrop = 1
    private var lfo = 0.0
    private val lfoStep = 2.0 * PI * 0.07 / sr

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val dg = (g1 - g0) / n
        var g = g0
        for (i in 0 until n) {
            lfo += lfoStep
            val swell = 0.9f + 0.1f * sin(lfo).toFloat()
            val f = farLp.lp(far.next()) * 0.35f
            var sl = lpL.lp(hpL.hp(pinkL.next())) * 0.8f * swell + f
            var sr2 = lpR.lp(hpR.hp(pinkR.next())) * 0.8f * swell + f
            if (--nextDrop <= 0) {
                nextDrop = wait(70f)
                spawnDrop()
            }
            for (k in 0 until drops) {
                val e = dEnv[k]
                if (e < 1e-4f) continue
                var v = dOsc[k].next() * e
                if (dClick[k] > 0) { v += rng.next() * e * 0.6f; dClick[k]-- }
                sl += v * dPanL[k]; sr2 += v * dPanR[k]
                dEnv[k] = e * dDecay[k]
            }
            l[i] += sl * g; r[i] += sr2 * g
            g += dg
        }
    }

    private fun spawnDrop() {
        for (k in 0 until drops) {
            if (dEnv[k] < 1e-4f) {
                val u = rng.unit()
                dEnv[k] = 0.04f + 0.30f * u * u * u
                dOsc[k].start(rng.range(1800f, 5200f), sr)
                val tau = rng.range(0.002f, 0.007f)
                dDecay[k] = exp(-1f / (tau * sr))
                val p = rng.unit()
                dPanL[k] = sqrt(1f - p); dPanR[k] = sqrt(p)
                dClick[k] = (sr * 0.0007f).toInt()
                return
            }
        }
    }
}

internal class WavesVoice(sr: Float) : Voice(sr, 37) {
    private val pink = Pink(Rng(606)); private val brown = Brown(Rng(707))
    private val pink2 = Pink(Rng(808)); private val brown2 = Brown(Rng(909))
    private val surf = Brown(Rng(1001))
    private val surfLp = OnePole(onePoleCoef(260f, sr))
    private val lpA = OnePole(0.1f); private val lpB = OnePole(0.1f)
    private val envA = OnePole(onePoleCoef(2.5f, sr)); private val envB = OnePole(onePoleCoef(2.5f, sr))
    private var tA = 0f; private var tB = 0.45f
    private var stepA = 1f / (10f * sr); private var stepB = 1f / (12f * sr)
    private var count = 0
    private var eA = 0f; private var eB = 0f

    private fun shape(t: Float): Float = if (t < 0.6f) {
        val x = t / 0.6f; x * x * (0.6f + 0.4f * x)
    } else exp(-(t - 0.6f) * 6f)

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val dg = (g1 - g0) / n
        var g = g0
        for (i in 0 until n) {
            tA += stepA; if (tA >= 1f) { tA -= 1f; stepA = 1f / (rng.range(8f, 13f) * sr) }
            tB += stepB; if (tB >= 1f) { tB -= 1f; stepB = 1f / (rng.range(9f, 14f) * sr) }
            eA = envA.lp(shape(tA)); eB = envB.lp(shape(tB))
            if (count-- <= 0) {
                count = 32
                lpA.a = onePoleCoef(220f + 3000f * eA * eA, sr)
                lpB.a = onePoleCoef(220f + 3000f * eB * eB, sr)
            }
            val a = lpA.lp(pink.next() * 0.7f + brown.next() * 0.5f) * (0.10f + 1.1f * eA)
            val b = lpB.lp(pink2.next() * 0.7f + brown2.next() * 0.5f) * (0.10f + 1.1f * eB)
            val s = surfLp.lp(surf.next()) * 0.35f
            l[i] += (a * 0.8f + b * 0.35f + s) * g
            r[i] += (a * 0.35f + b * 0.8f + s) * g
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

/**
 * Café: a murmur of distant voices (pulse-excited vowel formants with syllable rhythm and pauses),
 * room tone, and the odd cup clink or burst of milk steam.
 */
internal class CafeVoice(sr: Float) : Voice(sr, 53) {
    private val talkers = 6
    private val pitch = FloatArray(talkers); private val phase = FloatArray(talkers)
    private val f1 = Array(talkers) { BandPass(sr) }; private val f2 = Array(talkers) { BandPass(sr) }
    private val amp = FloatArray(talkers); private val target = FloatArray(talkers)
    private val syllable = IntArray(talkers); private val phrase = IntArray(talkers)
    private val talking = BooleanArray(talkers)
    private val panL = FloatArray(talkers); private val panR = FloatArray(talkers); private val dist = FloatArray(talkers)
    private val vib = FloatArray(talkers)
    private val ampCoef = onePoleCoef(22f, sr)
    private val distL = OnePole(onePoleCoef(2600f, sr)); private val distR = OnePole(onePoleCoef(2600f, sr))
    private val room = Pink(Rng(1301)); private val roomLp = OnePole(onePoleCoef(1800f, sr))
    private val roomB = Brown(Rng(1402))
    // Clinks: three inharmonic partials each.
    private val clinkOsc = Array(9) { Osc() }; private val clinkEnv = FloatArray(3); private val clinkDecay = FloatArray(3)
    private val clinkPan = FloatArray(3)
    private var nextClink = 0
    private var steamLeft = 0; private var steamLen = 1; private var nextSteam = 0
    private val steam = Pink(Rng(1503)); private val steamHp = OnePole(onePoleCoef(2500f, sr))
    private var formantCount = 0

    init {
        for (t in 0 until talkers) newSpeaker(t)
        nextClink = wait(0.12f); nextSteam = wait(1f / 70f)
    }

    private fun newSpeaker(t: Int) {
        pitch[t] = if (rng.chance(0.5f)) rng.range(95f, 140f) else rng.range(170f, 240f)
        val p = rng.unit(); panL[t] = sqrt(1f - p); panR[t] = sqrt(p)
        dist[t] = rng.range(0.35f, 1f)
        talking[t] = rng.chance(0.6f)
        phrase[t] = (sr * rng.range(0.5f, 3f)).toInt()
        vowel(t)
    }

    private fun vowel(t: Int) {
        f1[t].set(rng.range(300f, 850f), 4f)
        f2[t].set(rng.range(900f, 2300f), 5f)
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        val dg = (g1 - g0) / n
        var g = g0
        for (i in 0 until n) {
            var ml = 0f; var mr = 0f
            for (t in 0 until talkers) {
                if (--phrase[t] <= 0) {
                    talking[t] = !talking[t]
                    phrase[t] = (sr * if (talking[t]) rng.range(1.2f, 5f) else rng.range(0.4f, 2.5f)).toInt()
                    if (!talking[t] && rng.chance(0.15f)) newSpeaker(t)
                }
                if (--syllable[t] <= 0) {
                    syllable[t] = (sr * rng.range(0.08f, 0.24f)).toInt()
                    target[t] = if (talking[t] && !rng.chance(0.18f)) rng.range(0.4f, 1f) else 0f
                    vowel(t)
                }
                amp[t] += ampCoef * (target[t] - amp[t])
                if (amp[t] < 0.002f) continue
                vib[t] += 1f / sr
                val f0 = pitch[t] * (1f + 0.04f * sin(vib[t] * 5.1f + t))
                phase[t] += f0 / sr
                var ex = rng.next() * 2f
                if (phase[t] >= 1f) { phase[t] -= 1f; ex += 36f }
                val v = (f1[t].run(ex) + f2[t].run(ex) * 0.6f) * amp[t] * dist[t]
                ml += v * panL[t]; mr += v * panR[t]
            }
            val roomTone = roomLp.lp(room.next()) * 0.16f + roomB.next() * 0.10f
            var sl = distL.lp(ml) * 0.55f + roomTone
            var sr2 = distR.lp(mr) * 0.55f + roomTone

            if (--nextClink <= 0) { nextClink = wait(0.12f); clink() }
            for (c in 0 until 3) {
                val e = clinkEnv[c]
                if (e < 1e-4f) continue
                val v = (clinkOsc[c * 3].next() + clinkOsc[c * 3 + 1].next() * 0.5f + clinkOsc[c * 3 + 2].next() * 0.25f) * e
                sl += v * (1f - clinkPan[c]); sr2 += v * clinkPan[c]
                clinkEnv[c] = e * clinkDecay[c]
            }
            if (steamLeft > 0) {
                val x = 1f - steamLeft.toFloat() / steamLen
                val env = min(1f, x * 8f) * min(1f, (1f - x) * 4f) * 0.07f
                val v = steamHp.hp(steam.next()) * env
                sl += v * 0.7f; sr2 += v * 0.4f
                steamLeft--
            } else if (--nextSteam <= 0) {
                nextSteam = wait(1f / 70f)
                steamLen = (sr * rng.range(2.5f, 4f)).toInt(); steamLeft = steamLen
            }
            l[i] += sl * g; r[i] += sr2 * g
            g += dg
        }
    }

    private fun clink() {
        for (c in 0 until 3) {
            if (clinkEnv[c] < 1e-4f) {
                val f = rng.range(2000f, 3200f)
                clinkOsc[c * 3].start(f, sr); clinkOsc[c * 3 + 1].start(f * 2.32f, sr); clinkOsc[c * 3 + 2].start(f * 4.25f, sr)
                clinkEnv[c] = rng.range(0.05f, 0.14f)
                clinkDecay[c] = exp(-1f / (rng.range(0.06f, 0.18f) * sr))
                clinkPan[c] = rng.range(0.15f, 0.85f)
                if (rng.chance(0.3f)) nextClink = (sr * rng.range(0.12f, 0.3f)).toInt()
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
 * The mixer: six voices, each with its own smoothed gain, summed and soft-limited.
 * [render] is called from the audio thread only.
 */
class AmbientSynth(sampleRate: Int) {
    private val sr = sampleRate.toFloat()
    private val voices: Array<Voice> = AmbientSound.entries.map { s ->
        when (s) {
            AmbientSound.RAIN -> RainVoice(sr)
            AmbientSound.LIBRARY -> LibraryVoice(sr)
            AmbientSound.BROWN -> BrownVoice(sr)
            AmbientSound.CAFE -> CafeVoice(sr)
            AmbientSound.FIRE -> FireVoice(sr)
            AmbientSound.WAVES -> WavesVoice(sr)
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

    /** True once every voice has glided to silence. */
    fun isSilent(): Boolean = current.all { it <= 0f }

    private fun soft(x: Float): Float {
        val c = min(abs(x), 3f)
        val y = c * (27f + c * c) / (27f + 9f * c * c)
        return if (x < 0) -y else y
    }

    companion object { const val MAX_BLOCK = 2048 }
}
