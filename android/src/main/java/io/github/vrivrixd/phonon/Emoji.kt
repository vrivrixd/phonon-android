package io.github.vrivrixd.phonon

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Emoji read aloud by name, since the runtime drops them from the text. The names come from
 * Unicode CLDR, one table per language in assets/emoji (scripts/generate-emoji-tables.py).
 */
object Emoji {
    private val tables = ConcurrentHashMap<String, EmojiTable>()

    /** [text] with its emoji replaced by their names in [lang]. */
    fun spell(context: Context, text: String, lang: String): String {
        if (!mayHaveEmoji(text)) return text
        return try {
            table(context, lang).spell(text)
        } catch (e: Exception) {
            Log.w("PhononEmoji", "no emoji table for $lang", e)
            text
        }
    }

    // Keeps ordinary text from loading a table at all.
    private fun mayHaveEmoji(text: String) =
        text.any { it.isSurrogate() || it.code in 0x2000..0x33FF || it == '©' || it == '®' }

    private fun table(context: Context, lang: String): EmojiTable = tables.getOrPut(lang) {
        context.assets.open("emoji/$lang.tsv").bufferedReader(Charsets.UTF_8).useLines { EmojiTable(it) }
    }
}

/** A table of "emoji, tab, name" lines. Keys have no variation selectors. */
class EmojiTable(lines: Sequence<String>) {
    private val names = HashMap<String, String>()
    private val firstChars = HashSet<Char>()
    private var longest = 0

    init {
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val key = line.substring(0, tab)
            // CLDR writes "bandeira: Brasil"; the colon and commas would be read as pauses.
            names[key] = line.substring(tab + 1).replace(PAUSES, " ")
            firstChars += key[0]
            longest = maxOf(longest, key.length)
        }
    }

    /**
     * Replace each emoji, longest sequence first, by its name and no added pause, as it sits on
     * the screen: "Oi 😀 tudo bem" becomes "Oi rosto risonho tudo bem".
     */
    fun spell(text: String): String {
        val source = text.replace("️", "")
        val out = StringBuilder(source.length + 32)
        var afterName = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val length = if (c in firstChars) matchAt(source, i) else 0
            if (length > 0) {
                if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                out.append(names.getValue(source.substring(i, i + length)))
                afterName = true
                i += length
                continue
            }
            if (afterName && c.isLetterOrDigit()) out.append(' ')
            afterName = false
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun matchAt(text: String, start: Int): Int {
        var length = minOf(longest, text.length - start)
        while (length > 0) {
            if (text.substring(start, start + length) in names) return length
            length--
        }
        return 0
    }

    private companion object {
        val PAUSES = Regex("\\s*[:,]\\s*")
    }
}
