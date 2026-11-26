package dev.cambraia.openherdr.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure helpers for recorded takes: 16-bit little-endian mono PCM in, recognizer-sized pieces out. */
object AudioSegments {
    /**
     * Cuts [samples] into pieces of about [maxSeconds] so a long take never goes through the
     * recognizer in one pass. Each cut lands in the quietest fifth of a second of the piece's last
     * [searchSeconds], which is a pause between words whenever there is one. The pieces cover every
     * sample once, in order; the last one is at least a second long.
     */
    fun split(samples: FloatArray, rate: Int, maxSeconds: Int = 30, searchSeconds: Int = 12): List<IntRange> {
        val max = maxSeconds * rate
        val window = rate / 5
        val pieces = ArrayList<IntRange>()
        var start = 0
        while (samples.size - start > max + rate) {
            val limit = start + max
            var best = limit
            var quietest = Double.MAX_VALUE
            var at = limit - searchSeconds * rate
            while (at + window <= limit) {
                var energy = 0.0
                for (i in at until at + window) energy += samples[i] * samples[i]
                if (energy < quietest) {
                    quietest = energy
                    best = at + window / 2
                }
                at += window / 2
            }
            pieces += start until best
            start = best
        }
        if (start < samples.size) pieces += start until samples.size
        return pieces
    }

    fun floats(pcm: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(pcm, 0, pcm.size and 1.inv()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    /** The 44-byte header that turns [pcmBytes] of mono 16-bit PCM into a WAV file. */
    fun wavHeader(pcmBytes: Long, rate: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + pcmBytes).toInt())
            put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcmBytes.toInt())
        }.array()
}
