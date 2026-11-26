package dev.cambraia.openherdr.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSegmentsTest {
    private val rate = 100

    /** Loud everywhere except the given sample ranges. */
    private fun speech(seconds: Int, vararg quiet: IntRange) =
        FloatArray(seconds * rate) { i -> if (quiet.any { i in it }) 0f else 0.5f }

    @Test
    fun shortTakeIsOnePiece() {
        assertEquals(listOf(0 until 3000), AudioSegments.split(speech(30), rate))
        assertEquals(emptyList<IntRange>(), AudioSegments.split(FloatArray(0), rate))
    }

    @Test
    fun cutsInThePauseAndCoversEverySampleOnce() {
        val samples = speech(70, 2500 until 2560, 5200 until 5260)
        val pieces = AudioSegments.split(samples, rate)
        assertEquals(3, pieces.size)
        assertTrue("first cut in the first pause", pieces[0].last + 1 in 2500..2560)
        assertTrue("second cut in the second pause", pieces[1].last + 1 in 5200..5260)
        assertEquals(0, pieces.first().first)
        assertEquals(samples.size - 1, pieces.last().last)
        pieces.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        assertTrue(pieces.all { it.count() <= 31 * rate })
    }

    @Test
    fun speechWithoutPausesIsStillCutBeforeTheLimit() {
        val pieces = AudioSegments.split(speech(600), rate)
        assertTrue(pieces.size >= 20)
        assertTrue(pieces.all { it.count() in rate..31 * rate })
        assertEquals(600 * rate, pieces.sumOf { it.count() })
    }

    @Test
    fun pcmBecomesFloatsAndAWavHeader() {
        val pcm = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(16384).putShort(-32768).array()
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), AudioSegments.floats(pcm), 0f)
        // A killed process can leave half a sample behind.
        assertEquals(2, AudioSegments.floats(pcm.copyOf(5)).size)

        val header = ByteBuffer.wrap(AudioSegments.wavHeader(32_000, 16_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header.array(), 0, 4))
        assertEquals(36 + 32_000, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(32_000, header.getInt(40))
    }
}
