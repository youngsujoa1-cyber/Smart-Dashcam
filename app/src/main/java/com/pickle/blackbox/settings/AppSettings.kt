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

    /** 1 = least sensitive, 5 = most sensitive. */
    var impactSensitivity: Int
        get() = prefs.getInt(KEY_IMPACT_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(1, 5)
        set(value) = prefs.edit { putInt(KEY_IMPACT_SENSITIVITY, value.coerceIn(1, 5)) }

    var autoLockOnImpact: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_LOCK, value) }

    var recordAudio: Boolean
        get() = prefs.getBoolean(KEY_RECORD_AUDIO, true)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_AUDIO, value) }

    var showSpeed: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SPEED, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SPEED, value) }

    // ---- derived ----------------------------------------------------------

    val segmentMillis: Long
        get() = segmentMinutes * 60_000L

    val storageLimitBytes: Long
        get() = storageLimitGb * 1024L * 1024L * 1024L

    /** G-force threshold used by [com.pickle.blackbox.sensor.ImpactDetector]. */
    val impactThresholdG: Float
        get() = thresholdFor(impactSensitivity)

    companion object {
        private const val PREFS_NAME = "pickle_blackbox_settings"
        private const val KEY_SEGMENT_MINUTES = "segment_minutes"
        private const val KEY_STORAGE_LIMIT_GB = "storage_limit_gb"
        private const val KEY_IMPACT_SENSITIVITY = "impact_sensitivity"
        private const val KEY_AUTO_LOCK = "auto_lock_on_impact"
        private const val KEY_RECORD_AUDIO = "record_audio"
        private const val KEY_SHOW_SPEED = "show_speed"

        const val DEFAULT_SEGMENT_MINUTES = 3
        const val DEFAULT_STORAGE_LIMIT_GB = 2
        const val DEFAULT_SENSITIVITY = 3

        /** Maps the 1–5 sensitivity setting to the acceleration threshold in G. */
        fun thresholdFor(level: Int): Float = when (level.coerceIn(1, 5)) {
            5 -> 1.9f
            4 -> 2.2f
            3 -> 2.6f
            2 -> 3.0f
            else -> 3.4f
        }
    }
}
