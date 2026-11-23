package com.pickle.blackbox.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.pickle.blackbox.util.Formatters
import java.io.File
import java.util.concurrent.Executors

/**
 * Everything that touches recorded files.
 *
 * Segments are written by CameraX into app-external storage (a temp file) and then
 * published to the shared gallery so other apps (Gallery, file managers) can play them:
 *
 *  - Android 10 (API 29)+ : MediaStore.Video with RELATIVE_PATH = Movies/PickleBlackbox
 *  - Android 9  (API 28)- : public Movies/PickleBlackbox directory + MediaScanner
 *
 * Locked clips are marked with the "LOCKED_" file name prefix and are never touched by
 * [enforceLimitBlocking], which implements the loop-recording budget.
 *
 * All disk / MediaStore work happens on a single background thread; callers get their
 * result back on the main thread.
 */
class ClipStorage(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "clip-storage") }
    private val main = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- paths

    /** A fresh temp file for the next CameraX segment. */
    fun newSegmentFile(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "segments")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "seg_" + System.currentTimeMillis() + ".mp4")
    }

    private fun legacyDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)

    private fun contentCollection(): Uri =
        if (isScopedStorage) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    // ---------------------------------------------------------- publishing

    /**
     * Moves a finished segment into the public Movies/PickleBlackbox folder.
     * Returns the published clip, or null when nothing usable was produced.
     */
    fun publishSegmentBlocking(tempFile: File, locked: Boolean, recordedAtMillis: Long): Clip? {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            tempFile.delete()
            return null
        }
        val displayName = buildDisplayName(recordedAtMillis, locked)
        return try {
            if (isScopedStorage) publishToMediaStore(tempFile, displayName) else publishLegacy(tempFile, displayName)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not publish segment " + displayName, t)
            null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun buildDisplayName(recordedAtMillis: Long, locked: Boolean): String {
        val prefix = if (locked) LOCKED_PREFIX else ""
        return prefix + "BB_" + Formatters.fileStamp(recordedAtMillis) + ".mp4"
    }

    private fun publishToMediaStore(tempFile: File, displayName: String): Clip? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(contentCollection(), values) ?: return null
        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return findClipByUri(uri)
    }

    private fun publishLegacy(tempFile: File, displayName: String): Clip? {
        val dir = legacyDirectory()
        if (!dir.exists() && !dir.mkdirs()) return null
        val destination = File(dir, displayName)
        tempFile.copyTo(destination, overwrite = true)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf(MIME_TYPE),
            null
        )
        return findClipByPath(destination.absolutePath) ?: insertLegacyEntry(destination)
    }

    private fun insertLegacyEntry(file: File): Clip? {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.TITLE, file.nameWithoutExtension)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.DATA, file.absolutePath)
            put(MediaStore.Video.Media.SIZE, file.length())
            put(MediaStore.Video.Media.DATE_ADDED, nowSeconds)
            put(MediaStore.Video.Media.DATE_MODIFIED, file.lastModified() / 1000L)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return findClipByUri(uri) ?: Clip(
            uri = uri,
            displayName = file.name,
            sizeBytes = file.length(),
            dateAddedSeconds = nowSeconds,
            durationMs = 0L,
            isLocked = isLockedName(file.name),
            filePath = file.absolutePath
        )
    }

    // ------------------------------------------------------------- queries

    fun queryAllBlocking(): List<Clip> {
        val clips = ArrayList<Clip>()
        val collection = contentCollection()
        val selection = if (isScopedStorage) {
            MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?"
        } else {
            MediaStore.Video.Media.DATA + " LIKE ?"
        }
        val argument = if (isScopedStorage) "$RELATIVE_PATH/%" else legacyDirectory().absolutePath + "/%"
        val order = MediaStore.Video.Media.DATE_ADDED + " DESC, " + MediaStore.Video.Media._ID + " DESC"
        runCatching {
            resolver.query(collection, PROJECTION, selection, arrayOf(argument), order)
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                clips.add(readClip(cursor, ContentUris.withAppendedId(collection, id)))
            }
        }
        return clips
    }

    fun listClips(callback: (List<Clip>) -> Unit) {
        io.execute {
            val clips = queryAllBlocking()
            main.post { callback(clips) }
        }
    }

    fun computeUsage(callback: (StorageUsage) -> Unit) {
        io.execute {
            val clips = queryAllBlocking()
            val usage = StorageUsage(
                usedBytes = clips.sumOf { it.sizeBytes },
                limitBytes = 0L,
                clipCount = clips.size
            )
            main.post { callback(usage) }
        }
    }

    private fun findClipByUri(uri: Uri): Clip? {
        return runCatching {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) readClip(cursor, uri) else null
            }
        }.getOrNull()
    }

    private fun findClipByPath(path: String): Clip? {
        val selection = MediaStore.Video.Media.DATA + " = ?"
        return runCatching {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                selection,
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    readClip(cursor, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id))
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun readClip(cursor: Cursor, uri: Uri): Clip {
        val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
        val dateIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
        val durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
            cursor.getString(nameIndex)
        } else {
            "BB_clip.mp4"
        }
        val path = if (dataIndex >= 0 && !cursor.isNull(dataIndex)) cursor.getString(dataIndex) else null
        return Clip(
            uri = uri,
            displayName = name,
            sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
            dateAddedSeconds = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L,
            durationMs = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
            isLocked = isLockedName(name),
            filePath = path
        )
    }

    // -------------------------------------------------------- lock / delete

    fun setLocked(clip: Clip, locked: Boolean, callback: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching { setLockedBlocking(clip, locked) }.getOrDefault(false)
            main.post { callback(ok) }
        }
    }

    private fun setLockedBlocking(clip: Clip, locked: Boolean): Boolean {
        val baseName = stripLockedPrefix(clip.displayName)
        val targetName = if (locked) LOCKED_PREFIX + baseName else baseName
        if (clip.isLocked == locked) {
            // Already in the requested state.
            return true
        }

        if (isScopedStorage) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
            }
            val updated = runCatching { resolver.update(clip.uri, values, null, null) }.getOrDefault(0)
            return updated > 0
        }

        // API 26–28: rename the real file, then refresh the MediaStore row.
        val file = clip.filePath?.let { File(it) } ?: return false
        if (!file.exists()) return false
        val target = File(file.parentFile, targetName)
        if (!file.renameTo(target)) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
            put(MediaStore.Video.Media.DATA, target.absolutePath)
        }
        runCatching { resolver.update(clip.uri, values, null, null) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MIME_TYPE), null)
        return true
    }

    /** Locks the newest clip that is not locked yet. */
    fun lockLatestClip(callback: (Clip?) -> Unit) {
        io.execute {
            val newest = queryAllBlocking().firstOrNull { !it.isLocked }
            var result: Clip? = null
            if (newest != null && runCatching { setLockedBlocking(newest, true) }.getOrDefault(false)) {
                result = newest.copy(
                    displayName = LOCKED_PREFIX + stripLockedPrefix(newest.displayName),
                    isLocked = true
                )
            }
            main.post { callback(result) }
        }
    }

    fun deleteClip(clip: Clip, callback: (Boolean) -> Unit) {
        io.execute {
            val ok = deleteBlocking(clip)
            main.post { callback(ok) }
        }
    }

    private fun deleteBlocking(clip: Clip): Boolean {
        val rowDeleted = runCatching { resolver.delete(clip.uri, null, null) > 0 }.getOrDefault(false)
        if (rowDeleted) return true
        // The MediaStore row may already be gone, or the delete was refused: remove the file.
        val file = clip.filePath?.let { File(it) }
        val fileDeleted = file != null && file.exists() && file.delete()
        if (fileDeleted) {
            runCatching { resolver.delete(clip.uri, null, null) }
        }
        return fileDeleted
    }

    // ------------------------------------------------------- loop recording

    /**
     * Deletes the oldest *unlocked* clips until the library fits [limitBytes].
     * Locked clips are always kept, even when they alone exceed the budget.
     */
    fun enforceLimitBlocking(limitBytes: Long) {
        val clips = queryAllBlocking()
        var total = clips.sumOf { it.sizeBytes }
        if (total <= limitBytes) return

        val unlockedOldestFirst = clips
            .filter { !it.isLocked }
            .sortedBy { it.dateAddedSeconds }

        for (clip in unlockedOldestFirst) {
            if (total <= limitBytes) break
            if (deleteBlocking(clip)) {
                total -= clip.sizeBytes
                Log.d(TAG, "Loop cleanup removed " + clip.displayName)
            }
        }
        if (total > limitBytes) {
            Log.w(TAG, "Storage still above budget; locked clips were kept")
        }
    }

    /** Removes leftover temp segments from crashed sessions. */
    fun purgeTempSegments() {
        io.execute {
            runCatching {
                val base = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(base, "segments")
                val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff) file.delete()
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private val isScopedStorage: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun isLockedName(name: String): Boolean = name.startsWith(LOCKED_PREFIX)

    private fun stripLockedPrefix(name: String): String =
        if (name.startsWith(LOCKED_PREFIX)) name.removePrefix(LOCKED_PREFIX) else name

    companion object {
        private const val TAG = "ClipStorage"

        const val FOLDER = "PickleBlackbox"
        const val LOCKED_PREFIX = "LOCKED_"
        const val MIME_TYPE = "video/mp4"

        val RELATIVE_PATH: String = Environment.DIRECTORY_MOVIES + "/" + FOLDER

        private val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA
        )
    }
}
