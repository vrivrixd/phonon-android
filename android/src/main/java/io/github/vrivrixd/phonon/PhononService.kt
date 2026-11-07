package io.github.vrivrixd.phonon

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Phonon as a system text-to-speech engine. Android calls [onSynthesizeText] on one thread, one
 * request at a time, and [onStop] from another to cut the current one short.
 *
 * Everything about languages and voices is answered from assets/voices.json, so TalkBack and the
 * system settings never wait for the model; only synthesis does.
 */
class PhononService : TextToSpeechService() {
    @Volatile private var stopped = false
    @Volatile private var current: Array<String> = arrayOf("por", "BRA", "")

    override fun onCreate() {
        super.onCreate()
        Model.load(this)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val code = Voices.code(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val c = Voices.country(country)
        return if (c.isNotEmpty() && c in Voices.countries(this, code)) TextToSpeech.LANG_COUNTRY_AVAILABLE
        else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            val code = Voices.code(lang)!!
            val loc = Locale(code, if (result == TextToSpeech.LANG_COUNTRY_AVAILABLE) Voices.country(country) else "")
            current = arrayOf(loc.isO3Language, if (loc.country.isEmpty()) "" else loc.isO3Country, "")
        }
        return result
    }

    override fun onGetLanguage(): Array<String> = current

    override fun onGetVoices(): List<Voice> = Voices.all(this).map { voice(it) }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (Voices.byAndroidName(this, voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val code = Voices.code(lang) ?: return null
        return Voices.defaultFor(this, code, Voices.country(country))?.androidName
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        // The voice the user picked for the request's language wins over the one the app asked
        // for only when the app asked for none; an explicit voice is honoured.
        val requestLang = Voices.code(request.language)
        val voice = Voices.byAndroidName(this, request.voiceName)
            ?.takeIf { requestLang == null || it.lang == requestLang || request.language.isNullOrEmpty() }
            ?: requestLang?.let { Voices.defaultFor(this, it, Voices.country(request.country)) }
            ?: Voices.defaultFor(this, "pt", "BR")
        if (voice == null) {
            fail(callback, TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val text = request.charSequenceText?.toString().orEmpty()
        if (callback.start(AudioPipeline.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        if (text.isBlank()) {
            callback.done()
            return
        }
        val tts = Model.await(this)
        if (tts == null) {
            fail(callback, TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        if (stopped) {
            callback.done()
            return
        }
        // Android's 100 is normal for both; TalkBack's rate slider lands in speechRate.
        val speed = (request.speechRate / 100f).coerceIn(0.2f, 6f) * Prefs.speedBoost(this)
        val pitch = (request.pitch / 100f).coerceIn(0.25f, 4f)
        val pipeline = AudioPipeline(speed, pitch, Prefs.trimSilence(this))
        val max = callback.maxBufferSize

        fun send(bytes: ByteArray): Boolean {
            var at = 0
            while (at < bytes.size) {
                if (stopped) return false
                val n = minOf(max, bytes.size - at)
                if (callback.audioAvailable(bytes, at, n) != TextToSpeech.SUCCESS) return false
                at += n
            }
            return true
        }

        try {
            var ok = true
            synchronized(tts) {
                tts.setLanguage(voice.lang)
                tts.setVoice(voice.name)
                val temperature = Prefs.temperature(this)
                tts.setTemperature(if (temperature >= 0f) temperature else MODEL_TEMPERATURE)
                tts.speak(text) { pcm ->
                    ok = !stopped && send(pipeline.process(pcm))
                    ok
                }
            }
            if (ok && !stopped) send(pipeline.finish())
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            fail(callback, TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    private fun fail(callback: SynthesisCallback, code: Int) {
        callback.error(code)
        callback.done()
    }

    private fun voice(v: CatalogVoice) = Voice(
        v.androidName, v.locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet(),
    )

    companion object {
        private const val TAG = "PhononService"

        /** The model's own sampling temperature, from its config.json ("temp"). */
        const val MODEL_TEMPERATURE = 0.8f
    }
}
