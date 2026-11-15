package io.github.vrivrixd.phonon

import sonic.Sonic
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What happens to the model's audio before Android gets it: the decoder's start-up click silenced,
 * the leading silence dropped, a short fade-in, then
 * speed and pitch through Sonic (Bill Cox, Apache-2.0), then 16-bit little-endian PCM.
 */
class AudioPipeline(speed: Float, pitch: Float, trim: Boolean = true) {
    private val sonic: Sonic? = if (abs(speed - 1f) < 0.01f && abs(pitch - 1f) < 0.01f) null else
        Sonic(SAMPLE_RATE, 1).also {
            // Not apply: inside it, "speed" would mean Sonic's own getSpeed(), and nothing changes.
            it.setSpeed(speed)
            it.setPitch(pitch)
        }

    private var trimming = trim
    private var trimmed = 0
    // A little audio kept before the onset, so the first sound is not clipped.
    private val preRoll = FloatArray(PRE_ROLL)
    private var preRollFill = 0
    private var out = FloatArray(0)
    private var faded = 0
    private var position = 0

    /** Process one piece of the model's audio. May return an empty array. */
    fun process(model: FloatArray): ByteArray {
        // Every utterance opens with a short burst from the audio decoder starting up, before any
        // speech: a click about 40 dB down, gone within 10 ms. Silenced here, it is also no longer
        // mistaken for the start of speech, so the silence after it is trimmed too.
        var pcm = model
        if (position < DECODER_CLICK) {
            pcm = model.copyOf()
            for (i in 0 until minOf(pcm.size, DECODER_CLICK - position)) pcm[i] = 0f
        }
        position += model.size
        var input = pcm
        if (trimming) {
            val onset = pcm.indexOfFirst { abs(it) > THRESHOLD }
            if (onset < 0 && trimmed + pcm.size < MAX_TRIM) {
                trimmed += pcm.size
                remember(pcm)
                return ByteArray(0)
            }
            trimming = false
            val start = if (onset < 0) 0 else onset
            val preStart = maxOf(0, start - PRE_ROLL)
            val fromHistory = minOf(preRollFill, PRE_ROLL - (start - preStart))
            input = preRoll.copyOfRange(preRollFill - fromHistory, preRollFill) + pcm.copyOfRange(preStart, pcm.size)
        }
        if (faded < FADE_IN) input = fadeIn(input)
        return run(input)
    }

    /**
     * Ramp the first samples up from silence. Audio that starts on a sample far from zero, as it
     * does once leading silence is cut, is heard as a click.
     */
    private fun fadeIn(pcm: FloatArray): FloatArray {
        val result = pcm.copyOf()
        var i = 0
        while (i < result.size && faded < FADE_IN) {
            val x = faded.toFloat() / FADE_IN
            result[i] *= x * x * (3 - 2 * x)
            i++
            faded++
        }
        return result
    }

    /** What Sonic still holds at the end of the utterance. */
    fun finish(): ByteArray {
        val s = sonic ?: return ByteArray(0)
        s.flushStream()
        return drain(s)
    }

    private fun remember(pcm: FloatArray) {
        // Keep only the tail of the silence, enough for the pre-roll.
        val take = minOf(pcm.size, PRE_ROLL)
        if (preRollFill + take > PRE_ROLL) {
            val drop = preRollFill + take - PRE_ROLL
            System.arraycopy(preRoll, drop, preRoll, 0, preRollFill - drop)
            preRollFill -= drop
        }
        System.arraycopy(pcm, pcm.size - take, preRoll, preRollFill, take)
        preRollFill += take
    }

    private fun run(input: FloatArray): ByteArray {
        val s = sonic ?: return pcm16(input, input.size)
        // Sonic casts to 16 bits without clamping, so a sample past full scale would wrap around.
        val clamped = FloatArray(input.size) { input[it].coerceIn(-1f, 1f) }
        s.writeFloatToStream(clamped, clamped.size)
        return drain(s)
    }

    private fun drain(s: Sonic): ByteArray {
        val available = s.samplesAvailable()
        if (available <= 0) return ByteArray(0)
        if (out.size < available) out = FloatArray(available)
        val n = s.readFloatFromStream(out, available)
        return pcm16(out, n)
    }

    companion object {
        const val SAMPLE_RATE = 24000
        private const val THRESHOLD = 0.004f
        private const val PRE_ROLL = SAMPLE_RATE / 50 // 20 ms
        private const val MAX_TRIM = SAMPLE_RATE // never drop more than 1 s
        private const val FADE_IN = SAMPLE_RATE / 200 // 5 ms
        private const val DECODER_CLICK = SAMPLE_RATE / 50 // 20 ms

        fun pcm16(pcm: FloatArray, n: Int): ByteArray {
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) {
                val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                bytes[2 * i] = s.toByte()
                bytes[2 * i + 1] = (s shr 8).toByte()
            }
            return bytes
        }
    }
}
