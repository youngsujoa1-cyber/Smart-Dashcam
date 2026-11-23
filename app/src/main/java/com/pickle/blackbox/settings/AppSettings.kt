package com.pickle.blackbox.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * User facing recording preferences, persisted in SharedPreferences.
 *
 * Values are read on every use (instead of being cached) so a change in the
 * settings dialog takes effect from the next recording segment on.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Recording segment length in minutes (1 / 3 / 5). */
    var segmentMinutes: Int
        get() = prefs.getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES)
        set(value) = prefs.edit { putInt(KEY_SEGMENT_MINUTES, value) }

    /** Loop-recording budget for normal (unlocked) clips, in GB (1 / 2 / 5). */
    var storageLimitGb: Int
        get() = prefs.getInt(KEY_STORAGE_LIMIT_GB, DEFAULT_STORAGE_LIMIT_GB)
        set(value) = prefs.edit { putInt(KEY_STORAGE_LIMIT_GB, value) }

    var recordAudio: Boolean
        get() = prefs.getBoolean(KEY_RECORD_AUDIO, true)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_AUDIO, value) }

    var showSpeed: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SPEED, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SPEED, value) }

    /**
     * 녹화 방향. false = 자동 (follow how the phone is really held), true = 가로 고정
     * (always record 16:9 landscape, whatever way the phone is held or the app was started).
     */
    var landscapeLocked: Boolean
        get() = prefs.getBoolean(KEY_LANDSCAPE_LOCKED, DEFAULT_LANDSCAPE_LOCKED)
        set(value) = prefs.edit { putBoolean(KEY_LANDSCAPE_LOCKED, value) }

    /** BCP-47 language tag chosen in the app ("" = follow the system language). */
    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE_TAG, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LANGUAGE_TAG, value) }

    /**
     * False until the user has answered the first-launch language screen (or dismissed it),
     * so that screen is shown exactly once per install and never on later launches.
     */
    var hasSelectedLanguage: Boolean
        get() = prefs.getBoolean(KEY_HAS_SELECTED_LANGUAGE, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_SELECTED_LANGUAGE, value) }

    // ---- derived ----------------------------------------------------------

    val segmentMillis: Long
        get() = segmentMinutes * 60_000L

    val storageLimitBytes: Long
        get() = storageLimitGb * 1024L * 1024L * 1024L

    companion object {
        private const val PREFS_NAME = "pickle_blackbox_settings"
        private const val KEY_SEGMENT_MINUTES = "segment_minutes"
        private const val KEY_STORAGE_LIMIT_GB = "storage_limit_gb"
        private const val KEY_RECORD_AUDIO = "record_audio"
        private const val KEY_SHOW_SPEED = "show_speed"
        private const val KEY_LANDSCAPE_LOCKED = "landscape_locked"
        private const val KEY_LANGUAGE_TAG = "language_tag"
        private const val KEY_HAS_SELECTED_LANGUAGE = "has_selected_language"

        const val DEFAULT_SEGMENT_MINUTES = 3
        const val DEFAULT_STORAGE_LIMIT_GB = 2

        /** 녹화 방향 default: 자동. */
        const val DEFAULT_LANDSCAPE_LOCKED = false
    }
}
