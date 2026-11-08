package io.github.vrivrixd.phonon

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/** The engine's settings, built from plain widgets so TalkBack reads them all. */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var column: LinearLayout
    private lateinit var batteryButton: Button
    private lateinit var updates: UpdateManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pad = 0
    private val poll = Runnable { refresh() }
    private val reconnect = Runnable {
        connect()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        heading(R.string.engine_status)
        status = text("")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        heading(R.string.default_voices)
        text(getString(R.string.default_voices_help))
        for (lang in Voices.languagesFor(Locale.getDefault().language)) voiceRow(lang)

        heading(R.string.speech)
        val boosts = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        choice(
            getString(R.string.speed_boost), boosts.map { "${fmt(it)}x" },
            boosts.indexOfFirst { it == Prefs.speedBoost(this) }.coerceAtLeast(0),
        ) { Prefs.setSpeedBoost(this, boosts[it]) }

        // 0 to 3 in steps of 0.2, the least expressive first. -1 stands for the model's own default
        // (0.8), which is also the default here.
        val temps = (0..15).map { if (it == 4) -1f else it / 5f }
        val tempLabels = (0..15).map {
            val value = String.format(Locale.getDefault(), "%.1f", it / 5f)
            when (it) {
                4 -> getString(R.string.temp_default, value)
                5 -> getString(R.string.temp_recommended, value)
                else -> value
            }
        }
        val savedTemp = Prefs.temperature(this)
        choice(
            getString(R.string.expressiveness), tempLabels,
            temps.indexOfFirst { it == savedTemp }.takeIf { it >= 0 } ?: temps.indexOf(-1f),
        ) { Prefs.setTemperature(this, temps[it]) }

        heading(R.string.performance)
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (1..minOf(8, cores)).toList()
        choice(
            getString(R.string.threads, cores), threads.map { it.toString() },
            threads.indexOf(Prefs.threads(this)).coerceAtLeast(0),
        ) { i ->
            if (threads[i] != Prefs.threads(this)) {
                Prefs.setThreads(this, threads[i])
                restartEngine()
            }
        }

        text(getString(R.string.battery_help))
        batteryButton = button("") { openBatterySettings() }

        heading(R.string.system)
        button(getString(R.string.open_tts_settings)) { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }

        heading(R.string.about)
        text(getString(R.string.about_text, packageManager.getPackageInfo(packageName, 0).versionName))

        connect()
        updates = UpdateManager(this)
        updates.checkOnStart()
    }

    // Binding to the engine also starts it, and with it the model's loading.
    private fun connect() {
        tts = TextToSpeech(this, { ttsReady = it == TextToSpeech.SUCCESS }, packageName)
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
        batteryButton.setText(if (batteryExempt()) R.string.battery_exempt else R.string.battery_disable)
        refresh()
    }

    override fun onDestroy() {
        updates.release()
        status.removeCallbacks(poll)
        status.removeCallbacks(reconnect)
        tts?.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        status.removeCallbacks(poll)
        val app = applicationContext
        // Off the main thread: this may have to wait for the engine's process to start.
        Thread {
            val reply = EngineProvider.call(app, EngineProvider.STATUS) ?: Bundle()
            runOnUiThread { if (!isDestroyed) show(reply) }
        }.start()
    }

    private fun show(reply: Bundle) {
        val state = reply.getString(EngineProvider.STATE)
        val message = when (state) {
            EngineProvider.EXTRACTING -> getString(R.string.status_extracting, reply.getString(EngineProvider.DETAIL))
            EngineProvider.FAILED -> getString(R.string.status_failed, reply.getString(EngineProvider.DETAIL))
            EngineProvider.READY -> getString(
                R.string.status_ready, fmt(reply.getDouble(EngineProvider.SECONDS).toFloat()),
                getString(if (reply.getBoolean(EngineProvider.OPTIMIZED)) R.string.library_fast else R.string.library_compat),
            )
            else -> getString(R.string.status_loading)
        }
        // Only on a change, so TalkBack does not repeat the live region.
        if (status.text.toString() != message) status.text = message
        if (state != EngineProvider.READY && state != EngineProvider.FAILED) status.postDelayed(poll, 700)
    }

    private fun batteryExempt() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    // The direct request is a yes or no from the system. Once exempt, only the system's list can undo it.
    private fun openBatterySettings() {
        val intent = if (batteryExempt()) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun restartEngine() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        status.removeCallbacks(poll)
        status.text = getString(R.string.status_loading)
        val app = applicationContext
        Thread { EngineProvider.call(app, EngineProvider.RESTART) }.start()
        status.removeCallbacks(reconnect)
        status.postDelayed(reconnect, 1000)
    }

    private fun voiceRow(lang: String) {
        val display = Locale.getDefault()
        val voices = Voices.forLanguage(this, lang)
        val current = Voices.defaultFor(this, lang, "")
        val labels = voices.map { v ->
            val region = if (v.character) getString(R.string.character_voice) else v.locale.getDisplayCountry(display)
            "${v.name} ($region)"
        }
        val langName = Locale(lang).getDisplayLanguage(display).replaceFirstChar { it.titlecase(display) }
        choice(langName, labels, voices.indexOf(current).coerceAtLeast(0)) { Prefs.setDefaultVoice(this, lang, voices[it].name) }
        button(getString(R.string.listen_default, langName)) { speakSample(lang) }
    }

    private fun speakSample(lang: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            status.text = getString(R.string.engine_starting)
            return
        }
        val voice = Voices.defaultFor(this, lang, "") ?: return
        val sample = DataActivity.SAMPLES[lang] ?: return
        engine.voices?.firstOrNull { it.name == voice.androidName }?.let { engine.voice = it }
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun heading(label: Int) = TextView(this).apply {
        setText(label)
        textSize = 20f
        setPadding(0, pad, 0, pad / 2)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
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
        val s = String.format(Locale.getDefault(), "%.2f", v)
        return s.trimEnd('0').trimEnd(',', '.')
    }
}
