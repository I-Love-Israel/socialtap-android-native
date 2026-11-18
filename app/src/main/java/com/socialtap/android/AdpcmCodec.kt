package com.socialtap.android

/**
 * Mono IMA ADPCM as used by SocialTap's voice relay (voice-relay-worklet.js).
 * Each packet is independent: 4 header bytes (zero) followed by 4-bit codes, low nibble first,
 * with the predictor and step index reset at the start of every packet.
 */
object AdpcmCodec {

    private val STEPS = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544,
        598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707,
        1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635,
        13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val INDEX_ADJUST = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    /** Encodes one packet of 16-bit PCM samples (frame_samples long) into frame_bytes = 4 + samples/2. */
    fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(4 + pcm.size / 2)
        var predictor = 0
        var index = 0
        for (i in pcm.indices) {
            val sample = pcm[i].toInt()
            val step = STEPS[index]
            var diff = sample - predictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            if (diff >= step) {
                code = code or 4
                diff -= step
            }
            if (diff >= (step shr 1)) {
                code = code or 2
                diff -= step shr 1
            }
            if (diff >= (step shr 2)) {
                code = code or 1
            }
            var delta = step shr 3
            if ((code and 4) != 0) delta += step
            if ((code and 2) != 0) delta += step shr 1
            if ((code and 1) != 0) delta += step shr 2
            predictor = clamp(predictor + if ((code and 8) != 0) -delta else delta, -32768, 32767)
            index = clamp(index + INDEX_ADJUST[code and 7], 0, 88)
            val pos = 4 + (i shr 1)
            out[pos] = (out[pos].toInt() or (code shl ((i and 1) * 4))).toByte()
        }
        return out
    }

    /** Decodes one packet; returns null when the frame has the wrong size. */
    fun decode(frame: ByteArray, frameSamples: Int): ShortArray? {
        if (frame.size != 4 + frameSamples / 2) return null
        val out = ShortArray(frameSamples)
        var predictor = 0
        var index = 0
        for (i in 0 until frameSamples) {
            val byte = frame[4 + (i shr 1)].toInt() and 0xFF
            val code = (byte shr ((i and 1) * 4)) and 0x0F
            val step = STEPS[index]
            var delta = step shr 3
            if ((code and 4) != 0) delta += step
            if ((code and 2) != 0) delta += step shr 1
            if ((code and 1) != 0) delta += step shr 2
            predictor = clamp(predictor + if ((code and 8) != 0) -delta else delta, -32768, 32767)
            index = clamp(index + INDEX_ADJUST[code and 7], 0, 88)
            out[i] = predictor.toShort()
        }
        return out
    }

    private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))
}
