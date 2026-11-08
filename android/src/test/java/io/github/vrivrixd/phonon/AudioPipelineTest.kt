package io.github.vrivrixd.phonon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioPipelineTest {
    private val seconds = 2

    // A 200 Hz tone, cut into pieces the way the model delivers audio.
    private fun render(speed: Float, pitch: Float): ShortArray {
        val pipeline = AudioPipeline(speed, pitch, false)
        val tone = FloatArray(AudioPipeline.SAMPLE_RATE * seconds) {
            0.5f * sin(2 * PI * 200 * it / AudioPipeline.SAMPLE_RATE).toFloat()
        }
        val bytes = ArrayList<ByteArray>()
        for (start in tone.indices step 1920) {
            bytes += pipeline.process(tone.copyOfRange(start, minOf(start + 1920, tone.size)))
        }
        bytes += pipeline.finish()
        val all = bytes.reduce { a, b -> a + b }
        return ShortArray(all.size / 2) {
            ((all[2 * it].toInt() and 0xff) or (all[2 * it + 1].toInt() shl 8)).toShort()
        }
    }

    private fun duration(pcm: ShortArray) = pcm.size.toDouble() / AudioPipeline.SAMPLE_RATE

    private fun frequency(pcm: ShortArray): Double {
        val rises = (1 until pcm.size).count { pcm[it - 1] < 0 && pcm[it] >= 0 }
        return rises / duration(pcm)
    }

    @Test fun normalSpeedKeepsTheAudio() {
        assertEquals(seconds.toDouble(), duration(render(1f, 1f)), 0.01)
    }

    @Test fun speedChangesTheDurationButNotThePitch() {
        val fast = render(2f, 1f)
        assertEquals(seconds / 2.0, duration(fast), 0.1)
        assertEquals(200.0, frequency(fast), 15.0)
        assertEquals(seconds * 2.0, duration(render(0.5f, 1f)), 0.2)
    }

    @Test fun pitchChangesThePitchButNotTheDuration() {
        val high = render(1f, 1.5f)
        assertEquals(seconds.toDouble(), duration(high), 0.1)
        assertTrue("frequency was ${frequency(high)}", frequency(high) in 270.0..330.0)
    }
}
