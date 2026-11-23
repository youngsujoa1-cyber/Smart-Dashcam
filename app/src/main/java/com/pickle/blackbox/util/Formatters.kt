package com.pickle.blackbox.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small formatting helpers shared by the HUD and the clip list.
 * SimpleDateFormat is not thread safe, so every formatter is created locally or guarded.
 */
object Formatters {

    fun clock(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun duration(millis: Long): String {
        if (millis <= 0L) return "--:--"
        val totalSeconds = millis / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0L) return "0MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2fGB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1fMB", mb)
            else -> String.format(Locale.US, "%.0fKB", kb)
        }
    }

    /** "2026-11-22 21:58:27" from epoch seconds. */
    fun dateTime(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
            .format(Date(epochSeconds * 1000L))

    /** "11.22 21:58" from epoch seconds. */
    fun shortDateTime(epochSeconds: Long): String =
        SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)
            .format(Date(epochSeconds * 1000L))

    /** Current wall clock as "yyyy-MM-dd HH:mm:ss". */
    fun nowDateTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())

    /** Current time as "HH:mm:ss". */
    fun nowTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())

    /** Timestamp used inside clip file names: yyyyMMdd_HHmmss. */
    fun fileStamp(epochMillis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(epochMillis))
}
