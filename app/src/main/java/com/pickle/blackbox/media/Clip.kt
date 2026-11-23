package com.pickle.blackbox.media

import android.net.Uri

/**
 * One recorded segment as it is stored in MediaStore under Movies/PickleBlackbox.
 *
 * @param uri            content:// uri of the video (playable / deletable)
 * @param displayName    file name, prefixed with "LOCKED_" when protected from loop cleanup
 * @param sizeBytes      file size in bytes
 * @param dateAddedSeconds epoch seconds from MediaStore.DATE_ADDED
 * @param durationMs     clip length in milliseconds
 * @param isLocked       true when this clip was locked manually by the user
 * @param filePath       absolute path when known (used for legacy thumbnails / fallbacks)
 */
data class Clip(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val durationMs: Long,
    val isLocked: Boolean,
    val filePath: String? = null
)

/** Snapshot of how much space the dashcam library currently uses. */
data class StorageUsage(
    val usedBytes: Long,
    val limitBytes: Long,
    val clipCount: Int
)
