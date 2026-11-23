package com.pickle.blackbox.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.gms.ads.AdView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pickle.blackbox.BlackboxApp
import com.pickle.blackbox.R
import com.pickle.blackbox.ads.AdManager
import com.pickle.blackbox.databinding.ActivityMainBinding
import com.pickle.blackbox.databinding.DialogLanguageBinding
import com.pickle.blackbox.databinding.DialogSettingsBinding
import com.pickle.blackbox.media.Clip
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.service.RecordingService
import com.pickle.blackbox.settings.AppSettings
import com.pickle.blackbox.util.AppLanguage
import com.pickle.blackbox.util.Formatters
import java.util.Locale

/**
 * Dashcam main screen: live preview + HUD, record/stop, lens switch, manual lock,
 * gallery entry point and settings. Ads: bottom banner + interstitial on stop/settings.
 *
 * The camera and the recording itself live in [RecordingService]; this screen only
 * drives it and shows its state, so leaving the app never interrupts a recording.
 */
class MainActivity : AppCompatActivity(), RecordingService.UiListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: BlackboxApp
    private lateinit var settings: AppSettings
    private lateinit var clipStorage: ClipStorage
    private lateinit var adManager: AdManager

    private val handler = Handler(Looper.getMainLooper())
    private var service: RecordingService? = null
    private var bound = false
    private var lastSpeedKmh = -1f
    private var isRecordingUi = false
    private var showAdAfterStop = false

    /** This screen's bottom banner; paused while the banner is hidden. */
    private var bannerAdView: AdView? = null
    private var bannerHidden = false

    /** Open first-launch language dialog, if any (kept so a choice can close it). */
    private var languageDialog: AlertDialog? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
            binding.permissionPanel.isVisible = !cameraGranted
            if (cameraGranted) connectToService()
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? RecordingService.LocalBinder ?: return
            val recordingService = localBinder.service
            service = recordingService
            recordingService.setPreviewTarget(binding.previewView.surfaceProvider)
            recordingService.attachUi(this@MainActivity)
            applyRotationToService()
            syncWithService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as BlackboxApp
        settings = app.settings
        clipStorage = app.clipStorage
        adManager = app.adManager
        // Re-applies the stored language (a no-op when the app already started with it).
        AppLanguage.apply(settings.languageTag)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireUi()
        bannerAdView = adManager.createBanner(binding.adContainer)
        adManager.preloadInterstitial()

        binding.tvSegment.text = getString(R.string.hud_segment_format, settings.segmentMinutes)
        binding.tvDateTime.text = Formatters.nowDateTime()
        if (settings.hasSelectedLanguage) {
            ensurePermissions()
        } else {
            // First launch: choose the language first, then ask for permissions.
            showLanguagePicker()
        }
        updateStorageInfo()
        updateAdBanner()
    }

    private fun wireUi() {
        binding.btnRecord.setOnClickListener {
            if (isRecordingUi) stopRecording(showAd = true) else startRecording()
        }

        binding.btnSwitchCamera.setOnClickListener {
            // Allowed while recording too: the service finalizes the current segment,
            // rebinds the other lens and continues recording without ending the session.
            val recordingService = service
            if (recordingService == null) {
                connectToService()
                return@setOnClickListener
            }
            recordingService.switchCamera()
        }

        binding.btnLock.setOnClickListener { lockCurrentOrLatest() }

        binding.btnGallery.setOnClickListener {
            adManager.showInterstitial(this) { openGallery() }
        }

        binding.btnSettings.setOnClickListener {
            adManager.showInterstitial(this) { showSettingsDialog() }
        }

        binding.btnPermissionRetry.setOnClickListener { ensurePermissions() }
    }

    // ------------------------------------------------------------ permissions

    private fun ensurePermissions() {
        val missing = requiredPermissions().filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            binding.permissionPanel.isVisible = false
            connectToService()
            return
        }
        // Ask on first launch and on retry; the system decides whether to show a dialog.
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ----------------------------------------------------------------- service

    private fun connectToService() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            binding.permissionPanel.isVisible = true
            return
        }
        if (!bound) {
            bound = bindService(
                Intent(this, RecordingService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    private fun syncWithService() {
        val recordingService = service ?: return
        isRecordingUi = recordingService.isRecording
        applyRecordingUi(isRecordingUi)
        binding.tvLens.text = getString(
            if (recordingService.isFrontCamera) R.string.hud_lens_front else R.string.hud_lens_back
        )
    }

    private fun startRecording() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            ensurePermissions()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toast(getString(R.string.toast_no_mic_permission))
        }
        binding.tvLockBadge.isVisible = false
        showAdAfterStop = false
        connectToService()
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        )
    }

    private fun stopRecording(showAd: Boolean) {
        showAdAfterStop = showAd
        val recordingService = service
        if (recordingService != null && recordingService.isRecording) {
            recordingService.stopRecording()
        } else {
            startService(
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
            )
        }
    }

    // --------------------------------------------------- recording callbacks

    override fun onCameraReady(preview: Preview, isFrontCamera: Boolean) {
        binding.tvLens.text =
            getString(if (isFrontCamera) R.string.hud_lens_front else R.string.hud_lens_back)
    }

    override fun onLensChanged(isFrontCamera: Boolean) {
        binding.tvLens.text =
            getString(if (isFrontCamera) R.string.hud_lens_front else R.string.hud_lens_back)
    }

    override fun onRecordingStarted() {
        isRecordingUi = true
        applyRecordingUi(true)
    }

    override fun onRecordingStopped() {
        isRecordingUi = false
        applyRecordingUi(false)
        binding.tvTimer.text = getString(R.string.hud_timer_placeholder)
        binding.tvLockBadge.isVisible = false
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

    override fun onImpact(gForce: Float, locked: Boolean) {
        val gText = String.format(Locale.US, "%.1fG", gForce)
        if (locked) {
            binding.tvLockBadge.isVisible = true
            binding.tvLockBadge.setText(R.string.hud_locked_badge)
            toast(getString(R.string.toast_impact_detected, gText))
        } else {
            toast(getString(R.string.toast_impact_only, gText))
        }
    }

    override fun onError(message: String) {
        toast(message)
    }

    private fun applyRecordingUi(recording: Boolean) {
        binding.previewView.keepScreenOn = recording
        binding.btnRecord.setImageResource(
            if (recording) R.drawable.ic_stop else R.drawable.ic_record
        )
        binding.btnRecord.contentDescription = getString(
            if (recording) R.string.action_stop_recording else R.string.action_start_recording
        )
        binding.recDot.setBackgroundResource(
            if (recording) R.drawable.bg_rec_dot_active else R.drawable.bg_rec_dot_idle
        )
        binding.tvRecordState.setText(if (recording) R.string.hud_recording else R.string.hud_idle)
        binding.tvRecordState.setTextColor(
            ContextCompat.getColor(this, if (recording) R.color.bb_primary else R.color.bb_text_dim)
        )
        binding.tvSegment.text = getString(R.string.hud_segment_format, settings.segmentMinutes)
        updateAdBanner()
    }

    // ------------------------------------------------- first-launch language

    /**
     * Shown once per install: the user picks the app language right away instead of
     * having to find Settings first. Every row is a full-width, 56dp tall button so it is
     * easy to tap.
     */
    private fun showLanguagePicker() {
        val dialogBinding = DialogLanguageBinding.inflate(layoutInflater)
        val density = resources.displayMetrics.density

        AppLanguage.firstRunOptions(this).forEach { (tag, label) ->
            val option = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                isAllCaps = false
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = (56 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
                setOnClickListener { selectLanguage(tag) }
            }
            dialogBinding.languageList.addView(option)
        }

        languageDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_select_title)
            .setView(dialogBinding.root)
            .setOnCancelListener {
                // Back press: keep the device language, do not ask again, continue to permissions.
                settings.languageTag = AppLanguage.SYSTEM
                settings.hasSelectedLanguage = true
                ensurePermissions()
            }
            .create()
            .also { it.show() }
    }

    private fun selectLanguage(tag: String) {
        settings.languageTag = tag
        settings.hasSelectedLanguage = true
        languageDialog?.dismiss()
        languageDialog = null
        // Applies to every screen / dialog / toast / the recording notification. When the
        // locale actually changes this recreates the activity stack with the new language.
        AppLanguage.apply(tag)
        service?.refreshNotification()
        // Choosing "system" keeps the current locale, so this screen survives and has to
        // continue to the permission step itself; any real language change recreates the
        // activity, whose onCreate asks for permissions.
        if (tag == AppLanguage.SYSTEM) ensurePermissions()
    }

    // ---------------------------------------------------------------- actions

    private fun lockCurrentOrLatest() {
        if (service?.lockCurrentSegment() == true) {
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
                toast(getString(R.string.toast_lock_latest, clip.displayName.removePrefix(ClipStorage.LOCKED_PREFIX)))
            }
            updateStorageInfo()
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

        val languageOptions = AppLanguage.options(this)
        val languageTags = languageOptions.map { it.first }
        dialogBinding.spLanguage.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageOptions.map { it.second }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        dialogBinding.spLanguage.setSelection(
            languageTags.indexOf(settings.languageTag).coerceAtLeast(0)
        )

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

                val selectedTag = languageTags.getOrElse(dialogBinding.spLanguage.selectedItemPosition) { "" }
                val languageChanged = selectedTag != settings.languageTag
                settings.languageTag = selectedTag
                if (languageChanged) {
                    // Recreates the activity chain with the new locale.
                    AppLanguage.apply(selectedTag)
                    service?.refreshNotification()
                } else {
                    toast(getString(R.string.toast_settings_saved))
                }
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

            val recordingService = service
            if (isRecordingUi && recordingService != null) {
                binding.tvTimer.text = Formatters.clock(recordingService.sessionElapsedMs)
                binding.tvSegment.text =
                    getString(R.string.hud_segment_format, settings.segmentMinutes) +
                    " · " + Formatters.duration(recordingService.segmentRemainingMs)
            }

            lastSpeedKmh = recordingService?.speedKmh ?: -1f
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
        updateStorageInfo()
        connectToService()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        // The service keeps the camera and the recording; the screen only detaches.
        service?.detachUi(this)
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        service = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The screen was rotated: keep the preview and the next recorded segment in step.
        applyRotationToService()
        // Rotating in or out of landscape while recording shows/hides the banner right away.
        updateAdBanner()
    }

    // -------------------------------------------------------------------- ads

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * In landscape the banner would sit over the camera preview and the controls, so it is
     * hidden while recording. It comes back when recording stops or the screen turns upright.
     */
    private fun updateAdBanner() {
        val hide = isRecordingUi && isLandscape
        if (hide == bannerHidden) return
        bannerHidden = hide
        binding.adContainer.isVisible = !hide
        if (hide) bannerAdView?.pause() else bannerAdView?.resume()
    }

    private fun applyRotationToService() {
        val rotation = binding.root.display?.rotation ?: Surface.ROTATION_0
        service?.setTargetRotation(rotation)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
