package com.hamoon.unclecarbon.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * A utility object to manage the application's locale using AppCompatDelegate.
 * This is the modern and recommended way to handle in-app language switching.
 */
object LocaleManager {

    /**
     * Sets the application's locale.
     * @param languageCode The ISO 639-1 code for the language (e.g., "en", "fa"),
     * or "system" to revert to the system's default language.
     */
    fun setLocale(languageCode: String) {
        if (languageCode.equals("system", ignoreCase = true)) {
            // Passing an empty list tells AppCompat to use the system default.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val appLocale = LocaleListCompat.forLanguageTags(languageCode)
            AppCompatDelegate.setApplicationLocales(appLocale)
        }
    }
}