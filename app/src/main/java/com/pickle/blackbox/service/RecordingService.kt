package com.pickle.blackbox.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.pickle.blackbox.BlackboxApp
import com.pickle.blackbox.R
import com.pickle.blackbox.camera.CameraController
import com.pickle.blackbox.camera.RecordingController
import com.pickle.blackbox.location.SpeedTracker
import com.pickle.blackbox.media.Clip
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.settings.AppSettings
import com.pickle.blackbox.ui.MainActivity
import kotlin.math.abs

/**
 * Runs the dashcam in the background.
 *
 * The CameraX use cases (preview + video capture) are bound to this service's own
 * lifecycle instead of an activity, so recording keeps going when the user presses
 * Home, switches apps or turns the screen off, and continues when the activity comes
 * back. While recording, the service is a started foreground service with an ongoing
 * notification ("RoadEyeCam 녹화 중"); without a client and without a recording it
 * shuts itself down and releases the camera.
 *
 * While a recording is active the service holds a [PowerManager.PARTIAL_WAKE_LOCK]: with the
 * screen off the CPU would otherwise be throttled or suspended between frames, which stops
 * the encoder from receiving frames even though the foreground service keeps running.
 */
class RecordingService : Service(), LifecycleOwner {

    /** Callbacks for the screen that is currently attached (the main activity). */
    interface UiListener {
        fun onCameraReady(preview: Preview, isFrontCamera: Boolean)
        fun onLensChanged(isFrontCamera: Boolean)
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onSegmentSaved(clip: Clip?)
        fun onSegmentLocked()
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        val service: RecordingService
            get() = this@RecordingService
    }

    private val binder = LocalBinder()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var settings: AppSettings
    private lateinit var clipStorage: ClipStorage
    private lateinit var cameraController: CameraController
    private lateinit var recordingController: RecordingController
    private lateinit var speedTracker: SpeedTracker
    private var wakeLock: PowerManager.WakeLock? = null

    private var uiListener: UiListener? = null
    private var wantRecording = false
    private var inForeground = false
    private var cameraReady = false
    private var sessionStartElapsed = 0L
    private var switchingCamera = false

    /** A rotation rebind is on its way: the running segment was finalized, the loop is paused. */
    private var rebindingForRotation = false

    /** Rotation reported by the screen the activity is on (stale when auto-rotate is off). */
    private var displayRotation: Int = Surface.ROTATION_0

    /**
     * True while the display, which is lying in landscape, is trusted over a sensor reading
     * that still says portrait. Only a display that turned landscape grants this (see
     * [updateDisplayRotation]); it is dropped as soon as the display goes back to portrait.
     */
    private var trustDisplayLandscape: Boolean = false

    /** Landscape direction used by the 가로 고정 mode ([Surface.ROTATION_90] / [Surface.ROTATION_270]). */
    private var landscapeSide: Int = Surface.ROTATION_90

    /**
     * True only while the 녹화 방향 setting is being applied from the settings dialog. That is a
     * deliberate act, so it may split the running segment even in 가로 고정 mode.
     */
    private var orientationChangeIsExplicit: Boolean = false

    /** Rotation the phone is really held in, from the accelerometer; null while unknown/flat. */
    private var sensorRotation: Int? = null

    /** Last raw angle reported by the accelerometer, kept even when it fits no quadrant. */
    private var lastOrientationDegrees: Int? = null

    /** Rotation every capture actually uses ([sensorRotation] when known, else [displayRotation]). */
    private var targetRotation: Int = Surface.ROTATION_0

    /** Last GPS speed in km/h, -1 when unknown. */
    var speedKmh: Float = -1f
        private set

    val isRecording: Boolean
        get() = ::recordingController.isInitialized && recordingController.isRecording

    val isFrontCamera: Boolean
        get() = ::cameraController.isInitialized && cameraController.isFrontCamera

    /** Elapsed time of the current recording session in milliseconds (0 when idle). */
    val sessionElapsedMs: Long
        get() = if (isRecording) SystemClock.elapsedRealtime() - sessionStartElapsed else 0L

    /** Milliseconds left until the current segment is rotated. */
    val segmentRemainingMs: Long
        get() {
            if (!isRecording) return 0L
            val length = settings.segmentMillis
            return length - (sessionElapsedMs % length)
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val app = application as BlackboxApp
        settings = app.settings
        clipStorage = app.clipStorage

        // Start from what the screen says instead of the ROTATION_0 default: the very first
        // bindUseCases() bakes this rotation into the Recorder, and a portrait session at
        // service start was exactly what kept the footage vertical. The accelerometer value
        // overrides it as soon as it fits a quadrant, and 가로 고정 starts landscape at once.
        displayRotation = currentDisplayRotation()
        trustDisplayLandscape = isLandscape(displayRotation)
        targetRotation = effectiveRotation()

        createNotificationChannel()
        registerDisplayTracking()
        ensureCamera()
        enableOrientationTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> stopRecording()
            ACTION_REFRESH_NOTIFICATION -> refreshNotification()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        uiListener = null
        // Keep running only while something is being recorded.
        if (!isRecording) stopSelf()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterDisplayTracking()
        disableOrientationTracking()
        cancelOrientationRestart()
        mainHandler.removeCallbacksAndMessages(null)
        if (::speedTracker.isInitialized) speedTracker.stop()
        if (::recordingController.isInitialized) recordingController.release()
        releaseWakeLock()
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        // Unbinds the camera use cases and frees the offscreen preview surface with them.
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        if (::cameraController.isInitialized) cameraController.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI bridge

    fun attachUi(listener: UiListener) {
        uiListener = listener
        listener.onLensChanged(isFrontCamera)
        cameraController.currentPreview()?.let { listener.onCameraReady(it, isFrontCamera) }
        if (isRecording) listener.onRecordingStarted()
    }

    fun detachUi(listener: UiListener) {
        if (uiListener === listener) uiListener = null
    }

    fun setPreviewTarget(provider: Preview.SurfaceProvider?) {
        if (::cameraController.isInitialized) cameraController.setPreviewSurfaceProvider(provider)
    }

    // --------------------------------------------------------------- recording API

    /** Records into the loop until [stopRecording] is called. Also makes the service foreground. */
    fun beginRecording() {
        ensureCamera()
        ensureForeground(getString(R.string.notification_preparing))
        acquireWakeLock()
        wantRecording = true
        switchingCamera = false
        rebindingForRotation = false
        cancelOrientationRestart()
        // A phone that is already lying sideways must record landscape from second 0.
        refreshOrientationNow()
        if (cameraReady) startSegmentsIfNeeded()
    }

    fun stopRecording() {
        cancelOrientationRestart()
        wantRecording = false
        if (isRecording) {
            recordingController.stop()
        } else {
            finishSession()
        }
    }

    /** Locks the segment that is being recorded right now. */
    fun lockCurrentSegment(): Boolean = isRecording && recordingController.lockCurrentSegment()

    /**
     * Reports the rotation of the screen (from `Display.rotation`), sent by the activity.
     * The service also follows the display on its own through [registerDisplayTracking], so this
     * is only what the attached screen sees; both paths land in [updateDisplayRotation].
     */
    fun setDisplayRotation(rotation: Int) = updateDisplayRotation(rotation)

    /**
     * Adopts a new display rotation.
     *
     * The sensor value from [enableOrientationTracking] normally wins, because the display is
     * often locked to portrait (auto-rotate off) or stale while the phone really lies sideways.
     * A display that *turns* landscape is the exception: auto-rotate, or a navigation app that
     * rotated the screen itself, says more than a sensor reading that is still portrait (a
     * steeply tilted car mount, or a callback that has not come yet). That reading is therefore
     * trusted until the display leaves landscape again, while a display locked at ROTATION_0
     * never overrides the sensor — the phone turned in a portrait mount still records landscape.
     */
    private fun updateDisplayRotation(rotation: Int) {
        if (rotation == displayRotation) return
        displayRotation = rotation
        trustDisplayLandscape = isLandscape(rotation)
        applyEffectiveRotation()
    }

    /** Keeps the captured video oriented the same way as the phone is really held. */
    private fun applyEffectiveRotation() {
        val rotation = effectiveRotation()
        if (rotation == targetRotation) return
        targetRotation = rotation
        if (!::cameraController.isInitialized) return
        // Turns the preview right away. The video capture only takes the new rotation for its
        // next segment once the session is rebuilt (see CameraController.rebind): on Samsung
        // devices the running Recorder keeps the encoder orientation it was bound with.
        cameraController.setTargetRotation(rotation)
        if (rebindingForRotation) {
            // The rebind that is already on its way builds with this targetRotation anyway.
            return
        }
        if (switchingCamera) {
            // Likewise: the pending lens switch rebuilds the session with the new rotation.
            return
        }
        if (isRecording) {
            // Turning the phone in the middle of a segment splits it after a short debounce, so the
            // footage keeps the orientation it actually had (instead of staying portrait for the
            // whole 3-5 minute segment). The split is followed by a rebind.
            // In 가로 고정 mode the orientation is pinned, so a reading from the sensor changes
            // nothing the user asked for: the segment is never split for it and the loop keeps
            // splitting only on the chosen 3/5 minute length.
            if (settings.landscapeLocked && !orientationChangeIsExplicit) return
            scheduleOrientationRestart()
            return
        }
        if (cameraReady && recordingController.isCaptureIdle) {
            // Nothing is recording: rebuild now, so the next segment starts on a Recorder that was
            // created with the new rotation instead of the one from service start.
            rebindForRotation()
        }
    }

    /**
     * Rotation the next segment is recorded in.
     *
     * With 녹화 방향 = 가로 고정 it is always one of the two landscape rotations, so the footage is
     * 16:9 whether the phone is held upright, laid in a landscape mount, or the app was started
     * in portrait. Otherwise the sensor wins, with a display that turned landscape beating a
     * portrait sensor reading (see [updateDisplayRotation]).
     */
    private fun effectiveRotation(): Int {
        if (settings.landscapeLocked) return landscapeRotation()
        val sensor = sensorRotation ?: return displayRotation
        if (isLandscape(displayRotation) && isPortrait(sensor) && trustDisplayLandscape) {
            return displayRotation
        }
        return sensor
    }

    /**
     * The landscape rotation the 가로 고정 mode records in: the side the phone is lying on
     * ([Surface.ROTATION_270] when that is what the sensor — or a landscape display — reports,
     * else [Surface.ROTATION_90]). A portrait reading never changes the side, so lifting the
     * phone out of its mount does not flip the footage upside down.
     */
    private fun landscapeRotation(): Int {
        val observed = when {
            sensorRotation == Surface.ROTATION_90 || sensorRotation == Surface.ROTATION_270 -> sensorRotation
            isLandscape(displayRotation) -> displayRotation
            else -> null
        }
        if (observed != null) landscapeSide = observed
        return landscapeSide
    }

    /**
     * Re-applies the 녹화 방향 setting after it was changed while recording: the running segment is
     * split for the new orientation and the session is rebuilt, exactly like a real rotation. This
     * is the one orientation change that also splits in 가로 고정 mode, because the user asked for it.
     */
    fun refreshOrientation() {
        orientationChangeIsExplicit = true
        try {
            refreshOrientationNow()
        } finally {
            orientationChangeIsExplicit = false
        }
    }

    /**
     * Applies the rotation the phone is held in right now.
     *
     * The accelerometer callback may not have arrived yet when recording starts (or the phone
     * was lying at an angle that fits no quadrant), so the last raw angle is mapped here as
     * well: if the phone is already horizontal, the very first segment is recorded horizontal.
     */
    private fun refreshOrientationNow() {
        if (sensorRotation == null) {
            val degrees = lastOrientationDegrees
            if (degrees != null) rotationForOrientation(degrees)?.let { sensorRotation = it }
        }
        applyEffectiveRotation()
    }

    // ------------------------------------------------------ rotation segment split

    /**
     * Schedules a segment split for the new orientation. A rotation that only passes through
     * (the phone is tilted and put back) is absorbed: every new change restarts the timer, and
     * the split is skipped when the running segment already has the target orientation.
     */
    private fun scheduleOrientationRestart() {
        mainHandler.removeCallbacks(orientationRestart)
        mainHandler.postDelayed(orientationRestart, ORIENTATION_RESTART_DEBOUNCE_MS)
    }

    private fun cancelOrientationRestart() {
        mainHandler.removeCallbacks(orientationRestart)
    }

    private val orientationRestart = Runnable {
        if (switchingCamera || rebindingForRotation) return@Runnable
        if (isRecording) {
            // Tilting back within the debounce window leaves the running segment alone.
            if (recordingController.currentSegmentRotation == targetRotation) return@Runnable
            rebindingForRotation = true
            // Finalize the running segment first, then rebuild the session with the new rotation
            // and continue the loop (onPausedForSwitch -> rebindForRotation -> resume).
            recordingController.pauseForRebind()
            return@Runnable
        }
        if (cameraReady && recordingController.isCaptureIdle &&
            cameraController.isBound && cameraController.boundRotation != targetRotation
        ) {
            // The immediate rebind in applyEffectiveRotation had to wait for a segment that was
            // still finalizing back then; catch up now that the capture is free.
            rebindForRotation()
        }
    }

    // ------------------------------------------------------- display rotation

    private val displayManager: DisplayManager?
        get() = getSystemService(DisplayManager::class.java)

    /**
     * Follows `Display.rotation` even while the activity is gone.
     *
     * The screen used to report its rotation only through [setDisplayRotation] from the activity,
     * so a rotation that happened in the background — the phone turned in its mount with
     * auto-rotate on, or a navigation app that put the screen in landscape — was never seen and
     * the footage stayed portrait until the app was opened again.
     */
    private fun registerDisplayTracking() {
        runCatching {
            displayManager?.registerDisplayListener(displayListener, mainHandler)
        }.onFailure { Log.w(TAG, "Could not register the display listener", it) }
    }

    private fun unregisterDisplayTracking() {
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
            .onFailure { Log.w(TAG, "Could not unregister the display listener", it) }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            // Fires for any property of the display (brightness, refresh rate); only the
            // rotation is interesting, and only of the screen the phone is using.
            if (displayId != Display.DEFAULT_DISPLAY) return
            updateDisplayRotation(currentDisplayRotation())
        }
    }

    // ----------------------------------------------------- device orientation

    /**
     * Follows how the phone is physically held, independent of the screen rotation.
     *
     * With auto-rotate off (common in a car mount) or while another portrait-locked app is in
     * front, `Display.rotation` stays [Surface.ROTATION_0] although the phone lies sideways, and
     * the recorded video came out portrait. The accelerometer reports the real angle, so the
     * rotation of both the preview and of every new segment follows the phone itself.
     */
    private fun enableOrientationTracking() {
        runCatching { orientationListener.enable() }
            .onFailure { Log.w(TAG, "Could not enable orientation tracking", it) }
    }

    private fun disableOrientationTracking() {
        runCatching { orientationListener.disable() }
            .onFailure { Log.w(TAG, "Could not disable orientation tracking", it) }
    }

    private val orientationListener: OrientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                lastOrientationDegrees = orientation
                // Only act when the phone is clearly inside one quadrant (±35°), so a phone
                // lying at an odd angle keeps the last good rotation instead of flickering. The
                // wide margin is for a dashboard / windshield mount, where the phone usually
                // sits a good 10-20° away from level.
                val rotation = rotationForOrientation(orientation) ?: return
                if (rotation == sensorRotation) return
                sensorRotation = rotation
                applyEffectiveRotation()
                // A running segment is split after the debounce and continues in this rotation.
            }
        }
    }

    /**
     * Switches front <-> rear. While recording, the running segment is finalized first and the
     * loop is resumed on the new lens, so the camera change never loses footage and the
     * session (timer, foreground service) keeps going.
     */
    fun switchCamera(): Boolean {
        if (!isRecording) {
            val switched = cameraController.switchCamera()
            if (!switched) uiListener?.onError(getString(R.string.toast_camera_unavailable))
            return switched
        }
        if (switchingCamera) return true
        if (!cameraController.canSwitchCamera()) {
            uiListener?.onError(getString(R.string.toast_camera_unavailable))
            return false
        }
        switchingCamera = true
        if (rebindingForRotation) {
            // A rotation rebind already finalized the segment and is on its way; the pending
            // onPausedForSwitch picks the switch up and the rebuild applies both at once.
            return true
        }
        recordingController.pauseForCameraSwitch()
        return true
    }

    /** Runs once the paused segment has been finalized and published. */
    private fun rebindForSwitch() {
        if (!switchingCamera) return
        val switched = runCatching { cameraController.switchCamera() }.getOrDefault(false)
        if (!switched) {
            switchingCamera = false
            uiListener?.onError(getString(R.string.toast_camera_unavailable))
            if (isRecording) {
                rebindingForRotation = false
                // A rotation change that was waiting for this rebuild still has to be applied.
                if (cameraController.isBound && cameraController.boundRotation != targetRotation) {
                    rebindForRotation()
                } else {
                    recordingController.resume()
                }
            }
        }
        // On success the new capture arrives through cameraController.onReady -> resume().
    }

    /**
     * Rebuilds the camera session with the current [targetRotation] (fresh Recorder, fresh
     * VideoCapture), so a segment recorded after the phone was turned is saved in the orientation
     * the phone is actually held in. A segment that is in flight has to be finalized first — see
     * the callers, which pause the loop (or only call this while nothing is recording).
     */
    private fun rebindForRotation() {
        if (!::cameraController.isInitialized || !cameraController.isBound) {
            // No session to rebuild (still starting / already released): leave the loop as it is.
            rebindingForRotation = false
            if (isRecording) recordingController.resume()
            return
        }
        rebindingForRotation = true
        val rebound = runCatching { cameraController.rebind() }.getOrDefault(false)
        if (rebound) return
        // Nothing is bound any more after a failed bind, so there is no capture to resume on.
        rebindingForRotation = false
        Log.w(TAG, "Rotation rebind failed; ending the segment loop")
        if (isRecording) recordingController.stop()
        // On success the new capture arrives through cameraController.onReady -> resume().
    }

    /** Rebuilds the ongoing notification with the current language / wording. */
    fun refreshNotification() {
        if (!inForeground) return
        updateNotification(getString(if (isRecording) R.string.notification_text else R.string.notification_preparing))
    }

    // -------------------------------------------------------------------- camera

    private fun ensureCamera() {
        if (::cameraController.isInitialized) return
        if (!hasPermission(Manifest.permission.CAMERA)) return

        recordingController = RecordingController(
            this,
            settings,
            clipStorage,
            rotationProvider = { targetRotation }
        ).apply {
            listener = recordingListener
        }
        cameraController = CameraController(this, this).apply {
            onReady = { preview, capture ->
                cameraReady = true
                recordingController.attach(capture)
                uiListener?.onCameraReady(preview, isFrontCamera)
                uiListener?.onLensChanged(isFrontCamera)
                when {
                    // This bind was a lens switch and/or a rotation rebind: continue the loop on
                    // the new session, or start it when the recording had not begun yet.
                    switchingCamera || rebindingForRotation -> {
                        switchingCamera = false
                        rebindingForRotation = false
                        if (isRecording) recordingController.resume() else startSegmentsIfNeeded()
                    }
                    wantRecording -> startSegmentsIfNeeded()
                }
            }
            onError = { message ->
                switchingCamera = false
                rebindingForRotation = false
                uiListener?.onError(message)
                // A failed bind leaves no capture: stop instead of recording into nothing.
                if (isRecording && !cameraController.isBound) recordingController.stop()
            }
            onLensChanged = { isFront -> uiListener?.onLensChanged(isFront) }
        }
        // The bind runs as soon as the provider is ready and bakes this rotation into the
        // Recorder; give it what we know now instead of CameraX's ROTATION_0 default.
        cameraController.setTargetRotation(targetRotation)
        speedTracker = SpeedTracker(this) { kmh -> speedKmh = kmh }
        speedTracker.start()

        cameraController.start()
    }

    private fun startSegmentsIfNeeded() {
        if (!wantRecording || recordingController.isRecording) return
        // A Recorder that was bound for another orientation keeps writing that orientation, so the
        // session is rebuilt first; onReady then comes back here with the fresh capture.
        if (cameraController.isBound && cameraController.boundRotation != targetRotation) {
            rebindForRotation()
            return
        }
        recordingController.start()
    }

    private val recordingListener = object : RecordingController.Listener {
        override fun onRecordingStarted(segmentIndex: Int) {
            if (segmentIndex <= 1) sessionStartElapsed = SystemClock.elapsedRealtime()
            updateNotification(getString(R.string.notification_text))
            uiListener?.onRecordingStarted()
        }

        override fun onRecordingStopped() {
            finishSession()
            uiListener?.onRecordingStopped()
        }

        override fun onSegmentSaved(clip: Clip?) {
            uiListener?.onSegmentSaved(clip)
            if (isRecording) updateNotification(getString(R.string.notification_text))
        }

        override fun onPausedForSwitch() {
            when {
                switchingCamera -> rebindForSwitch()
                rebindingForRotation -> rebindForRotation()
                else -> if (isRecording) recordingController.resume()
            }
        }

        override fun onSegmentLocked() {
            uiListener?.onSegmentLocked()
            if (inForeground) updateNotification(getString(R.string.notification_text_locked))
        }

        override fun onError(message: String) {
            uiListener?.onError(message)
        }
    }

    /** Called once a recording session has really ended (after the last segment was saved). */
    private fun finishSession() {
        cancelOrientationRestart()
        wantRecording = false
        switchingCamera = false
        rebindingForRotation = false
        releaseWakeLock()
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        if (uiListener == null) stopSelf()
    }

    // ---------------------------------------------------------------- wake lock

    /**
     * Keeps the CPU awake while recording. START/STOP_WAKE_LOCK is repeated on a timer shorter
     * than the safety timeout, so a long drive keeps its lock and a crashed session cannot
     * hold one forever.
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager == null) {
                Log.w(TAG, "No PowerManager: background recording may be throttled")
                return
            }
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RoadEyeCam:recording"
            ).apply { setReferenceCounted(false) }
        }
        try {
            // (Re)starts the timeout, both on the first acquire and on every renewal.
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            mainHandler.removeCallbacks(wakeLockRenewal)
            mainHandler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire the recording wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenewal)
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release the recording wake lock", t)
        }
    }

    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            if (wantRecording || isRecording) acquireWakeLock() else releaseWakeLock()
        }
    }

    // -------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground(text: String) {
        if (inForeground) {
            updateNotification(text)
            return
        }
        val types = foregroundServiceTypes()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), types)
            inForeground = true
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException / missing permission on API 34+
            android.util.Log.e(TAG, "startForeground failed", t)
            uiListener?.onError("백그라운드 녹화를 시작할 수 없습니다: " + (t.message ?: "알 수 없는 오류"))
        }
    }

    private fun updateNotification(text: String) {
        if (!inForeground) return
        // Android 13+ shows no notification without the runtime permission. The foreground
        // service keeps running anyway, so a missing grant is not treated as an error.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /** Only the service types the app can actually justify with granted permissions. */
    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Rotation of the default display, or ROTATION_0 when it cannot be read. */
    private fun currentDisplayRotation(): Int {
        val displayManager = getSystemService(DisplayManager::class.java) ?: return Surface.ROTATION_0
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return Surface.ROTATION_0
        return runCatching { display.rotation }.getOrDefault(Surface.ROTATION_0)
    }

    companion object {
        private const val TAG = "RecordingService"

        /** Degrees the phone may sit away from a quadrant before that quadrant counts. */
        private const val ORIENTATION_TOLERANCE_DEGREES = 35

        /**
         * How long a new orientation has to hold before the running segment is split for it.
         * Absorbs the angles of turning the phone and tilting it back in one motion, and lets a
         * phone that is carried or briefly tilted while driving keep one continuous segment
         * instead of being cut into 1-2 second fragments.
         */
        private const val ORIENTATION_RESTART_DEBOUNCE_MS = 4_500L

        /**
         * Maps [OrientationEventListener] degrees to `Surface.ROTATION_*`.
         *
         * `OrientationEventListener` counts the angle the device was rotated counter-clockwise
         * from its natural (portrait) position, while `Surface.ROTATION_*` counts the clockwise
         * rotation the display is turned from natural orientation — hence 90 <-> 270.
         *
         * @return null when the angle is not clearly within one quadrant.
         */
        private fun rotationForOrientation(orientation: Int): Int? {
            val candidates = intArrayOf(0, 90, 180, 270)
            var nearest = 0
            var nearestDistance = Int.MAX_VALUE
            for (degrees in candidates) {
                val raw = abs(orientation - degrees)
                val distance = if (raw > 180) 360 - raw else raw
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = degrees
                }
            }
            if (nearestDistance > ORIENTATION_TOLERANCE_DEGREES) return null
            return when (nearest) {
                0 -> Surface.ROTATION_0
                90 -> Surface.ROTATION_270
                180 -> Surface.ROTATION_180
                else -> Surface.ROTATION_90
            }
        }

        private fun isLandscape(rotation: Int): Boolean =
            rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        private fun isPortrait(rotation: Int): Boolean =
            rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

        const val ACTION_START = "com.pickle.blackbox.action.START_RECORDING"
        const val ACTION_STOP = "com.pickle.blackbox.action.STOP_RECORDING"
        const val ACTION_REFRESH_NOTIFICATION = "com.pickle.blackbox.action.REFRESH_NOTIFICATION"

        /** Safety net: the lock never outlives this without being renewed. */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        /** Renewed well before the timeout so a long drive never loses the lock. */
        private const val WAKE_LOCK_RENEW_MS = 3 * 60 * 60 * 1000L

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 10
        private const val REQUEST_STOP = 11
    }
}
