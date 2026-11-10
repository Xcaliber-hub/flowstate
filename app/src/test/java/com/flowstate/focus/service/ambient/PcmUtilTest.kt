package com.flowstate.focus.service.ambient

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmUtilTest {
    @Test
    fun floatSamplesAreScaledAndClamped() {
        assertEquals(0.toShort(), PcmUtil.floatToPcm16(0f))
        assertEquals(32767.toShort(), PcmUtil.floatToPcm16(1f))
        assertEquals((-32767).toShort(), PcmUtil.floatToPcm16(-1f))
        assertEquals(32767.toShort(), PcmUtil.floatToPcm16(1.7f))
        assertEquals((-32767).toShort(), PcmUtil.floatToPcm16(-3f))
        assertEquals(16384.toShort(), PcmUtil.floatToPcm16(0.5f))
    }

    @Test
    fun trailingSilentFramesAreTrimmedWholeFramesOnly() {
        val stereo = shortArrayOf(5, 6, 0, 7, 0, 0, 0, 0, 9)
        // 9 samples -> 4 whole stereo frames; the last two frames are silent; frame (0,7) is kept.
        assertEquals(4, PcmUtil.trimTrailingSilence(stereo, stereo.size, 2))
        assertEquals(0, PcmUtil.trimTrailingSilence(ShortArray(6), 6, 2))
        assertEquals(3, PcmUtil.trimTrailingSilence(shortArrayOf(1, 2, 3, 0, 0), 5, 1))
    }

    @Test
    fun builderAppends16BitAndFloatAndGrows() {
        val b = PcmBuilder(2)
        val s16 = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder())
        s16.asShortBuffer().put(shortArrayOf(1, -2, 3))
        b.append(s16, AudioFormat.ENCODING_PCM_16BIT)
        val f = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        f.asFloatBuffer().put(floatArrayOf(1f, -0.5f))
        b.append(f, AudioFormat.ENCODING_PCM_FLOAT)
        assertEquals(5, b.size)
        assertArrayEquals(shortArrayOf(1, -2, 3, 32767, -16383), b.data.copyOf(b.size))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownEncodingIsRejected() {
        PcmBuilder(4).append(ByteBuffer.allocate(4), 99)
    }
}
