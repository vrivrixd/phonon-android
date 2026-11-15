package io.github.vrivrixd.phonon

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
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
 * The "Manage extra voices" window: navigate up first, then the voices the person added (a tap
 * plays one, a long press offers rename and delete), then a button to add one from a file.
 *
 * @param preview speaks a sample in the voice given.
 * @param changed called after any change, so the screen behind can update its voice lists.
 */
class VoiceManager(
    private val activity: Activity,
    private val preview: (CatalogVoice) -> Unit,
    private val changed: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var adapter: ArrayAdapter<String>? = null
    private var empty: TextView? = null
    private var voices: List<CatalogVoice> = emptyList()

    fun show() {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val up = Button(activity).apply { setText(R.string.navigate_up) }
        val emptyText = TextView(activity).apply {
            setText(R.string.no_extra_voices)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val list = ListView(activity)
        val add = Button(activity).apply { setText(R.string.add_voice) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(up, MATCH_PARENT, WRAP_CONTENT)
            addView(emptyText, MATCH_PARENT, WRAP_CONTENT)
            addView(list, MATCH_PARENT, WRAP_CONTENT)
            addView(add, MATCH_PARENT, WRAP_CONTENT)
        }
        val items = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        list.adapter = items
        adapter = items
        empty = emptyText
        list.setOnItemClickListener { _, _, position, _ -> voices.getOrNull(position)?.let(preview) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            voices.getOrNull(position)?.let { options(it) }
            true
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.manage_title)
            .setView(content)
            .setOnDismissListener { dialog = null }
            .show()
        up.setOnClickListener { dialog?.dismiss() }
        add.setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try {
                activity.startActivityForResult(pick, REQUEST_FILE)
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.invalid_voice, Toast.LENGTH_LONG).show()
            }
        }
        refresh()
    }

    /** Call from the activity's onActivityResult. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        askName(fileName(uri)) { name -> askLocale { locale -> import(uri, name, locale) } }
    }

    private fun refresh() {
        voices = ImportedVoices.list(activity).sortedBy { it.name.lowercase(Locale.getDefault()) }
        val display = Locale.getDefault()
        adapter?.apply {
            clear()
            addAll(voices.map { "${it.name}, ${it.locale.getDisplayName(display)}" })
        }
        empty?.visibility = if (voices.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    private fun options(voice: CatalogVoice) {
        AlertDialog.Builder(activity)
            .setTitle(voice.name)
            .setItems(arrayOf(activity.getString(R.string.rename), activity.getString(R.string.delete))) { _, which ->
                if (which == 0) rename(voice) else delete(voice)
            }
            .show()
    }

    private fun rename(voice: CatalogVoice) {
        askName(voice.name) { name ->
            if (attempt { ImportedVoices.rename(activity, voice.id, name) }) done()
            else rename(voice)
        }
    }

    private fun delete(voice: CatalogVoice) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.delete_confirm, voice.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                ImportedVoices.remove(activity, voice.id)
                done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun import(uri: Uri, name: String, locale: Locale) {
        val ok = attempt {
            val input = activity.contentResolver.openInputStream(uri) ?: throw ImportedVoices.ImportException(ImportedVoices.Reason.NOT_A_VOICE)
            input.use { ImportedVoices.add(activity, it, name, locale) }
        }
        if (ok) {
            done()
        } else if (lastReason == ImportedVoices.Reason.NAME_TAKEN || lastReason == ImportedVoices.Reason.NAME_INVALID) {
            // Only the name was wrong: ask again rather than make them pick the file again.
            askName(name) { again -> import(uri, again, locale) }
        }
    }

    private var lastReason: ImportedVoices.Reason? = null

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
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            lastReason = ImportedVoices.Reason.NOT_A_VOICE
            Toast.makeText(activity, R.string.invalid_voice, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun done() {
        refresh()
        changed()
    }

    private fun askName(initial: String, then: (String) -> Unit) {
        val field = EditText(activity).apply {
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            contentDescription = activity.getString(R.string.voice_name)
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field, MATCH_PARENT, WRAP_CONTENT)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.voice_name)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> then(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        field.requestFocus()
    }

    private fun askLocale(then: (Locale) -> Unit) {
        val display = Locale.getDefault()
        val locales = Voices.catalogLocales(activity)
        val preferred = locales.indexOfFirst { it == Locale(display.language, display.country) }
            .takeIf { it >= 0 } ?: locales.indexOfFirst { it.language == display.language }.coerceAtLeast(0)
        var chosen = preferred
        AlertDialog.Builder(activity)
            .setTitle(R.string.voice_language)
            .setSingleChoiceItems(locales.map { it.getDisplayName(display) }.toTypedArray(), preferred) { _, which -> chosen = which }
            .setPositiveButton(android.R.string.ok) { _, _ -> then(locales[chosen]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fileName(uri: Uri): String {
        val name = try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: ""
        return name.substringBeforeLast('.').take(ImportedVoices.MAX_NAME)
    }

    companion object {
        const val REQUEST_FILE = 41
    }
}
