package com.wifiauxbridge.audio

/**
 * IMA ADPCM codec (standard "DVI-4" variant), ~4:1 compression, pure Kotlin.
 * Used instead of Opus because Android does not guarantee an on-device Opus encoder.
 */
object Adpcm {

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
        3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    private class ChannelState {
        var predictor = 0
        var index = 0
    }

    class Encoder(channels: Int) {
        private val states = Array(channels) { ChannelState() }

        /** Encodes [sampleCount] interleaved 16-bit samples into packed 4-bit codes. */
        fun encode(pcm: ShortArray, sampleCount: Int, channels: Int): ByteArray {
            val out = ByteArray((sampleCount + 1) / 2)
            var nibbleHigh = false
            var outIndex = 0
            var pending = 0
            for (i in 0 until sampleCount) {
                val ch = i % channels
                val code = encodeSample(pcm[i].toInt(), states[ch])
                if (!nibbleHigh) {
                    pending = code and 0x0F
                    nibbleHigh = true
                } else {
                    out[outIndex] = (pending or ((code and 0x0F) shl 4)).toByte()
                    outIndex++
                    nibbleHigh = false
                }
            }
            if (nibbleHigh) {
                out[outIndex] = pending.toByte()
                outIndex++
            }
            return if (outIndex == out.size) out else out.copyOf(outIndex)
        }

        private fun encodeSample(sample: Int, state: ChannelState): Int {
            var predictor = state.predictor
            var index = state.index
            val step = STEP_TABLE[index]

            var diff = sample - predictor
            var sign = 0
            if (diff < 0) {
                sign = 8
                diff = -diff
            }

            var delta = 0
            var tempStep = step
            var diffq = step shr 3
            if (diff >= tempStep) {
                delta = 4
                diff -= tempStep
                diffq += step
            }
            tempStep = tempStep shr 1
            if (diff >= tempStep) {
                delta = delta or 2
                diff -= tempStep
                diffq += tempStep
            }
            tempStep = tempStep shr 1
            if (diff >= tempStep) {
                delta = delta or 1
                diffq += tempStep
            }

            predictor = if (sign != 0) predictor - diffq else predictor + diffq
            predictor = predictor.coerceIn(-32768, 32767)

            index += INDEX_TABLE[delta or sign]
            index = index.coerceIn(0, STEP_TABLE.size - 1)

            state.predictor = predictor
            state.index = index
            return delta or sign
        }
    }

    class Decoder(channels: Int) {
        private val states = Array(channels) { ChannelState() }

        /** Decodes [data] into [sampleCount] interleaved 16-bit PCM samples. */
        fun decode(data: ByteArray, dataLength: Int, sampleCount: Int, channels: Int): ShortArray {
            val out = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                val byteIndex = i / 2
                if (byteIndex >= dataLength) break // truncated input: leave the rest silent
                val b = data[byteIndex].toInt() and 0xFF
                val code = if (i % 2 == 0) b and 0x0F else (b shr 4) and 0x0F
                out[i] = decodeSample(code, states[i % channels]).toShort()
            }
            return out
        }

        private fun decodeSample(code: Int, state: ChannelState): Int {
            val index = state.index
            val step = STEP_TABLE[index]

            var diffq = step shr 3
            if (code and 4 != 0) diffq += step
            if (code and 2 != 0) diffq += step shr 1
            if (code and 1 != 0) diffq += step shr 2

            var predictor = state.predictor
            predictor = if (code and 8 != 0) predictor - diffq else predictor + diffq
            predictor = predictor.coerceIn(-32768, 32767)

            var newIndex = index + INDEX_TABLE[code and 0x0F]
            newIndex = newIndex.coerceIn(0, STEP_TABLE.size - 1)

            state.index = newIndex
            state.predictor = predictor
            return predictor
        }
    }
}
