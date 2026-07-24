package com.rezvani.mesh.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    /** Supported language codes, in picker display order. */
    val SUPPORTED = listOf("en", "fa", "ar", "ur", "ks")

    /** Codes whose script is right-to-left. */
    val RTL_LANGUAGES = setOf("fa", "ar", "ur", "ks")

    fun isRtl(languageCode: String): Boolean = languageCode in RTL_LANGUAGES

    private fun buildLocale(code: String): Locale = when (code) {
        // Kashmiri's default script is Perso-Arabic. Declare it explicitly so
        // the layout direction resolves to RTL on every API level (a bare
        // Locale("ks") can resolve LTR on some devices).
        "ks" -> Locale.Builder().setLanguage("ks").setScript("Arab").build()
        else -> Locale(code)
    }

    fun setLocale(context: Context, languageCode: String): Context {
        val locale = buildLocale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    }

    fun saveLanguage(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, languageCode).apply()
    }
}