package io.github.vrivrixd.phonon

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The two questions the system settings ask an engine without binding to it: which languages
 * are installed (all of them: the model ships in the APK), and what to say for "Play".
 */
class DataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val available = ArrayList<String>()
                for (lang in Voices.LANGUAGES) {
                    val iso3 = Locale(lang).isO3Language
                    available += iso3
                    for (country in Voices.countries(this, lang)) available += "$iso3-${Locale("", country).isO3Country}"
                }
                val result = Intent()
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
                setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                val sample = SAMPLES[Voices.code(intent.getStringExtra("language"))]
                if (sample == null) {
                    setResult(TextToSpeech.LANG_NOT_SUPPORTED)
                } else {
                    setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample))
                }
            }
        }
        finish()
    }

    companion object {
        val SAMPLES = mapOf(
            "pt" to "Olá! Esta é a voz do Phonon, funcionando direto no seu Android.",
            "en" to "Hello! This is Phonon, speaking right on your Android phone.",
            "es" to "¡Hola! Esta es la voz de Phonon, funcionando en tu Android.",
            "fr" to "Bonjour ! Voici la voix de Phonon, directement sur votre Android.",
            "de" to "Hallo! Das ist Phonon, direkt auf deinem Android-Telefon.",
        )
    }
}
