package com.pickle.blackbox.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * A segment is flagged as locked (saved with the LOCKED_ prefix) when an impact is
 * detected or the user presses the lock button while it is being recorded.
 */
class RecordingController(
    private val context: Context,
    private val settings: AppSettings,
    private val clipStorage: ClipStorage
) {

    interface Listener {
        fun onRecordingStarted(segmentIndex: Int)
        fun onRecordingStopped()
        fun onSegmentSaved(clip: Clip?)
        fun onSegmentLocked()
        fun onError(message: String)
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
    private var lockNextSegment = false
    private var segmentIndex = 0
    private var active = false

    val isRecording: Boolean
        get() = active

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
        segmentIndex = 0
        startSegment(capture)
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

    /** Used by the impact detector when a segment is about to start. */
    fun lockNextSegmentAsWell() {
        lockNextSegment = true
    }

    fun release() {
        active = false
        mainHandler.removeCallbacks(rotateRunnable)
        val current = recording
        recording = null
        if (current != null) {
            // Let CameraX finalize so the last segment is still published.
            runCatching { current.stop() }
        }
    }

    // ------------------------------------------------------------- internals

    private fun startSegment(capture: VideoCapture<Recorder>) {
        val file = clipStorage.newSegmentFile()
        currentFile = file
        currentSegmentLocked = lockNextSegment
        lockNextSegment = false
        segmentIndex += 1

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
        try {
            ioExecutor.execute {
                val clip = clipStorage.publishSegmentBlocking(finishedFile, wasLocked, recordedAt)
                clipStorage.enforceLimitBlocking(settings.storageLimitBytes)
                mainHandler.post {
                    listener?.onSegmentSaved(clip)
                    continueOrFinish()
                }
            }
        } catch (t: Throwable) {
            // Executor was shut down (activity destroyed) — just drop the temp file.
            Log.w(TAG, "Publisher unavailable", t)
            finishedFile.delete()
        }
    }

    private fun continueOrFinish() {
        if (!active) {
            listener?.onRecordingStopped()
            return
        }
        val capture = videoCapture
        if (capture == null) {
            active = false
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
