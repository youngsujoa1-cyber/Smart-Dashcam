package com.pickle.blackbox.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.pickle.blackbox.R
import com.pickle.blackbox.databinding.ItemClipBinding
import com.pickle.blackbox.media.Clip
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.media.VideoThumbs
import com.pickle.blackbox.util.Formatters
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Clip list with thumbnail, metadata, lock toggle and delete. */
class ClipAdapter(
    private val onPlay: (Clip) -> Unit,
    private val onToggleLock: (Clip) -> Unit,
    private val onDelete: (Clip) -> Unit
) : RecyclerView.Adapter<ClipAdapter.ClipViewHolder>() {

    private val items = mutableListOf<Clip>()
    private val thumbExecutor: Executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "thumb-loader").apply { isDaemon = true }
    }

    fun submit(clips: List<Clip>) {
        items.clear()
        items.addAll(clips)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val binding = ItemClipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ClipViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ClipViewHolder(private val binding: ItemClipBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(clip: Clip) {
            val context = binding.root.context
            val name = clip.displayName.removePrefix(ClipStorage.LOCKED_PREFIX)

            binding.tvName.text = name
            binding.tvMeta.text = context.getString(
                R.string.gallery_meta_format,
                Formatters.shortDateTime(clip.dateAddedSeconds),
                Formatters.duration(clip.durationMs),
                Formatters.size(clip.sizeBytes)
            )
            binding.tvDurationBadge.text = Formatters.duration(clip.durationMs)
            binding.tvLocked.isVisible = clip.isLocked

            val lockIcon = if (clip.isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
            val lockTint = if (clip.isLocked) R.color.bb_locked else R.color.bb_text_dim
            binding.btnLock.setImageResource(lockIcon)
            binding.btnLock.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, lockTint))
            binding.btnLock.contentDescription = context.getString(
                if (clip.isLocked) R.string.action_unlock else R.string.action_lock
            )

            VideoThumbs.load(context, clip, binding.ivThumb, thumbExecutor, R.drawable.ic_videocam)

            binding.root.setOnClickListener { onPlay(clip) }
            binding.btnLock.setOnClickListener { onToggleLock(clip) }
            binding.btnDelete.setOnClickListener { onDelete(clip) }
        }
    }
}
