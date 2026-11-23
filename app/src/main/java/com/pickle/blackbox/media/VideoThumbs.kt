package com.pickle.blackbox.media

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.pickle.blackbox.R
import java.util.concurrent.Executor

/**
 * Loads a small frame of a clip for the gallery list.
 * Runs off the main thread and checks the view tag before touching the ImageView so
 * recycled rows do not show the wrong picture.
 */
object VideoThumbs {

    @Suppress("DEPRECATION")
    fun load(context: Context, clip: Clip, view: ImageView, executor: Executor, placeholder: Int) {
        val tag = clip.uri.toString()
        view.tag = tag
        view.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.bb_text_dim)
        )
        view.setImageResource(placeholder)

        executor.execute {
            val bitmap = runCatching { decode(context, clip) }.getOrNull() ?: return@execute
            view.post {
                if (view.tag == tag) {
                    view.imageTintList = null
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decode(context: Context, clip: Clip): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(clip.uri, Size(320, 180), null)
        } else {
            val path = clip.filePath ?: return null
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
        }
    }

    /** Fallback used when MediaStore thumbnails are unavailable. */
    fun decodeWithRetriever(clip: Clip): Bitmap? {
        val path = clip.filePath ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
