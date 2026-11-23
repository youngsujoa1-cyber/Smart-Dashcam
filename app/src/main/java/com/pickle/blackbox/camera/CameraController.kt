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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the CameraX pipeline: a [Preview] plus a single [VideoCapture] backed by a
 * [Recorder]. Switching between the front and the back lens simply rebinds the use
 * cases with the other [CameraSelector].
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    var onReady: ((VideoCapture<Recorder>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLensChanged: ((isFront: Boolean) -> Unit)? = null

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
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

    fun currentCapture(): VideoCapture<Recorder>? = videoCapture

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

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

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
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            videoCapture = capture
            onReady?.invoke(capture)
            onLensChanged?.invoke(isFrontCamera)
        } catch (t: Throwable) {
            Log.e(TAG, "Binding use cases failed", t)
            videoCapture = null
            onError?.invoke("카메라 화면을 표시할 수 없습니다: " + (t.message ?: "알 수 없는 오류"))
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
