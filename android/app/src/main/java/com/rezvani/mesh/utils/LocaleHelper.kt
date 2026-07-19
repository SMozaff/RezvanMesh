package com.rezvani.mesh.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    /** Supported language codes, in picker display order.
     * Temporarily scoped to English + Farsi only, per current product
     * decision. Arabic/Urdu/Kashmiri resource files (values-ar, values-ur,
     * values-ks) are left on disk untouched -- real translation work, not
     * deleted -- just not currently selectable/detected, since this is
     * stated as temporary. Re-add to this list (and to RTL_LANGUAGES if
     * applicable) to bring them back. */
    val SUPPORTED = listOf("en", "fa")

    /** Codes whose script is right-to-left. */
    val RTL_LANGUAGES = setOf("fa")

    fun isRtl(languageCode: String): Boolean = languageCode in RTL_LANGUAGES

    private fun buildLocale(code: String): Locale = when (code) {
        // Kashmiri's default script is Perso-Arabic. Declare it explicitly so
        // the layout direction resolves to RTL on every API level (a bare
        // Locale("ks") can resolve LTR on some devices). Kept here even
        // though "ks" isn't in SUPPORTED right now, so re-enabling it later
        // doesn't require rediscovering this device-compatibility detail.
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

    /**
     * Returns the language to use: an explicit user choice if one has ever
     * been saved, otherwise auto-detected from the DEVICE's actual system
     * locale -- not a hardcoded default.
     *
     * Previously this always returned "en" until the user manually changed
     * it in Settings, regardless of the phone's real locale -- so a phone
     * already set to Persian/Farsi would install and run in English by
     * default, with zero auto-detection. Fixed by checking the system's
     * configured locale list on first run (no saved preference yet, or a
     * previously saved language that's no longer in SUPPORTED) via
     * Configuration.locales, which reflects the user's actual
     * device-language setting(s).
     */
    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null)
        if (saved != null && saved in SUPPORTED) {
            return saved
        }
        return detectSystemLanguage(context)
    }

    /**
     * Inspects the device's actual configured locale list and returns the
     * best SUPPORTED match. Checks the FULL list (not just the primary
     * locale), since some devices/regions list multiple preferred languages
     * -- if Farsi appears anywhere in that list, prefer it, since a user who
     * has Farsi configured at all very likely wants the app in Farsi even if
     * it isn't their first-listed system language. Falls back to English
     * (the only other currently-supported language) if Farsi isn't found
     * anywhere in the list.
     */
    private fun detectSystemLanguage(context: Context): String {
        val configLocales = context.resources.configuration.locales
        for (i in 0 until configLocales.size()) {
            if (configLocales[i].language == "fa") return "fa"
        }
        return "en"
    }

    fun saveLanguage(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, languageCode).apply()
    }
}