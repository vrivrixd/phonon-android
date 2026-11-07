package io.github.vrivrixd.phonon

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/** One catalog voice and the locale it speaks. Read from assets/voices.json, no model needed. */
data class CatalogVoice(val name: String, val locale: Locale, val character: Boolean) {
    /** The runtime's language code for text normalization. */
    val lang: String get() = locale.language

    /** The Android voice name, unique across the catalog. */
    val androidName: String get() = "${locale.toLanguageTag()}-x-${name}"
}

object Voices {
    /** The languages the model speaks and the runtime normalizes. */
    val LANGUAGES = listOf("pt", "en", "es", "fr", "de")

    @Volatile private var cached: List<CatalogVoice>? = null

    fun all(context: Context): List<CatalogVoice> = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): List<CatalogVoice> {
        val text = context.assets.open("voices.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONArray(text)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            CatalogVoice(o.getString("name"), Locale.forLanguageTag(o.getString("locale")), o.optBoolean("character"))
        }
    }

    fun byAndroidName(context: Context, name: String?): CatalogVoice? =
        name?.let { n -> all(context).firstOrNull { it.androidName == n } }

    fun byName(context: Context, name: String?): CatalogVoice? =
        name?.let { n -> all(context).firstOrNull { it.name == n } }

    /** A language as Android names it (two or three letters) to one of [LANGUAGES], or null. */
    fun code(language: String?): String? {
        if (language.isNullOrEmpty()) return null
        val iso3 = when (language.lowercase(Locale.ROOT)) {
            "fre" -> "fra"
            "ger" -> "deu"
            else -> try {
                Locale.forLanguageTag(language).isO3Language
            } catch (e: Exception) {
                return null
            }
        }
        return LANGUAGES.firstOrNull { Locale.forLanguageTag(it).isO3Language == iso3 }
    }

    /** A country as Android names it (two or three letters) to ISO 3166 alpha-2, or "". */
    fun country(country: String?): String {
        if (country.isNullOrEmpty()) return ""
        if (country.length == 2) return country.uppercase(Locale.ROOT)
        return Locale.getISOCountries().firstOrNull {
            try {
                Locale("", it).isO3Country.equals(country, ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        } ?: ""
    }

    /** The voices offered for a language, regional ones first, then the character voices. */
    fun forLanguage(context: Context, lang: String): List<CatalogVoice> =
        all(context).filter { it.lang == lang }.sortedBy { it.character }

    /**
     * The voice to speak [lang] (and [country], if given) when a request names none: the user's
     * choice for that language, else the first voice of that country, else of that language.
     */
    fun defaultFor(context: Context, lang: String, country: String): CatalogVoice? {
        val candidates = forLanguage(context, lang)
        Prefs.defaultVoice(context, lang)?.let { chosen -> candidates.firstOrNull { it.name == chosen }?.let { return it } }
        return candidates.firstOrNull { !it.character && it.locale.country == country }
            ?: candidates.firstOrNull { !it.character }
            ?: candidates.firstOrNull()
    }

    /** The countries the catalog has regional voices for, per language. */
    fun countries(context: Context, lang: String): Set<String> =
        all(context).filter { it.lang == lang && it.locale.country.isNotEmpty() }.map { it.locale.country }.toSet()
}
