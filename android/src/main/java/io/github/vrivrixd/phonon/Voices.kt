package io.github.vrivrixd.phonon

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * One voice and the locale it speaks: from the catalog (assets/voices.json), contributed for this
 * app (assets/contributed-voices.json, present in official builds only), or added by the person
 * ([ImportedVoices]). None of this needs the model.
 *
 * @param name what is shown.
 * @param id what the runtime and Android know it by: the name for bundled voices, a fixed id for
 *   added ones, so renaming them changes nothing that apps remember.
 */
data class CatalogVoice(
    val name: String,
    val locale: Locale,
    val character: Boolean,
    val id: String = name,
    val imported: Boolean = false,
) {
    /** The runtime's language code for text normalization. */
    val lang: String get() = locale.language

    /** The Android voice name, unique across all voices. */
    val androidName: String get() = "${locale.toLanguageTag()}-x-${id}"
}

object Voices {
    /** The languages the model speaks and the runtime normalizes. */
    val LANGUAGES = listOf("pt", "en", "es", "fr", "de")

    /** What speaks when a language has no voices. */
    const val FALLBACK_LANGUAGE = "en"

    /** [LANGUAGES] for showing: the phone's language first (else English), then English, then the rest. */
    fun languagesFor(system: String): List<String> =
        (listOf(if (system in LANGUAGES) system else FALLBACK_LANGUAGE, FALLBACK_LANGUAGE) + LANGUAGES).distinct()

    private const val DEFAULT_SUFFIX = "-x-default"
    private const val CONTRIBUTED = "contributed-voices.json"

    @Volatile private var cached: List<CatalogVoice>? = null

    /** Every voice: the bundled ones, then the ones the person added. */
    fun all(context: Context): List<CatalogVoice> = catalog(context) + ImportedVoices.list(context)

    private fun catalog(context: Context): List<CatalogVoice> = cached ?: synchronized(this) {
        cached ?: (read(context, "voices.json").orEmpty() + read(context, CONTRIBUTED).orEmpty()).also { cached = it }
    }

    private fun read(context: Context, asset: String): List<CatalogVoice>? {
        val text = try {
            context.assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            return null
        }
        val array = JSONArray(text)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            CatalogVoice(o.getString("name"), Locale.forLanguageTag(o.getString("locale")), o.optBoolean("character"))
        }
    }

    /** Whether this build carries the voices contributed for it. */
    fun hasContributed(context: Context): Boolean = read(context, CONTRIBUTED) != null

    /** The regional locales of the bundled voices, the ones an added voice can speak. */
    fun catalogLocales(context: Context): List<Locale> =
        catalog(context).filter { !it.character }.map { it.locale }.distinct()

    /**
     * The default voices, one per regional locale: what Android hands to apps that ask for a
     * language. Apps keep the name they got and send it with every request, so a real voice's name
     * here would freeze the choice until they reconnect. These stand for "whichever voice the
     * settings name right now", and a voice an app picks by its real name stays honoured.
     */
    fun defaultVoiceLocales(context: Context): List<Locale> = catalogLocales(context)

    fun defaultVoiceName(locale: Locale): String = "${locale.toLanguageTag()}$DEFAULT_SUFFIX"

    /** The locale of a name from [defaultVoiceName], or null for any other name. */
    fun defaultVoiceLocale(context: Context, name: String?): Locale? {
        if (name == null || !name.endsWith(DEFAULT_SUFFIX)) return null
        val locale = Locale.forLanguageTag(name.removeSuffix(DEFAULT_SUFFIX))
        return locale.takeIf { it in defaultVoiceLocales(context) }
    }

    fun byAndroidName(context: Context, name: String?): CatalogVoice? =
        name?.let { n -> all(context).firstOrNull { it.androidName == n } }

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

    /**
     * The voices offered for a language: regional ones first, then character voices, in catalog
     * order, then the added ones in alphabetical order.
     */
    fun forLanguage(context: Context, lang: String): List<CatalogVoice> =
        all(context).filter { it.lang == lang }.sortedWith(
            compareBy<CatalogVoice> { if (it.imported) 2 else if (it.character) 1 else 0 }
                .thenBy { if (it.imported) it.name.lowercase(Locale.ROOT) else "" },
        )

    /**
     * The voice to speak [lang] (and [country], if given) when a request names none: the user's
     * choice for that language, else the first voice of that country, else of that language.
     */
    fun defaultFor(context: Context, lang: String, country: String): CatalogVoice? {
        val candidates = forLanguage(context, lang)
        Prefs.defaultVoice(context, lang)?.let { chosen -> candidates.firstOrNull { it.id == chosen }?.let { return it } }
        return candidates.firstOrNull { !it.character && it.locale.country == country }
            ?: candidates.firstOrNull { !it.character }
            ?: candidates.firstOrNull()
    }

    /** The countries the catalog has regional voices for, per language. */
    fun countries(context: Context, lang: String): Set<String> =
        all(context).filter { it.lang == lang && it.locale.country.isNotEmpty() }.map { it.locale.country }.toSet()
}
