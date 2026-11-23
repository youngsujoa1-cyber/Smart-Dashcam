package com.pickle.blackbox.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.pickle.blackbox.media.Clip
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.settings.AppSettings
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Implements the dashcam loop: record a segment, finalize it into the gallery, enforce
 * the storage budget, and immediately start the next segment until the user stops.
 * A segment is flagged as locked (saved with the LOCKED_ prefix) when the user presses
 * the lock button while it is being recorded.
 *
 * The loop is driven by the service, never by a screen: backgrounding the app or turning
 * the display off does not pause, stop or re-rotate the running segment.
 */
class RecordingController(
    private val context: Context,
    private val settings: AppSettings,
    private val clipStorage: ClipStorage,
    private val rotationProvider: () -> Int = { Surface.ROTATION_0 }
) {

    interface Listener {
        fun onRecordingStarted(segmentIndex: Int)
        fun onRecordingStopped()
        fun onSegmentSaved(clip: Clip?)
        fun onSegmentLocked()
        fun onError(message: String)

        /**
         * The loop is paused (current segment finalized) and waits for [RecordingController.resume].
         * Fired for a lens switch and for a rotation rebind alike.
         */
        fun onPausedForSwitch()
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "segment-publisher")
    }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentFile: File? = null
    private var currentSegmentLocked = false
    private var segmentIndex = 0
    private var active = false
    private var paused = false

    /** A finalized segment is still being copied into the gallery (the capture is free meanwhile). */
    private var publishing = false

    /** Rotation the running segment was started with; null while nothing is running. */
    var currentSegmentRotation: Int? = null
        private set

    val isRecording: Boolean
        get() = active

    /**
     * True while a segment still holds the [VideoCapture] — recording, or waiting for its Finalize
     * to be published. Unbinding the use cases during that window can end the file with an error,
     * so whoever wants to rebind the camera waits until this is false.
     */
    val isCaptureIdle: Boolean
        get() = recording == null && !publishing

    /** True while the current segment will be saved as a locked clip. */
    val isCurrentSegmentLocked: Boolean
        get() = currentSegmentLocked

    fun attach(capture: VideoCapture<Recorder>) {
        videoCapture = capture
    }

    fun start() {
        if (active) return
        val capture = videoCapture
        if (capture == null) {
            listener?.onError("카메라가 아직 준비되지 않았습니다.")
            return
        }
        active = true
        paused = false
        segmentIndex = 0
        startSegment(capture)
    }

    /**
     * Stops recording the running segment (it is published normally) and holds the loop until
     * [resume] is called. Used when the camera has to be rebound, e.g. a front/rear switch or a
     * rotation change while recording: the session, its timer and the foreground service all
     * stay alive.
     */
    fun pauseForRebind() {
        if (!active || paused) return
        paused = true
        mainHandler.removeCallbacks(rotateRunnable)
        val current = recording
        if (current == null) {
            // Nothing is recording: either the loop sits between segments, or a finalized segment is
            // still being published — continueOrFinish() reports onPausedForSwitch() when it is done.
            if (!publishing) listener?.onPausedForSwitch()
        } else {
            // Finalize publishes the segment and then reports onPausedForSwitch().
            runCatching { current.stop() }
        }
    }

    /** Alias kept for the lens switch path; both reasons finalize the segment the same way. */
    fun pauseForCameraSwitch() = pauseForRebind()

    /** Continues the loop with the currently attached capture after a [pauseForRebind]. */
    fun resume() {
        if (!active || !paused) return
        paused = false
        val capture = videoCapture
        if (capture == null) {
            active = false
            currentSegmentRotation = null
            listener?.onRecordingStopped()
            return
        }
        startSegment(capture)
    }

    /**
     * Splits the running segment because the phone was turned while recording.
     *
     * The current segment is finalized and published in the orientation it was recorded in,
     * and the loop immediately starts the next one with the current [rotationProvider], exactly
     * like the segment timer does. The session itself is untouched: [paused] does not change,
     * the timer keeps running and the foreground service stays alive.
     *
     * Kept for a plain split that does not rebuild the camera session. The service no longer uses
     * it for a rotation: a [VideoCapture] that is only re-targeted keeps the encoder orientation
     * it was bound with on some devices, so the owner finalizes the segment and rebuilds the
     * session instead ([pauseForRebind]).
     */
    fun rotateForOrientation() {
        if (!active || paused) return
        if (recording == null) return
        if (currentSegmentRotation == rotationProvider()) return
        // Finalize publishes the segment; continueOrFinish() then starts the next one.
        runCatching { recording?.stop() }
    }

    fun stop() {
        if (!active) return
        active = false
        mainHandler.removeCallbacks(rotateRunnable)
        val current = recording
        if (current == null) {
            listener?.onRecordingStopped()
        } else {
            // Finalize triggers onSegmentSaved(...) and then onRecordingStopped().
            runCatching { current.stop() }
        }
    }

    /**
     * Marks the segment that is being recorded right now as locked.
     * @return false when nothing is being recorded (the caller can then lock the newest clip).
     */
    fun lockCurrentSegment(): Boolean {
        if (!active || recording == null) return false
        if (!currentSegmentLocked) {
            currentSegmentLocked = true
            listener?.onSegmentLocked()
        }
        return true
    }

    fun release() {
        active = false
        paused = false
        mainHandler.removeCallbacks(rotateRunnable)
        val current = recording
        recording = null
        currentSegmentRotation = null
        if (current != null) {
            // Let CameraX finalize so the last segment is still published.
            runCatching { current.stop() }
        }
    }

    // ------------------------------------------------------------- internals

    @SuppressLint("MissingPermission") // hasAudioPermission() checks RECORD_AUDIO right above
    private fun startSegment(capture: VideoCapture<Recorder>) {
        val file = clipStorage.newSegmentFile()
        currentFile = file
        currentSegmentLocked = false
        segmentIndex += 1

        // Each segment follows the current device orientation. The rotation that matters is
        // already baked into the [VideoCapture] when the session was bound; this only keeps a
        // re-targeted session in step.
        val rotation = rotationProvider()
        currentSegmentRotation = rotation
        runCatching { capture.targetRotation = rotation }

        val outputOptions = FileOutputOptions.Builder(file).build()
        var pending: PendingRecording = capture.output.prepareRecording(context, outputOptions)
        if (settings.recordAudio && hasAudioPermission()) {
            pending = pending.withAudioEnabled()
        }

        try {
            recording = pending.start(mainExecutor) { event -> handleVideoEvent(event) }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start recording", t)
            active = false
            recording = null
            currentSegmentRotation = null
            listener?.onError("녹화를 시작할 수 없습니다: " + (t.message ?: "알 수 없는 오류"))
            return
        }

        listener?.onRecordingStarted(segmentIndex)
        mainHandler.postDelayed(rotateRunnable, settings.segmentMillis)
    }

    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            // Stopping finalizes the current segment; the finalize handler starts the next one.
            runCatching { recording?.stop() }
        }
    }

    private fun handleVideoEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return

        mainHandler.removeCallbacks(rotateRunnable)
        recording = null
        currentSegmentRotation = null
        val finishedFile = currentFile
        currentFile = null
        val wasLocked = currentSegmentLocked
        currentSegmentLocked = false

        if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
            Log.w(TAG, "Segment finalized with error code " + event.error)
            if (event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                listener?.onError("녹화 저장 중 오류가 발생했습니다. (code " + event.error + ")")
            }
        }

        if (finishedFile == null || !finishedFile.exists() || finishedFile.length() <= 0L) {
            finishedFile?.delete()
            listener?.onSegmentSaved(null)
            continueOrFinish()
            return
        }

        val recordedAt = parseRecordedAt(finishedFile)
        publishing = true
        try {
            ioExecutor.execute {
                runCatching {
                    val clip = clipStorage.publishSegmentBlocking(finishedFile, wasLocked, recordedAt)
                    clipStorage.enforceLimitBlocking(settings.storageLimitBytes)
                    clip
                }.onSuccess { clip ->
                    mainHandler.post {
                        publishing = false
                        listener?.onSegmentSaved(clip)
                        continueOrFinish()
                    }
                }.onFailure { t ->
                    // The copy into the gallery failed: drop the temp file and keep the loop alive.
                    Log.w(TAG, "Publishing the segment failed", t)
                    finishedFile.delete()
                    mainHandler.post {
                        publishing = false
                        listener?.onSegmentSaved(null)
                        continueOrFinish()
                    }
                }
            }
        } catch (t: Throwable) {
            // Executor was shut down (activity destroyed) — just drop the temp file.
            Log.w(TAG, "Publisher unavailable", t)
            publishing = false
            finishedFile.delete()
        }
    }

    private fun continueOrFinish() {
        if (!active) {
            currentSegmentRotation = null
            listener?.onRecordingStopped()
            return
        }
        if (paused) {
            // A lens switch / rotation rebind is in progress: wait for resume().
            listener?.onPausedForSwitch()
            return
        }
        val capture = videoCapture
        if (capture == null) {
            active = false
            currentSegmentRotation = null
            listener?.onRecordingStopped()
            return
        }
        startSegment(capture)
    }

    private fun parseRecordedAt(file: File): Long {
        // File name is seg_<epochMillis>.mp4
        val name = file.nameWithoutExtension
        val stamp = name.substringAfter("seg_", "")
        return stamp.toLongOrNull() ?: System.currentTimeMillis()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecordingController"
    }
}
