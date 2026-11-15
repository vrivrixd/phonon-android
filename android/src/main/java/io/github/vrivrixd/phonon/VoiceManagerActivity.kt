package io.github.vrivrixd.phonon

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * "Extra voices": a screen of its own, with Navigate up in the action bar as on other Android
 * screens, then the voices the person added (a tap plays one, a long press offers rename and
 * delete), then a button to add one from a file.
 */
class VoiceManagerActivity : Activity() {
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var empty: TextView
    private var voices: List<CatalogVoice> = emptyList()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastReason: ImportedVoices.Reason? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        empty = TextView(this).apply {
            setText(R.string.no_extra_voices)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> voices.getOrNull(position)?.let { speak(it) } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            voices.getOrNull(position)?.let { options(it) }
            true
        }
        val add = Button(this).apply {
            setText(R.string.add_voice)
            setOnClickListener { pickFile() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(empty, MATCH_PARENT, WRAP_CONTENT)
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(add, MATCH_PARENT, WRAP_CONTENT)
        })
        tts = TextToSpeech(this, { ttsReady = it == TextToSpeech.SUCCESS }, packageName)
        refresh()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        askName(fileName(uri)) { name -> askLocale { locale -> import(uri, name, locale) } }
    }

    private fun refresh() {
        voices = ImportedVoices.list(this).sortedBy { it.name.lowercase(Locale.getDefault()) }
        val display = Locale.getDefault()
        adapter.clear()
        adapter.addAll(voices.map { "${it.name}, ${it.locale.getDisplayName(display)}" })
        empty.visibility = if (voices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun speak(voice: CatalogVoice) {
        val engine = tts
        if (engine == null || !ttsReady) {
            Toast.makeText(this, R.string.engine_starting, Toast.LENGTH_SHORT).show()
            return
        }
        val sample = DataActivity.SAMPLES[voice.lang] ?: return
        engine.voices?.firstOrNull { it.name == voice.androidName }?.let { engine.voice = it }
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun pickFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(pick, REQUEST_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.invalid_voice, Toast.LENGTH_LONG).show()
        }
    }

    private fun options(voice: CatalogVoice) {
        AlertDialog.Builder(this)
            .setTitle(voice.name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) rename(voice) else delete(voice)
            }
            .show()
    }

    private fun rename(voice: CatalogVoice) {
        askName(voice.name) { name ->
            if (attempt { ImportedVoices.rename(this, voice.id, name) }) refresh() else rename(voice)
        }
    }

    private fun delete(voice: CatalogVoice) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, voice.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                ImportedVoices.remove(this, voice.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun import(uri: Uri, name: String, locale: Locale) {
        val ok = attempt {
            val input = contentResolver.openInputStream(uri) ?: throw ImportedVoices.ImportException(ImportedVoices.Reason.NOT_A_VOICE)
            input.use { ImportedVoices.add(this, it, name, locale) }
        }
        if (ok) {
            refresh()
        } else if (lastReason == ImportedVoices.Reason.NAME_TAKEN || lastReason == ImportedVoices.Reason.NAME_INVALID) {
            // Only the name was wrong: ask again rather than make them pick the file again.
            askName(name) { again -> import(uri, again, locale) }
        }
    }

    /** Runs [action], explaining a refusal. True if it went through. */
    private fun attempt(action: () -> Unit): Boolean {
        lastReason = null
        return try {
            action()
            true
        } catch (e: ImportedVoices.ImportException) {
            lastReason = e.reason
            val message = when (e.reason) {
                ImportedVoices.Reason.NOT_A_VOICE -> R.string.invalid_voice
                ImportedVoices.Reason.NAME_INVALID -> R.string.name_invalid
                ImportedVoices.Reason.NAME_TAKEN -> R.string.name_taken
                ImportedVoices.Reason.TOO_MANY -> R.string.too_many_voices
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            lastReason = ImportedVoices.Reason.NOT_A_VOICE
            Toast.makeText(this, R.string.invalid_voice, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun askName(initial: String, then: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            contentDescription = getString(R.string.voice_name)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field, MATCH_PARENT, WRAP_CONTENT)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_name)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> then(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        field.requestFocus()
    }

    private fun askLocale(then: (Locale) -> Unit) {
        val display = Locale.getDefault()
        val locales = Voices.catalogLocales(this)
        val preferred = locales.indexOfFirst { it == Locale(display.language, display.country) }
            .takeIf { it >= 0 } ?: locales.indexOfFirst { it.language == display.language }.coerceAtLeast(0)
        var chosen = preferred
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_language)
            .setSingleChoiceItems(locales.map { it.getDisplayName(display) }.toTypedArray(), preferred) { _, which -> chosen = which }
            .setPositiveButton(android.R.string.ok) { _, _ -> then(locales[chosen]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fileName(uri: Uri): String {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: ""
        return name.substringBeforeLast('.').take(ImportedVoices.MAX_NAME)
    }

    private companion object {
        const val REQUEST_FILE = 41
    }
}
