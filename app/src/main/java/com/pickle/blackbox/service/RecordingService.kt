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
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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

        createNotificationChannel()
        ensureCamera()
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
        if (cameraReady) startSegmentsIfNeeded()
    }

    fun stopRecording() {
        wantRecording = false
        if (isRecording) {
            recordingController.stop()
        } else {
            finishSession()
        }
    }

    /** Locks the segment that is being recorded right now. */
    fun lockCurrentSegment(): Boolean = isRecording && recordingController.lockCurrentSegment()

    /** Keeps the captured video oriented the same way as the screen. */
    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        if (::cameraController.isInitialized) cameraController.setTargetRotation(rotation)
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
            if (isRecording) recordingController.resume()
        }
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
                if (switchingCamera) {
                    // Camera switch while recording: continue the loop on the new lens.
                    switchingCamera = false
                    if (isRecording) recordingController.resume()
                } else if (wantRecording) {
                    startSegmentsIfNeeded()
                }
            }
            onError = { message ->
                if (switchingCamera) {
                    switchingCamera = false
                    if (isRecording) recordingController.resume()
                }
                uiListener?.onError(message)
            }
            onLensChanged = { isFront -> uiListener?.onLensChanged(isFront) }
        }
        speedTracker = SpeedTracker(this) { kmh -> speedKmh = kmh }
        speedTracker.start()

        cameraController.start()
    }

    private fun startSegmentsIfNeeded() {
        if (!wantRecording || recordingController.isRecording) return
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
            rebindForSwitch()
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
        wantRecording = false
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

    companion object {
        private const val TAG = "RecordingService"

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
