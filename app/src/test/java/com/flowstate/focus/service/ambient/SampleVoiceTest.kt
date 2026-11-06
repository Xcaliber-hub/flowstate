package com.flowstate.focus.service.ambient

import com.flowstate.focus.domain.AmbientSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SampleVoiceTest {
    private val sr = 48_000

    /** [cycles] whole periods of a sine across [frames], so a seamless loop has no jump at the wrap. */
    private fun sine(frames: Int, cycles: Int, channels: Int, amp: Float = 16000f) =
        SamplePcm(ShortArray(frames * channels) { i ->
            (amp * sin(2.0 * PI * cycles * (i / channels) / frames)).toInt().toShort()
        }, channels, sr)

    private fun render(v: Voice, frames: Int, g: Float, block: Int = 1000): Pair<FloatArray, FloatArray> {
        val l = FloatArray(frames); val r = FloatArray(frames)
        val bl = FloatArray(block); val br = FloatArray(block)
        var pos = 0
        while (pos < frames) {
            val n = minOf(block, frames - pos)
            java.util.Arrays.fill(bl, 0f); java.util.Arrays.fill(br, 0f)
            v.render(bl, br, n, g, g)
            System.arraycopy(bl, 0, l, pos, n); System.arraycopy(br, 0, r, pos, n)
            pos += n
        }
        return l to r
    }

    @Test
    fun loopWrapsWithoutAJump() {
        val frames = 4_800
        val cycles = 50 // 500 Hz
        val pcm = sine(frames, cycles, 2)
        val (l, r) = render(SampleVoice(sr.toFloat(), pcm), frames * 3 + 123, 1f, block = 777)
        // Largest step a 500 Hz sine at this amplitude can take between samples, with a little slack.
        val maxStep = (16000f / 32768f) * (2 * PI * cycles / frames).toFloat() * 1.02f
        for (i in 1 until l.size) {
            assertTrue("jump at $i (${abs(l[i] - l[i - 1])})", abs(l[i] - l[i - 1]) <= maxStep)
            assertTrue(abs(r[i] - r[i - 1]) <= maxStep)
        }
        // Each pass replays the buffer exactly.
        for (i in 0 until frames) assertEquals(l[i], l[i + frames], 1e-6f)
    }

    @Test
    fun monoPlaysOnBothChannels() {
        val (l, r) = render(SampleVoice(sr.toFloat(), sine(2_400, 10, 1)), 5_000, 0.7f)
        for (i in l.indices) assertEquals(l[i], r[i], 0f)
        assertTrue(l.maxOf { abs(it) } > 0.3f)
    }

    @Test
    fun gainRampsAcrossTheBlock() {
        val dc = SamplePcm(ShortArray(1_000) { 16384 }, 1, sr)
        val v = SampleVoice(sr.toFloat(), dc)
        val l = FloatArray(512); val r = FloatArray(512)
        v.render(l, r, 512, 0f, 1f)
        assertEquals(0f, l[0], 1e-6f)
        for (i in 1 until 512) assertTrue("not rising at $i", l[i] > l[i - 1])
        assertEquals(0.5f * 511f / 512f, l[511], 1e-4f)
    }

    @Test
    fun otherSampleRatesPlayAtTheirOwnSpeed() {
        // A 24 kHz loop of 2 400 frames lasts 0.1 s: 4 800 output frames at 48 kHz.
        val pcm = SamplePcm(sine(2_400, 12, 1).data, 1, 24_000)
        val (l, _) = render(SampleVoice(sr.toFloat(), pcm), 9_600, 1f)
        for (i in 0 until 4_800) assertEquals(l[i], l[i + 4_800], 1e-4f)
    }

    @Test
    fun silentUntilTheRecordingArrivesThenFadesIn() {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val loader = SampleLoader { calls.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); sine(4_800, 100, 2) }
        val v = SampleVoice(sr.toFloat(), AmbientSound.WAVES, loader)
        val (l0, _) = render(v, 20_000, 1f)
        assertEquals(0f, l0.maxOf { abs(it) }, 0f)
        assertTrue(!v.isReady)
        gate.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (!v.isReady && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(v.isReady)
        val (l1, _) = render(v, 24_000, 1f)
        // Quarter-second fade-in: the first few milliseconds are far below full level.
        assertTrue(l1.take(100).maxOf { abs(it) } < 0.01f)
        assertTrue(l1.takeLast(4_800).maxOf { abs(it) } > 0.45f)
        assertEquals("decoded once", 1, calls.get())
    }

    @Test
    fun nothingIsRequestedAtZeroGain() {
        val calls = AtomicInteger()
        val v = SampleVoice(sr.toFloat(), AmbientSound.RAIN, SampleLoader { calls.incrementAndGet(); null })
        render(v, 10_000, 0f)
        Thread.sleep(50)
        assertEquals(0, calls.get())
    }
}
