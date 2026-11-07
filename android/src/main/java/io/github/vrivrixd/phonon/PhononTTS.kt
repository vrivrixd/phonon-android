// Kotlin wrapper over libptts_ffi.so (Gradium's Phonon runtime, gradium-ai/xn-ptts), through JNA.
// Adapted from xn-ptts/android/PhononTTS.kt (MIT OR Apache-2.0).
package io.github.vrivrixd.phonon

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File

/**
 * A loaded model. Every call blocks and must come from one thread at a time.
 *
 * @param modelDir a checkpoint folder: config.json, tokenizer.json, the weights and voices/.
 * @param lang how to normalize text: en, fr, de, es, pt or none.
 */
class PhononTTS(modelDir: String, lang: String) : AutoCloseable {
    private var handle: Pointer? =
        lib.ptts_new(modelDir, 0, lang) ?: throw IllegalStateException(lib.ptts_last_error(null))

    private var onAudio: (FloatArray) -> Boolean = { true }
    private var thrown: Throwable? = null

    // A field, so the callback outlives every native call that holds it.
    private val onFrame = Lib.FrameFn { pcm, n, _ ->
        try {
            onAudio(pcm.getFloatArray(0, n.toInt()))
        } catch (t: Throwable) {
            thrown = t
            false
        }
    }

    val voices: List<String>
        get() = names(lib.ptts_voices(open()))

    val languages: List<String>
        get() = names(lib.ptts_languages(open()))

    private fun names(block: Pointer): List<String> {
        val names = mutableListOf<String>()
        var offset = 0L
        while (true) {
            val name = block.getString(offset, "UTF-8")
            if (name.isEmpty()) return names
            names += name
            offset += name.toByteArray(Charsets.UTF_8).size + 1
        }
    }

    fun setVoice(name: String) = check(lib.ptts_set_voice(open(), name))
    fun setLanguage(lang: String) = check(lib.ptts_set_language(open(), lang))
    fun setTemperature(temperature: Float) = check(lib.ptts_set_temperature(open(), temperature))

    /** Speak [text], handing [onAudio] 24 kHz mono floats as they are made; false stops. */
    fun speak(text: String, onAudio: (FloatArray) -> Boolean) {
        this.onAudio = onAudio
        thrown = null
        val out = Result()
        val ok = lib.ptts_speak(open(), text, onFrame, null, out)
        thrown?.let {
            thrown = null
            throw it
        }
        check(ok)
    }

    override fun close() {
        handle?.let { lib.ptts_free(it) }
        handle = null
    }

    private fun open() = handle ?: throw IllegalStateException("PhononTTS is closed")

    private fun check(ok: Byte) {
        if (ok.toInt() == 0) throw IllegalStateException(lib.ptts_last_error(handle))
    }

    @Structure.FieldOrder("frames", "samples", "ttfaMs", "totalMs")
    class Result : Structure() {
        @JvmField var frames: Int = 0
        @JvmField var samples: Long = 0
        @JvmField var ttfaMs: Double = 0.0
        @JvmField var totalMs: Double = 0.0
    }

    /** ptts.h. A C bool return is one byte. */
    @Suppress("FunctionName")
    interface Lib : Library {
        fun ptts_new(dir: String, unit: Int, lang: String): Pointer?
        fun ptts_speak(h: Pointer, text: String, cb: FrameFn, user: Pointer?, out: Result): Byte
        fun ptts_voices(h: Pointer): Pointer
        fun ptts_set_voice(h: Pointer, name: String): Byte
        fun ptts_set_language(h: Pointer, lang: String): Byte
        fun ptts_languages(h: Pointer): Pointer
        fun ptts_set_temperature(h: Pointer, temperature: Float): Byte
        fun ptts_last_error(h: Pointer?): String?
        fun ptts_free(h: Pointer)

        fun interface FrameFn : Callback {
            fun invoke(pcm: Pointer, n: Long, user: Pointer?): Boolean
        }
    }

    companion object {
        /**
         * Which build of the library this phone runs: the fast one needs the ARMv8.2 dot-product
         * and half-precision instructions; the compatible one runs on any 64-bit ARM phone.
         */
        val variant: String by lazy {
            val features = try {
                File("/proc/cpuinfo").readLines()
                    .filter { it.startsWith("Features") }
                    .flatMap { it.substringAfter(':').trim().split(Regex("\s+")) }
                    .toSet()
            } catch (e: Exception) {
                emptySet()
            }
            if ("asimddp" in features && "fphp" in features && "asimdhp" in features) "ptts_ffi" else "ptts_ffi_compat"
        }

        private val lib: Lib by lazy { Native.load(variant, Lib::class.java) }
    }
}
