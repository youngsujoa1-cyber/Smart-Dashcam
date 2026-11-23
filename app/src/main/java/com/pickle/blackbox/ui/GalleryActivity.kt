package com.pickle.blackbox.ui

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pickle.blackbox.BlackboxApp
import com.pickle.blackbox.R
import com.pickle.blackbox.ads.AdManager
import com.pickle.blackbox.databinding.ActivityGalleryBinding
import com.pickle.blackbox.databinding.DialogPlayerBinding
import com.pickle.blackbox.media.Clip
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.util.Formatters

/**
 * Lists the recorded clips (normal + locked), plays them, toggles the lock and deletes.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var clipStorage: ClipStorage
    private lateinit var adManager: AdManager
    private lateinit var adapter: ClipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = applicationContext as BlackboxApp
        clipStorage = app.clipStorage
        adManager = app.adManager

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ClipAdapter(
            onPlay = { clip -> playClip(clip) },
            onToggleLock = { clip -> toggleLock(clip) },
            onDelete = { clip -> confirmDelete(clip) }
        )
        binding.recyclerClips.layoutManager = LinearLayoutManager(this)
        binding.recyclerClips.adapter = adapter
        (binding.recyclerClips.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        adManager.createBanner(binding.adContainer)
        adManager.preloadInterstitial()
    }

    override fun onResume() {
        super.onResume()
        loadClips()
    }

    private fun loadClips() {
        binding.tvSummary.setText(R.string.gallery_summary_loading)
        clipStorage.listClips { clips ->
            if (isFinishing || isDestroyed) return@listClips
            adapter.submit(clips)
            binding.tvEmpty.isVisible = clips.isEmpty()
            val totalBytes = clips.sumOf { it.sizeBytes }
            val lockedCount = clips.count { it.isLocked }
            val summary = getString(
                R.string.gallery_summary_format,
                clips.size,
                Formatters.size(totalBytes)
            )
            binding.tvSummary.text = if (lockedCount > 0) {
                summary + " · " + getString(R.string.gallery_summary_locked, lockedCount)
            } else {
                summary
            }
        }
    }

    private fun playClip(clip: Clip) {
        val dialogBinding = DialogPlayerBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialogBinding.tvPlayerTitle.text = getString(
            R.string.gallery_player_title,
            clip.displayName.removePrefix(ClipStorage.LOCKED_PREFIX)
        )

        val videoView = dialogBinding.videoView
        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setVideoURI(clip.uri)
        videoView.setOnPreparedListener { player ->
            player.setLooping(false)
            videoView.start()
        }
        videoView.setOnErrorListener { _, what, _ ->
            toast("재생할 수 없습니다 (code " + what + ")")
            true
        }
        dialogBinding.btnClosePlayer.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { runCatching { videoView.stopPlayback() } }
        dialog.show()
    }

    private fun toggleLock(clip: Clip) {
        val target = !clip.isLocked
        clipStorage.setLocked(clip, target) { success ->
            if (isFinishing || isDestroyed) return@setLocked
            when {
                !success -> toast("잠금 상태를 바꾸지 못했습니다.")
                target -> toast("잠금 처리했습니다. 용량 정리에서 제외됩니다.")
                else -> toast("잠금을 해제했습니다.")
            }
            loadClips()
        }
    }

    private fun confirmDelete(clip: Clip) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, clip.displayName))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteClip(clip) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteClip(clip: Clip) {
        clipStorage.deleteClip(clip) { success ->
            if (isFinishing || isDestroyed) return@deleteClip
            toast(if (success) getString(R.string.toast_delete_done) else "삭제하지 못했습니다.")
            loadClips()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
