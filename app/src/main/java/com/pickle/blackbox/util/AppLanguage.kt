package com.pickle.blackbox.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.pickle.blackbox.R

/**
 * In-app language choice.
 *
 * The selection is stored in [com.pickle.blackbox.settings.AppSettings] and applied through
 * AppCompat's per-app locale API, so every resource string (screens, dialogs, toasts and the
 * recording notification) switches language without restarting the process.
 */
object AppLanguage {

    /** Stored tag for "follow the system language". */
    const val SYSTEM = ""

    /** Selectable tags, in the order shown in the settings dialog. */
    val TAGS = listOf(SYSTEM, "ko", "en", "ja", "zh", "es")

    /** (tag, label) pairs. Language names stay in their own language on purpose. */
    fun options(context: Context): List<Pair<String, String>> = listOf(
        SYSTEM to context.getString(R.string.language_system),
        "ko" to "한국어",
        "en" to "English",
        "ja" to "日本語",
        "zh" to "中文",
        "es" to "Español"
    )

    /** Applies [tag] to the whole app ("" = system default). */
    fun apply(tag: String) {
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
