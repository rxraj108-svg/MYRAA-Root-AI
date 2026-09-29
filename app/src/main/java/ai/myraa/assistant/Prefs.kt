package ai.myraa.assistant

import android.content.Context

object Prefs {
    const val KEY = "api_key"
    const val MODEL = "model"
    const val VOICE = "voice"
    const val LANG = "lang"
    const val NAME = "name"
    const val WATCH = "watch"
    const val DEFAULT_MODEL = "gemini-3.8-live"

    private fun sp(c: Context) = c.getSharedPreferences("myraa", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun getBool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}
