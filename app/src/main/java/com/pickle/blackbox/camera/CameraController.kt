package com.pickle.blackbox.camera

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the CameraX pipeline: a [Preview] plus a single [VideoCapture] backed by a
 * [Recorder]. Switching between the front and the back lens simply rebinds the use
 * cases with the other [CameraSelector].
 *
 * The controller no longer knows about any view: the use cases are bound to the
 * lifecycle of its owner (the recording service), and the UI only hands in its
 * [Preview.SurfaceProvider]. That is what lets a running recording survive when the
 * activity goes to the background or the screen is turned off — attaching or
 * detaching the preview surface never rebuilds the camera session, so the active
 * [VideoCapture] keeps recording.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    var onReady: ((preview: Preview, capture: VideoCapture<Recorder>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLensChanged: ((isFront: Boolean) -> Unit)? = null

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var targetRotation: Int = Surface.ROTATION_0
    private var started = false

    var isFrontCamera: Boolean = false
        private set

    val isBound: Boolean
        get() = videoCapture != null

    fun start() {
        if (started) return
        started = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases()
            } catch (t: Throwable) {
                Log.e(TAG, "CameraX provider failed", t)
                onError?.invoke("카메라를 시작할 수 없습니다: " + (t.message ?: "알 수 없는 오류"))
            }
        }, mainExecutor)
    }

    fun currentPreview(): Preview? = preview

    /**
     * Connects (or clears) the preview surface of the window that is on screen.
     * Only the preview stream of the running session is affected; an active
     * recording continues untouched.
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        surfaceProvider = provider
        runCatching { preview?.surfaceProvider = provider }
            .onFailure { Log.w(TAG, "Could not update the preview surface provider", it) }
    }

    /** Returns true when the other lens exists and can be bound. */
    fun canSwitchCamera(): Boolean =
        cameraProvider?.hasCamera(selectorFor(!isFrontCamera)) == true

    /**
     * Follows the screen orientation. The preview turns immediately; a recording that is
     * already running keeps its orientation and the next segment uses the new one.
     */
    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        runCatching { preview?.targetRotation = rotation }
            .onFailure { Log.w(TAG, "Could not update the preview rotation", it) }
    }

    /** Switches front <-> back and rebinds. Returns false when the target lens is missing. */
    fun switchCamera(): Boolean {
        val provider = cameraProvider ?: return false
        val targetIsFront = !isFrontCamera
        if (!provider.hasCamera(selectorFor(targetIsFront))) {
            return false
        }
        isFrontCamera = targetIsFront
        bindUseCases()
        return true
    }

    private fun selectorFor(front: Boolean): CameraSelector =
        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val selector = selectorFor(isFrontCamera)
        if (!provider.hasCamera(selector)) {
            Log.w(TAG, "Requested lens not available")
            onError?.invoke("사용 가능한 카메라를 찾을 수 없습니다.")
            if (isFrontCamera) {
                isFrontCamera = false
                bindUseCases()
            }
            return
        }

        val previewUseCase = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
        surfaceProvider?.let { previewUseCase.surfaceProvider = it }

        val qualitySelector = QualitySelector.from(
            Quality.FHD,
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val capture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, capture)
            preview = previewUseCase
            videoCapture = capture
            onReady?.invoke(previewUseCase, capture)
            onLensChanged?.invoke(isFrontCamera)
        } catch (t: Throwable) {
            Log.e(TAG, "Binding use cases failed", t)
            preview = null
            videoCapture = null
            onError?.invoke("카메라 화면을 표시할 수 없습니다: " + (t.message ?: "알 수 없는 오류"))
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
