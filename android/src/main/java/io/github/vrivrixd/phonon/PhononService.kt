package io.github.vrivrixd.phonon

import android.media.AudioFormat
import android.os.PowerManager
import android.os.Process
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
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val threads = EngineThreads()
    @Volatile private var lead: LeadBuffer? = null
    private val wakeLock by lazy {
        power
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonon:synthesis")
            .apply { setReferenceCounted(false) }
    }
    @Volatile private var current: Array<String> = arrayOf("eng", "", "")

    override fun onCreate() {
        super.onCreate()
        ForegroundKeeper.keep(this)
        Model.load(this)
    }

    // Android unbinds the engine when no app uses it any more, such as after another engine is
    // chosen: give the model's memory back then.
    override fun onDestroy() {
        Model.unload()
        super.onDestroy()
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

    override fun onGetVoices(): List<Voice> =
        Voices.defaultVoiceLocales(this).map { voice(Voices.defaultVoiceName(it), it) } +
            Voices.all(this).map { voice(it.androidName, it.locale) }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (Voices.byAndroidName(this, voiceName) != null || Voices.defaultVoiceLocale(this, voiceName) != null) TextToSpeech.SUCCESS
        else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val code = Voices.code(lang) ?: return null
        val locales = Voices.defaultVoiceLocales(this).filter { it.language == code }
        val c = Voices.country(country)
        return (locales.firstOrNull { it.country == c } ?: locales.firstOrNull())?.let { Voices.defaultVoiceName(it) }
    }

    override fun onStop() {
        stopped = true
        lead?.cancel()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // With the screen off the phone may sleep between pieces of audio and speech would stutter.
        // Held only while speech is being made, so it costs nothing when idle.
        wakeLock.acquire(WAKE_LOCK_LIMIT_MS)
        try {
            synthesize(request, callback)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        // A voice an app picked by name is honoured. Otherwise the settings decide, read now, so a
        // change there applies from the next utterance.
        val default = Voices.defaultVoiceLocale(this, request.voiceName)
        val requestLang = Voices.code(request.language) ?: default?.language
        val country = Voices.country(request.country).ifEmpty { default?.country.orEmpty() }
        val voice = Voices.byAndroidName(this, request.voiceName)
            ?.takeIf { requestLang == null || it.lang == requestLang }
            ?: requestLang?.let { Voices.defaultFor(this, it, country) }
            ?: Voices.defaultFor(this, Voices.FALLBACK_LANGUAGE, "")
        if (voice == null) {
            fail(callback, TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val text = Emoji.spell(this, request.charSequenceText?.toString().orEmpty(), voice.lang)
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
        val pipeline = AudioPipeline(speed, pitch)
        val buffer = LeadBuffer(LEAD_CAPACITY_BYTES)
        lead = buffer
        if (stopped) buffer.cancel()
        ForegroundKeeper.keep(this)
        threads.boost()

        // The model runs on its own thread, so it can work ahead of what Android will accept.
        var failure: Throwable? = null
        val maker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO)
            try {
                var ok = true
                synchronized(tts) {
                    tts.setLanguage(voice.lang)
                    tts.setVoice(voice.name)
                    val temperature = Prefs.temperature(this)
                    tts.setTemperature(if (temperature >= 0f) temperature else MODEL_TEMPERATURE)
                    var first = true
                    tts.speak(text) { pcm ->
                        // The runtime's workers exist from the first audio on.
                        if (first) threads.boost()
                        first = false
                        val bytes = pipeline.process(pcm)
                        ok = !stopped && (bytes.isEmpty() || buffer.put(bytes))
                        ok
                    }
                }
                if (ok && !stopped) {
                    val tail = pipeline.finish()
                    if (tail.isNotEmpty()) buffer.put(tail)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                buffer.finish()
            }
        }, "Phonon maker")
        maker.start()

        // Refused the foreground, the engine may be too slow to keep up with the screen off. Reading
        // has no one waiting for an instant answer, so begin with a little in hand.
        if (!ForegroundKeeper.active && !power.isInteractive) buffer.awaitLead(PREROLL_BYTES)

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

        while (true) {
            val chunk = buffer.take() ?: break
            if (!send(chunk)) {
                buffer.cancel()
                break
            }
        }
        maker.join()
        lead = null
        val problem = failure
        if (problem != null && !stopped) {
            Log.e(TAG, "synthesis failed", problem)
            fail(callback, TextToSpeech.ERROR_SYNTHESIS)
        } else {
            callback.done()
        }
    }

    private fun fail(callback: SynthesisCallback, code: Int) {
        callback.error(code)
        callback.done()
    }

    private fun voice(name: String, locale: Locale) = Voice(
        name, locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet(),
    )

    companion object {
        private const val TAG = "PhononService"
        private const val WAKE_LOCK_LIMIT_MS = 10 * 60 * 1000L
        private const val BYTES_PER_SECOND = AudioPipeline.SAMPLE_RATE * 2

        /** How far ahead of playback the model may get. */
        private const val LEAD_CAPACITY_BYTES = 20 * BYTES_PER_SECOND

        /** Audio to have in hand before speaking, when the engine may be too slow. */
        private const val PREROLL_BYTES = BYTES_PER_SECOND

        /** The model's own sampling temperature, from its config.json ("temp"). */
        const val MODEL_TEMPERATURE = 0.8f
    }
}
