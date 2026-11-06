package com.flowstate.focus.service.ambient

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.flowstate.focus.R
import com.flowstate.focus.domain.AmbientSound
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Decodes the recorded loops in `res/raw` (48 kHz Ogg Vorbis, see NOTICE.md) to 16-bit PCM with
 * MediaExtractor + MediaCodec. Each file is decoded once, the first time its sound is turned up,
 * and cached for the life of the process (about 2 to 10 MB each). Called off the audio thread by
 * [SampleVoice].
 */
class RawSampleLoader(context: Context) : SampleLoader {
    private val app = context.applicationContext
    private val cache = ConcurrentHashMap<AmbientSound, SamplePcm>()

    override fun load(sound: AmbientSound): SamplePcm? {
        cache[sound]?.let { return it }
        val res = rawFor(sound) ?: return null
        return synchronized(this) {
            cache[sound] ?: decode(res).also { cache[sound] = it }
        }
    }

    private fun rawFor(sound: AmbientSound): Int? = when (sound) {
        AmbientSound.RAIN -> R.raw.ambient_rain
        AmbientSound.CAFE -> R.raw.ambient_cafe
        AmbientSound.WAVES -> R.raw.ambient_waves
        AmbientSound.CRICKETS -> R.raw.ambient_crickets
        AmbientSound.LIBRARY, AmbientSound.BROWN, AmbientSound.FIRE -> null
    }

    private fun decode(resId: Int): SamplePcm {
        val ex = MediaExtractor()
        try {
            // .ogg is stored uncompressed in the APK, so it can be read straight from the file.
            app.resources.openRawResourceFd(resId).use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // Room for the stated duration plus a little; grows if the estimate is short.
            var out = ShortArray(max(rate, ((durationUs * rate / 1_000_000L + rate / 10) * channels).toInt()))
            var size = 0
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
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
                    if (o >= 0) {
                        if (info.size > 0) {
                            val bytes = codec.getOutputBuffer(o)!!
                            bytes.position(info.offset); bytes.limit(info.offset + info.size)
                            val shorts = bytes.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            if (size + count > out.size) out = out.copyOf(max(out.size + out.size / 2, size + count))
                            shorts.get(out, size, count)
                            size += count
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            check(f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "decoder is not giving 16-bit PCM" }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            check(channels == 1 || channels == 2) { "$channels channels" }
            // Drop any all-zero padding a decoder might leave after the last frame, so the loop
            // wraps on real audio.
            var end = size - size % channels
            while (end >= channels && (1..channels).all { out[end - it].toInt() == 0 }) end -= channels
            return SamplePcm(if (end == out.size) out else out.copyOf(end), channels, rate)
        } finally {
            ex.release()
        }
    }
}
