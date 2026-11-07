package io.github.vrivrixd.phonon

import android.content.Context
import android.content.SharedPreferences

/** The engine's settings, shared by the settings screen and the service (one process). */
object Prefs {
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("phonon", Context.MODE_PRIVATE)

    fun defaultVoice(context: Context, lang: String): String? = prefs(context).getString("voice_$lang", null)
    fun setDefaultVoice(context: Context, lang: String, voice: String) =
        prefs(context).edit().putString("voice_$lang", voice).apply()

    /** Extra speed on top of the system rate, as Sonic speed. */
    fun speedBoost(context: Context): Float = prefs(context).getFloat("speed_boost", 1.0f)
    fun setSpeedBoost(context: Context, value: Float) = prefs(context).edit().putFloat("speed_boost", value).apply()

    /** Sampling temperature; negative means the model's own default. */
    fun temperature(context: Context): Float = prefs(context).getFloat("temperature", -1f)
    fun setTemperature(context: Context, value: Float) = prefs(context).edit().putFloat("temperature", value).apply()

    /** Whether to drop the silence the model makes before speech starts. */
    fun trimSilence(context: Context): Boolean = prefs(context).getBoolean("trim_silence", true)
    fun setTrimSilence(context: Context, value: Boolean) = prefs(context).edit().putBoolean("trim_silence", value).apply()

    /** CPU threads the runtime uses. Read once, when the model loads. */
    fun threads(context: Context): Int = prefs(context).getInt("threads", defaultThreads())
    fun setThreads(context: Context, value: Int) = prefs(context).edit().putInt("threads", value).commit()

    /** Whether the runtime runs the Mimi decoder in q8_0. Read once, when the model loads. */
    fun quantizeMimi(context: Context): Boolean = prefs(context).getBoolean("quantize_mimi", false)
    fun setQuantizeMimi(context: Context, value: Boolean) = prefs(context).edit().putBoolean("quantize_mimi", value).commit()

    fun defaultThreads(): Int = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2
}
