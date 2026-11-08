package io.github.vrivrixd.phonon

import android.content.Context
import android.os.Process
import java.io.File
import java.util.Properties

/**
 * The engine's settings. The settings screen writes them and the engine, in another process, reads
 * them for every utterance. SharedPreferences does not see another process's changes, so they live
 * in a small file, replaced atomically.
 */
object Prefs {
    private const val OLD_PREFS = "phonon"

    private fun file(context: Context) = File(context.filesDir, "settings.properties")

    private fun read(context: Context): Properties {
        val file = file(context)
        if (!file.exists()) write(context, fromOldPrefs(context))
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun write(context: Context, values: Properties) {
        val temp = File(context.filesDir, "settings.${Process.myPid()}.tmp")
        temp.outputStream().use { values.store(it, null) }
        check(temp.renameTo(file(context))) { "could not save the settings" }
    }

    // Keeps what was chosen in versions before 1.1, which used SharedPreferences.
    private fun fromOldPrefs(context: Context) = Properties().apply {
        val old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE).all
        for ((key, value) in old) setProperty(key, value.toString())
    }

    private fun set(context: Context, key: String, value: Any) {
        val values = read(context)
        values.setProperty(key, value.toString())
        write(context, values)
    }

    private fun get(context: Context, key: String): String? = read(context).getProperty(key)

    fun defaultVoice(context: Context, lang: String): String? = get(context, "voice_$lang")
    fun setDefaultVoice(context: Context, lang: String, voice: String) = set(context, "voice_$lang", voice)

    /** Extra speed on top of the system rate, as Sonic speed. */
    fun speedBoost(context: Context): Float = get(context, "speed_boost")?.toFloatOrNull() ?: 1.0f
    fun setSpeedBoost(context: Context, value: Float) = set(context, "speed_boost", value)

    /** Sampling temperature; negative means the model's own default. */
    fun temperature(context: Context): Float = get(context, "temperature")?.toFloatOrNull() ?: -1f
    fun setTemperature(context: Context, value: Float) = set(context, "temperature", value)

    /** Whether to drop the silence the model makes before speech starts. */
    fun trimSilence(context: Context): Boolean = get(context, "trim_silence")?.toBooleanStrictOrNull() ?: true
    fun setTrimSilence(context: Context, value: Boolean) = set(context, "trim_silence", value)

    /** CPU threads the runtime uses. Read once, when the engine's process loads the model. */
    fun threads(context: Context): Int = get(context, "threads")?.toIntOrNull() ?: defaultThreads()
    fun setThreads(context: Context, value: Int) = set(context, "threads", value)

    // The runtime's worker pool gains little past three threads, and phones' extra cores are the slow ones.
    fun defaultThreads(): Int = minOf(3, Runtime.getRuntime().availableProcessors())
}
