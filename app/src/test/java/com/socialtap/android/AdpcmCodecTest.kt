package com.socialtap.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AdpcmCodecTest {

    private val frameSamples = 400 // 50 ms at 8 kHz, as in the server's relay_audio format

    private fun sine(freq: Double, rate: Int = 8000, amp: Double = 12000.0): ShortArray =
        ShortArray(frameSamples) { i -> Math.round(amp * sin(2 * Math.PI * freq * i / rate)).toInt().toShort() }

    @Test
    fun encodedFrameMatchesServerFrameSize() {
        val frame = AdpcmCodec.encode(sine(440.0))
        assertEquals(204, frame.size) // relay_audio.frame_bytes for 8 kHz / 400 samples
    }

    @Test
    fun headerBytesAreZero() {
        val frame = AdpcmCodec.encode(sine(300.0))
        for (i in 0 until 4) assertEquals(0, frame[i].toInt())
    }

    @Test
    fun roundTripTracksTheInputWaveform() {
        val input = sine(440.0)
        val decoded = AdpcmCodec.decode(AdpcmCodec.encode(input), frameSamples)
        assertNotNull(decoded)
        decoded!!
        var signal = 0.0
        var error = 0.0
        for (i in input.indices) {
            signal += input[i].toDouble() * input[i]
            val d = input[i].toDouble() - decoded[i]
            error += d * d
        }
        val snrDb = 10 * kotlin.math.log10(signal / error)
        // 4-bit ADPCM with a fresh state per 50 ms packet; the web relay measures the same ~16.9 dB.
        assertTrue("ADPCM SNR too low: $snrDb dB", snrDb > 15.0)
    }

    /**
     * Golden bytes produced by the deployed web encoder (voice-relay-worklet.js) for a 440 Hz,
     * amplitude-12000 sine at 8 kHz, 400 samples. Guarantees the Android codec is bit-compatible.
     */
    @Test
    fun matchesWebRelayEncoderBytes() {
        val frame = AdpcmCodec.encode(sine(440.0))
        val expectedHead = intArrayOf(0, 0, 0, 0, 112, 119, 119, 119, 244, 172, 170, 16)
        val expectedTail = intArrayOf(188, 154, 16, 52)
        for (i in expectedHead.indices) assertEquals("head byte $i", expectedHead[i], frame[i].toInt() and 0xFF)
        for (i in expectedTail.indices) {
            assertEquals("tail byte $i", expectedTail[i], frame[frame.size - 4 + i].toInt() and 0xFF)
        }
    }

    @Test
    fun silenceDecodesToNearSilence() {
        val decoded = AdpcmCodec.decode(AdpcmCodec.encode(ShortArray(frameSamples)), frameSamples)!!
        for (s in decoded) assertTrue(abs(s.toInt()) <= 8)
    }

    @Test
    fun wrongSizeFrameIsRejected() {
        assertNull(AdpcmCodec.decode(ByteArray(203), frameSamples))
        assertNull(AdpcmCodec.decode(ByteArray(205), frameSamples))
    }
}
