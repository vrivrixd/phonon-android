package io.github.vrivrixd.phonon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * Voices the person added from files. Each is copied into the app's own storage, which no other
 * app can see, so the original file can be deleted afterwards. A voice keeps an id for life, so
 * renaming it changes nothing that apps or the engine remember; only the name shown changes.
 *
 * The settings screen writes here and the engine, in another process, reads, so the list lives in
 * a file replaced atomically.
 */
object ImportedVoices {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_VOICES = 64
    const val MAX_NAME = 80

    class ImportException(val reason: Reason) : Exception(reason.name)

    enum class Reason { NOT_A_VOICE, NAME_INVALID, NAME_TAKEN, TOO_MANY }

    private data class Cache(val stamp: Long, val voices: List<CatalogVoice>)

    @Volatile private var cache: Cache? = null

    private fun dir(context: Context) = File(context.filesDir, "voices")
    private fun index(context: Context) = File(dir(context), "index.json")

    /** Where the engine loads a voice from. */
    fun file(context: Context, id: String) = File(dir(context), "$id.safetensors")

    fun list(context: Context): List<CatalogVoice> {
        val index = index(context)
        val stamp = if (index.isFile) index.lastModified() * 31 + index.length() else 0L
        cache?.takeIf { it.stamp == stamp }?.let { return it.voices }
        val voices = try {
            if (stamp == 0L) emptyList() else parse(index.readText())
        } catch (e: Exception) {
            emptyList()
        }
        cache = Cache(stamp, voices)
        return voices
    }

    /** Copy a voice file in, as [name] speaking [locale]. */
    @Synchronized
    fun add(context: Context, input: InputStream, name: String, locale: Locale): CatalogVoice {
        val current = list(context)
        if (current.size >= MAX_VOICES) throw ImportException(Reason.TOO_MANY)
        val clean = checkName(context, name, null)
        val data = input.readBytesUpTo(MAX_BYTES + 1)
        if (data.size > MAX_BYTES || !isVoiceFile(data)) throw ImportException(Reason.NOT_A_VOICE)
        val voice = CatalogVoice(clean, locale, character = false, id = "imported-" + UUID.randomUUID().toString().replace("-", ""), imported = true)
        dir(context).mkdirs()
        val target = file(context, voice.id)
        val temp = File(target.path + ".tmp")
        temp.writeBytes(data)
        check(temp.renameTo(target)) { "could not store the voice" }
        write(context, current + voice)
        return voice
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String) {
        val clean = checkName(context, name, id)
        write(context, list(context).map { if (it.id == id) it.copy(name = clean) else it })
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
        file(context, id).delete()
    }

    private fun checkName(context: Context, name: String, self: String?): String {
        val clean = name.trim()
        if (!validName(clean)) throw ImportException(Reason.NAME_INVALID)
        if (Voices.all(context).any { it.id != self && it.name.equals(clean, ignoreCase = true) }) {
            throw ImportException(Reason.NAME_TAKEN)
        }
        return clean
    }

    private fun write(context: Context, voices: List<CatalogVoice>) {
        val array = JSONArray()
        for (v in voices) array.put(JSONObject().put("id", v.id).put("name", v.name).put("locale", v.locale.toLanguageTag()))
        val temp = File(dir(context), "index.tmp")
        temp.writeText(array.toString())
        check(temp.renameTo(index(context))) { "could not save the voice list" }
        cache = null
    }

    private fun parse(text: String): List<CatalogVoice> {
        val array = JSONArray(text)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            CatalogVoice(o.getString("name"), Locale.forLanguageTag(o.getString("locale")), false, o.getString("id"), true)
        }
    }

    private fun InputStream.readBytesUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun validName(name: String) = name.length in 1..MAX_NAME && name.none { it.isISOControl() }

    /**
     * Whether [data] is a Phonon voice: a safetensors file holding one F32 tensor
     * "speaker_wavs" of shape [1, 512, 125], like the catalog's voices.
     */
    fun isVoiceFile(data: ByteArray): Boolean {
        if (data.size < 8) return false
        var headerSize = 0L
        for (i in 7 downTo 0) headerSize = (headerSize shl 8) or (data[i].toLong() and 0xff)
        if (headerSize <= 0 || headerSize > 65536 || 8 + headerSize > data.size) return false
        val header = String(data, 8, headerSize.toInt(), Charsets.UTF_8).filterNot { it.isWhitespace() }
        val tensor = Regex("\"speaker_wavs\":\\{([^}]*)\\}").find(header)?.groupValues?.get(1) ?: return false
        if (!tensor.contains("\"dtype\":\"F32\"") || !tensor.contains("\"shape\":[1,512,125]")) return false
        return data.size.toLong() == 8 + headerSize + 512L * 125 * 4
    }
}
