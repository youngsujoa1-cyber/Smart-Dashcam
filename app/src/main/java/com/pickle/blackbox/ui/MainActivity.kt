package com.pickle.blackbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pickle.blackbox.BlackboxApp
import com.pickle.blackbox.R
import com.pickle.blackbox.ads.AdManager
import com.pickle.blackbox.camera.CameraController
import com.pickle.blackbox.camera.RecordingController
import com.pickle.blackbox.databinding.ActivityMainBinding
import com.pickle.blackbox.databinding.DialogSettingsBinding
import com.pickle.blackbox.location.SpeedTracker
import com.pickle.blackbox.media.Clip
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.sensor.ImpactDetector
import com.pickle.blackbox.settings.AppSettings
import com.pickle.blackbox.util.Formatters
import java.util.Locale

/**
 * Dashcam main screen: live preview + HUD, record/stop, lens switch, manual lock,
 * gallery entry point and settings. Ads: bottom banner + interstitial on stop/settings.
 */
class MainActivity : AppCompatActivity(), RecordingController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: BlackboxApp
    private lateinit var settings: AppSettings
    private lateinit var clipStorage: ClipStorage
    private lateinit var adManager: AdManager

    private lateinit var cameraController: CameraController
    private lateinit var recordingController: RecordingController
    private lateinit var impactDetector: ImpactDetector
    private lateinit var speedTracker: SpeedTracker

    private val handler = Handler(Looper.getMainLooper())
    private var sessionStartElapsed = 0L
    private var lastSpeedKmh = -1f
    private var isRecordingUi = false
    private var showAdAfterStop = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
            binding.permissionPanel.isVisible = !cameraGranted
            if (cameraGranted) startCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = applicationContext as BlackboxApp
        settings = app.settings
        clipStorage = app.clipStorage
        adManager = app.adManager

        recordingController = RecordingController(this, settings, clipStorage)
        recordingController.listener = this

        cameraController = CameraController(this, this, binding.previewView).apply {
            onReady = { capture -> recordingController.attach(capture) }
            onError = { message -> toast(message) }
            onLensChanged = { isFront ->
                binding.tvLens.text =
                    getString(if (isFront) R.string.hud_lens_front else R.string.hud_lens_back)
            }
        }

        impactDetector = ImpactDetector(this, { settings.impactThresholdG }) { g -> onImpactDetected(g) }
        speedTracker = SpeedTracker(this) { kmh -> lastSpeedKmh = kmh }

        wireUi()
        adManager.createBanner(binding.adContainer)
        adManager.preloadInterstitial()

        binding.tvSegment.text = getString(R.string.hud_segment_format, settings.segmentMinutes)
        binding.tvDateTime.text = Formatters.nowDateTime()
        ensurePermissions()
        updateStorageInfo()
    }

    private fun wireUi() {
        binding.btnRecord.setOnClickListener {
            if (isRecordingUi) stopRecording(showAd = true) else startRecording()
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (isRecordingUi) {
                toast(getString(R.string.toast_switch_while_recording))
                return@setOnClickListener
            }
            if (!cameraController.switchCamera()) {
                toast(getString(R.string.toast_camera_unavailable))
            }
        }

        binding.btnLock.setOnClickListener { lockCurrentOrLatest() }

        binding.btnGallery.setOnClickListener {
            if (recordingController.isRecording) stopRecording(showAd = false)
            adManager.showInterstitial(this) { openGallery() }
        }

        binding.btnSettings.setOnClickListener {
            if (recordingController.isRecording) stopRecording(showAd = false)
            adManager.showInterstitial(this) { showSettingsDialog() }
        }

        binding.btnPermissionRetry.setOnClickListener { ensurePermissions() }
    }

    // ------------------------------------------------------------ permissions

    private fun ensurePermissions() {
        val missing = requiredPermissions().filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            binding.permissionPanel.isVisible = false
            startCamera()
            return
        }
        // Ask on first launch and on retry; the system decides whether to show a dialog.
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ----------------------------------------------------------------- camera

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            binding.permissionPanel.isVisible = true
            return
        }
        cameraController.start()
        speedTracker.start()
    }

    private fun startRecording() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            ensurePermissions()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toast("마이크 권한이 없어 무음으로 녹화합니다.")
        }
        speedTracker.start()
        binding.tvLockBadge.isVisible = false
        showAdAfterStop = false
        recordingController.start()
    }

    private fun stopRecording(showAd: Boolean) {
        if (!recordingController.isRecording) return
        showAdAfterStop = showAd
        recordingController.stop()
    }

    // --------------------------------------------------- recording callbacks

    override fun onRecordingStarted(segmentIndex: Int) {
        if (segmentIndex <= 1) {
            sessionStartElapsed = SystemClock.elapsedRealtime()
        }
        isRecordingUi = true
        binding.previewView.keepScreenOn = true
        binding.btnRecord.setImageResource(R.drawable.ic_stop)
        binding.btnRecord.contentDescription = getString(R.string.action_stop_recording)
        binding.recDot.setBackgroundResource(R.drawable.bg_rec_dot_active)
        binding.tvRecordState.setText(R.string.hud_recording)
        binding.tvRecordState.setTextColor(ContextCompat.getColor(this, R.color.bb_primary))
        binding.tvSegment.text = getString(R.string.hud_segment_format, settings.segmentMinutes)
        if (!impactDetector.isRunning) impactDetector.start()
    }

    override fun onRecordingStopped() {
        isRecordingUi = false
        binding.previewView.keepScreenOn = false
        binding.btnRecord.setImageResource(R.drawable.ic_record)
        binding.btnRecord.contentDescription = getString(R.string.action_start_recording)
        binding.recDot.setBackgroundResource(R.drawable.bg_rec_dot_idle)
        binding.tvRecordState.setText(R.string.hud_idle)
        binding.tvRecordState.setTextColor(ContextCompat.getColor(this, R.color.bb_text_dim))
        binding.tvTimer.text = getString(R.string.hud_timer_placeholder)
        binding.tvLockBadge.isVisible = false
        impactDetector.stop()
        updateStorageInfo()

        if (showAdAfterStop && !isFinishing && !isDestroyed) {
            showAdAfterStop = false
            adManager.showInterstitial(this) { }
        }
    }

    override fun onSegmentSaved(clip: Clip?) {
        if (clip == null) {
            toast(getString(R.string.toast_save_failed))
        } else if (clip.isLocked) {
            // Normal segments are saved silently so the HUD does not get noisy;
            // locked segments deserve an explicit confirmation.
            toast(getString(R.string.toast_saved_locked, clip.displayName.removePrefix(ClipStorage.LOCKED_PREFIX)))
        }
        updateStorageInfo()
    }

    override fun onSegmentLocked() {
        binding.tvLockBadge.isVisible = true
        binding.tvLockBadge.setText(R.string.hud_locked_badge)
    }

    override fun onError(message: String) {
        toast(message)
    }

    // ---------------------------------------------------------------- actions

    private fun lockCurrentOrLatest() {
        if (recordingController.lockCurrentSegment()) {
            binding.tvLockBadge.isVisible = true
            binding.tvLockBadge.setText(R.string.hud_locked_badge)
            toast(getString(R.string.toast_lock_scheduled))
            return
        }
        clipStorage.lockLatestClip { clip ->
            if (isFinishing || isDestroyed) return@lockLatestClip
            if (clip == null) {
                toast(getString(R.string.toast_nothing_to_lock))
            } else {
                toast("최근 영상 잠금: " + clip.displayName.removePrefix(ClipStorage.LOCKED_PREFIX))
            }
            updateStorageInfo()
        }
    }

    private fun onImpactDetected(gForce: Float) {
        if (!isRecordingUi) return
        val gText = String.format(Locale.US, "%.1fG", gForce)
        if (settings.autoLockOnImpact && recordingController.lockCurrentSegment()) {
            binding.tvLockBadge.isVisible = true
            binding.tvLockBadge.setText(R.string.hud_locked_badge)
            toast(getString(R.string.toast_impact_detected, gText))
        } else {
            toast(getString(R.string.toast_impact_only, gText))
        }
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    // --------------------------------------------------------------- settings

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)

        when (settings.segmentMinutes) {
            1 -> dialogBinding.rgSegment.check(dialogBinding.rbSegment1.id)
            5 -> dialogBinding.rgSegment.check(dialogBinding.rbSegment5.id)
            else -> dialogBinding.rgSegment.check(dialogBinding.rbSegment3.id)
        }
        when (settings.storageLimitGb) {
            1 -> dialogBinding.rgStorage.check(dialogBinding.rbStorage1.id)
            5 -> dialogBinding.rgStorage.check(dialogBinding.rbStorage5.id)
            else -> dialogBinding.rgStorage.check(dialogBinding.rbStorage2.id)
        }
        dialogBinding.sbSensitivity.progress = settings.impactSensitivity
        updateSensitivityLabel(dialogBinding)
        dialogBinding.sbSensitivity.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                    updateSensitivityLabel(dialogBinding)

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        dialogBinding.swAutoLock.isChecked = settings.autoLockOnImpact
        dialogBinding.swAudio.isChecked = settings.recordAudio
        dialogBinding.swSpeed.isChecked = settings.showSpeed

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val minutes = when (dialogBinding.rgSegment.checkedRadioButtonId) {
                    R.id.rbSegment1 -> 1
                    R.id.rbSegment5 -> 5
                    else -> 3
                }
                val limitGb = when (dialogBinding.rgStorage.checkedRadioButtonId) {
                    R.id.rbStorage1 -> 1
                    R.id.rbStorage5 -> 5
                    else -> 2
                }
                settings.segmentMinutes = minutes
                settings.storageLimitGb = limitGb
                settings.impactSensitivity = dialogBinding.sbSensitivity.progress.coerceIn(1, 5)
                settings.autoLockOnImpact = dialogBinding.swAutoLock.isChecked
                settings.recordAudio = dialogBinding.swAudio.isChecked
                settings.showSpeed = dialogBinding.swSpeed.isChecked

                binding.tvSegment.text = getString(R.string.hud_segment_format, minutes)
                updateStorageInfo()
                toast(getString(R.string.toast_settings_saved))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateSensitivityLabel(dialogBinding: DialogSettingsBinding) {
        val level = dialogBinding.sbSensitivity.progress.coerceIn(1, 5)
        dialogBinding.tvSensitivityValue.text = getString(
            R.string.settings_sensitivity_value,
            level,
            String.format(Locale.US, "%.1fG", AppSettings.thresholdFor(level))
        )
    }

    // -------------------------------------------------------------------- HUD

    private val ticker = object : Runnable {
        override fun run() {
            binding.tvDateTime.text = Formatters.nowDateTime()

            if (isRecordingUi) {
                val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsed
                binding.tvTimer.text = Formatters.clock(elapsed)
                val remaining = settings.segmentMillis - (elapsed % settings.segmentMillis)
                binding.tvSegment.text =
                    getString(R.string.hud_segment_format, settings.segmentMinutes) +
                    " · " + Formatters.duration(remaining)
            }

            binding.tvSpeed.isVisible = settings.showSpeed
            if (settings.showSpeed) {
                binding.tvSpeed.text = if (lastSpeedKmh < 0f) {
                    getString(R.string.hud_speed_unknown)
                } else {
                    getString(R.string.hud_speed_format, lastSpeedKmh.toInt())
                }
            }

            handler.postDelayed(this, 500L)
        }
    }

    private fun updateStorageInfo() {
        clipStorage.computeUsage { usage ->
            if (isFinishing || isDestroyed) return@computeUsage
            binding.tvStorage.text = getString(
                R.string.hud_storage_format,
                Formatters.size(usage.usedBytes),
                settings.storageLimitGb.toString() + "GB"
            )
        }
    }

    // --------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
        speedTracker.start()
        updateStorageInfo()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        speedTracker.stop()
        // CameraX unbinds when the activity stops, so finalize the segment explicitly.
        if (recordingController.isRecording) stopRecording(showAd = false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        impactDetector.stop()
        recordingController.release()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
