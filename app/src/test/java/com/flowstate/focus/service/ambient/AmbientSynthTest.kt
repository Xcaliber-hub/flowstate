package com.flowstate.focus.service.ambient

import com.flowstate.focus.domain.AmbientSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AmbientSynthTest {
    private val sr = 48_000

    private fun renderOne(sound: AmbientSound?, seconds: Int, gain: Float = 1f): FloatArray {
        val synth = AmbientSynth(sr)
        val targets = FloatArray(AmbientSound.entries.size)
        if (sound == null) targets.fill(gain) else targets[sound.ordinal] = gain
        val block = 1024
        val out = FloatArray(block * 2)
        val all = FloatArray(sr * seconds * 2)
        var pos = 0
        while (pos + block * 2 <= all.size) {
            synth.render(out, block, targets, 1f)
            System.arraycopy(out, 0, all, pos, block * 2)
            pos += block * 2
        }
        return all
    }

    @Test
    fun everySoundIsAudibleFiniteAndUnclipped() {
        for (s in AmbientSound.entries) {
            val pcm = renderOne(s, 20)
            var sum = 0.0; var peak = 0f
            // Skip the first second while filters and gains settle.
            for (i in sr * 2 until pcm.size) { val v = pcm[i]; assertTrue("${s.name} produced NaN", v.isFinite()); sum += v * v; peak = maxOf(peak, abs(v)) }
            val rms = sqrt(sum / (pcm.size - sr * 2))
            println("${s.name}: rms=%.4f peak=%.3f".format(rms, peak))
            assertTrue("${s.name} too quiet ($rms)", rms > 0.02)
            assertTrue("${s.name} too loud ($rms)", rms < 0.35)
            assertTrue("${s.name} peaks at $peak", peak < 1.0f)
        }
    }

    @Test
    fun fullMixStaysBelowFullScale() {
        val pcm = renderOne(null, 10)
        val peak = pcm.maxOf { abs(it) }
        println("ALL: peak=%.3f".format(peak))
        assertTrue(peak < 1.0f)
    }

    @Test
    fun silentWhenAllGainsZero() {
        val synth = AmbientSynth(sr)
        val out = FloatArray(2048)
        synth.render(out, 1024, FloatArray(6), 1f)
        assertEquals(0f, out.maxOf { abs(it) }, 0f)
        assertTrue(synth.isSilent())
    }

    /** Set FLOWSTATE_WAV_DIR to write 15 s previews of every sound (for listening, not part of CI). */
    @Test
    fun writePreviewsWhenAsked() {
        val dir = System.getenv("FLOWSTATE_WAV_DIR") ?: return
        java.io.File(dir).mkdirs()
        for (s in AmbientSound.entries) {
            val pcm = renderOne(s, 15, 0.8f)
            val bytes = java.io.ByteArrayOutputStream()
            val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size * 2); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(sr); h.putInt(sr * 4); h.putShort(4); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(pcm.size * 2)
            bytes.write(h.array()); bytes.write(data.array())
            java.io.File(dir, "${s.name.lowercase()}.wav").writeBytes(bytes.toByteArray())
        }
    }
}
