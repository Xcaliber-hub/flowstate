package com.flowstate.focus.service.ambient

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.flowstate.focus.R
import com.flowstate.focus.domain.AmbientSound
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes the recorded loops in `res/raw` (48 kHz Ogg Vorbis, see NOTICE.md) to 16-bit PCM with
 * MediaExtractor + MediaCodec. Each file is decoded once, the first time its sound is turned up (or
 * prefetched when it is switched on), and cached for the life of the process (about 2 to 10 MB each).
 * Called off the audio thread by [SampleVoice].
 *
 * 1.7.2 hardening of the decode loop: the output format is read from the decoder (channels, rate and
 * encoding can change after the first buffer), float PCM output is converted instead of failing,
 * a decoder that never signals end of stream can no longer spin forever, and a failure returns null
 * (logged) instead of throwing.
 */
class RawSampleLoader(context: Context) : SampleLoader {
    private val app = context.applicationContext
    private val cache = ConcurrentHashMap<AmbientSound, SamplePcm>()

    override fun load(sound: AmbientSound): SamplePcm? {
        cache[sound]?.let { return it }
        val res = rawFor(sound) ?: return null
        return synchronized(this) {
            cache[sound] ?: runCatching { decode(res) }
                .onFailure { Log.w("Flowstate", "Could not decode the ${sound.name} loop", it) }
                .getOrNull()
                ?.also { cache[sound] = it }
        }
    }

    private fun rawFor(sound: AmbientSound): Int? = when (sound) {
        AmbientSound.RAIN -> R.raw.ambient_rain
        AmbientSound.CAFE -> R.raw.ambient_cafe
        AmbientSound.WAVES -> R.raw.ambient_waves
        AmbientSound.CRICKETS -> R.raw.ambient_crickets
        AmbientSound.LIBRARY, AmbientSound.BROWN, AmbientSound.FIRE -> null
    }

    private fun decode(resId: Int): SamplePcm? {
        val ex = MediaExtractor()
        try {
            // .ogg is stored uncompressed in the APK, so it can be read straight from the file.
            app.resources.openRawResourceFd(resId).use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val out = PcmBuilder(max(rate, ((durationUs * rate / 1_000_000L + rate / 10) * channels).toInt()))
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
                var idleSinceEos = 0
                while (!outputDone) {
                    check(SystemClock.elapsedRealtime() < deadline) { "decode took too long" }
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o >= 0 -> {
                            idleSinceEos = 0
                            if (info.size > 0) {
                                val bytes = codec.getOutputBuffer(o)!!
                                bytes.position(info.offset); bytes.limit(info.offset + info.size)
                                out.append(bytes.slice().order(ByteOrder.nativeOrder()), encoding)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        }
                        // Some decoders drop the end-of-stream flag; once all input is in and nothing
                        // comes out for about a second, what we have is the whole file.
                        o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> if (++idleSinceEos >= 100) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (channels != 1 && channels != 2) return null
            if (rate !in 8_000..192_000) return null
            val end = PcmUtil.trimTrailingSilence(out.data, out.size, channels)
            if (end / channels < 2) return null
            return SamplePcm(if (end == out.data.size) out.data else out.data.copyOf(end), channels, rate)
        } finally {
            ex.release()
        }
    }

    private companion object {
        const val DECODE_TIMEOUT_MS = 30_000L
    }
}

/** A growable interleaved 16-bit buffer. */
internal class PcmBuilder(initial: Int) {
    var data = ShortArray(initial.coerceAtLeast(1)); private set
    var size = 0; private set

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(max(data.size + data.size / 2, size + extra))
    }

    /** Appends decoder output in [encoding] (16-bit or float PCM; anything else is rejected). */
    fun append(buf: ByteBuffer, encoding: Int) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buf.asShortBuffer()
                val n = shorts.remaining()
                ensure(n)
                shorts.get(data, size, n)
                size += n
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buf.asFloatBuffer()
                val n = floats.remaining()
                ensure(n)
                for (k in 0 until n) data[size + k] = PcmUtil.floatToPcm16(floats.get())
                size += n
            }
            else -> error("unsupported PCM encoding $encoding")
        }
    }
}

internal object PcmUtil {
    /** -1..1 float sample to 16-bit, clamped. */
    fun floatToPcm16(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    /**
     * Length (in samples, a whole number of frames) after dropping all-zero frames a decoder may leave
     * after the last real frame, so the loop wraps on real audio.
     */
    fun trimTrailingSilence(data: ShortArray, size: Int, channels: Int): Int {
        var end = size - size % channels
        while (end >= channels && (1..channels).all { data[end - it].toInt() == 0 }) end -= channels
        return end
    }
}
