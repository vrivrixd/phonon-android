package io.github.vrivrixd.phonon

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/** The engine's settings, in Portuguese, built from plain widgets so TalkBack reads them all. */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var column: LinearLayout
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pad = 0
    private val ptBR = Locale("pt", "BR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        heading("Estado do motor")
        status = text("")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        heading("Voz padrão de cada idioma")
        text("Usada quando o aplicativo (por exemplo, o TalkBack) não escolhe uma voz específica.")
        for (lang in Voices.LANGUAGES) voiceRow(lang)

        heading("Fala")
        val boosts = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        choice(
            "Velocidade extra (multiplica a velocidade do sistema)", boosts.map { "${fmt(it)}x" },
            boosts.indexOfFirst { it == Prefs.speedBoost(this) }.coerceAtLeast(0),
        ) { Prefs.setSpeedBoost(this, boosts[it]) }

        val temps = listOf(-1f, 0.6f, 0.4f, 0.2f, 0f)
        val tempLabels = listOf("Padrão do modelo (mais expressiva)", "Moderada", "Estável", "Muito estável", "Sem variação")
        choice(
            "Variação da voz", tempLabels,
            temps.indexOfFirst { it == Prefs.temperature(this) }.coerceAtLeast(0),
        ) { Prefs.setTemperature(this, temps[it]) }

        CheckBox(this).apply {
            text = "Cortar o silêncio antes da fala (resposta mais rápida)"
            isChecked = Prefs.trimSilence(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setTrimSilence(this@SettingsActivity, on) }
            column.addView(this, MATCH_PARENT, WRAP_CONTENT)
        }

        heading("Desempenho")
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (1..minOf(8, cores)).toList()
        choice(
            "Núcleos do processador usados (o aparelho tem $cores)", threads.map { it.toString() },
            threads.indexOf(Prefs.threads(this)).coerceAtLeast(0),
        ) { i ->
            val value = threads[i]
            if (value != Prefs.threads(this)) {
                Prefs.setThreads(this, value)
                AlertDialog.Builder(this)
                    .setMessage("O motor precisa reiniciar para usar $value núcleos. A fala pode demorar alguns segundos para voltar.")
                    .setPositiveButton("Reiniciar agora") { _, _ -> Process.killProcess(Process.myPid()) }
                    .setNegativeButton("Depois", null)
                    .show()
            }
        }

        heading("Sistema")
        button("Abrir configurações de voz do Android") { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }

        heading("Sobre")
        text(
            "Phonon para Android, versão ${packageManager.getPackageInfo(packageName, 0).versionName}. " +
                "Motor Phonon da Gradium (gradium-ai/xn-ptts, MIT ou Apache 2.0), baseado no Pocket TTS e no Mimi da Kyutai. " +
                "Modelo phonon-40m-multilingual da Gradium, licença CC BY-NC 4.0, somente uso não comercial. " +
                "Vozes do catálogo gradium/phonon-voices, Apache 2.0. Velocidade e tom pelo Sonic, de Bill Cox, Apache 2.0. " +
                "Projeto independente, não oficial da Gradium. Toda a fala é gerada no aparelho, sem internet.",
        )

        Model.load(this)
        tts = TextToSpeech(this, { ttsReady = it == TextToSpeech.SUCCESS }, packageName)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        status.text = when (val s = Model.state) {
            is Model.State.Idle, is Model.State.Loading -> "Carregando o modelo de voz..."
            is Model.State.Extracting -> "Preparando o modelo no primeiro uso: ${s.file}"
            is Model.State.Failed -> "Erro ao carregar o modelo: ${s.message}"
            is Model.State.Ready -> "Pronto. Modelo carregado em ${fmt(s.seconds.toFloat())} segundos. " +
                "Biblioteca: ${if (PhononTTS.variant == "ptts_ffi") "otimizada (ARMv8.2)" else "compatível"}."
        }
        if (Model.state !is Model.State.Ready && Model.state !is Model.State.Failed) status.postDelayed(::refresh, 700)
    }

    private fun voiceRow(lang: String) {
        val voices = Voices.forLanguage(this, lang)
        val current = Voices.defaultFor(this, lang, "")
        val labels = voices.map { v ->
            val region = if (v.character) "personagem" else v.locale.getDisplayCountry(ptBR)
            "${v.name} ($region)"
        }
        val langName = Locale(lang).getDisplayLanguage(ptBR).replaceFirstChar { it.titlecase(ptBR) }
        choice(langName, labels, voices.indexOf(current).coerceAtLeast(0)) { Prefs.setDefaultVoice(this, lang, voices[it].name) }
        button("Ouvir voz padrão: $langName") { speakSample(lang) }
    }

    private fun speakSample(lang: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            status.text = "O motor ainda está iniciando. Tente de novo em alguns segundos."
            return
        }
        val voice = Voices.defaultFor(this, lang, "") ?: return
        val sample = DataActivity.SAMPLES[lang] ?: return
        engine.voices?.firstOrNull { it.name == voice.androidName }?.let { engine.voice = it }
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun heading(label: String) = TextView(this).apply {
        text = label
        textSize = 20f
        setPadding(0, pad, 0, pad / 2)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        column.addView(this, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun text(label: String) = TextView(this).apply {
        text = label
        setPadding(0, 0, 0, pad / 2)
        column.addView(this, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        column.addView(this, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun choice(label: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) {
        val title = TextView(this).apply { text = label; setPadding(0, pad / 2, 0, 0) }
        val spinner = Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange(position)
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        title.labelFor = spinner.id
        column.addView(title, MATCH_PARENT, WRAP_CONTENT)
        column.addView(spinner, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun fmt(v: Float): String {
        val s = String.format(ptBR, "%.2f", v)
        return s.trimEnd('0').trimEnd(',')
    }
}
