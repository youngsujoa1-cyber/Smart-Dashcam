package com.pickle.blackbox.camera

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

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
 *
 * Preview must never be left without a consumer surface. When the UI leaves (the
 * activity is stopped, the screen turns off), the window's surface is destroyed, and
 * on Samsung / Camera2 HAL a bound [Preview] with no live surface stalls the repeating
 * capture request, so [VideoCapture] stops receiving frames and the recorded file plays
 * back as a frozen image. [setPreviewSurfaceProvider] therefore swaps in a tiny
 * offscreen [SurfaceTexture] instead of clearing the provider, and swaps the UI surface
 * back as its consumer when the screen returns.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    var onReady: ((preview: Preview, capture: VideoCapture<Recorder>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLensChanged: ((isFront: Boolean) -> Unit)? = null

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    /** The surface of the screen that is currently on top, null while no screen is attached. */
    private var uiSurfaceProvider: Preview.SurfaceProvider? = null

    /** Offscreen consumer that keeps the preview stream alive while no screen is attached. */
    private var dummyProvider: DummyPreviewSurface? = null
    private var dummyReleaseRunnable: Runnable? = null
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
     * Connects the preview surface of the window that is on screen, or — when [provider]
     * is null (background, screen off, activity stopped) — an offscreen surface that keeps
     * the preview stream running so the camera session (and its video track) does not stall.
     * Only the preview stream of the running session is affected; an active recording
     * continues untouched.
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        uiSurfaceProvider = provider
        if (provider == null) {
            cancelDummyRelease()
            val fallback = obtainDummyProvider()
            runCatching { preview?.surfaceProvider = fallback }
                .onFailure { Log.w(TAG, "Could not attach the offscreen preview surface", it) }
        } else {
            runCatching { preview?.surfaceProvider = provider }
                .onFailure { Log.w(TAG, "Could not update the preview surface provider", it) }
            // Let the session consume the new surface first, then free the offscreen one.
            scheduleDummyRelease()
        }
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

    /** Frees the offscreen preview surface. Called when the camera session goes away. */
    fun release() {
        releaseDummyProvider()
        preview = null
        videoCapture = null
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
        // Never leave Preview without a consumer: the UI surface when a screen is attached,
        // the offscreen surface otherwise.
        previewUseCase.surfaceProvider = uiSurfaceProvider ?: obtainDummyProvider()

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

    // ------------------------------------------------------- offscreen preview

    private fun obtainDummyProvider(): DummyPreviewSurface {
        dummyProvider?.let { return it }
        val created = DummyPreviewSurface(mainExecutor, mainHandler)
        dummyProvider = created
        return created
    }

    private fun scheduleDummyRelease() {
        val current = dummyProvider ?: return
        cancelDummyRelease()
        val runnable = Runnable {
            dummyReleaseRunnable = null
            // Only free it if a screen surface is still the one that is attached.
            if (uiSurfaceProvider != null && dummyProvider === current) releaseDummyProvider()
        }
        dummyReleaseRunnable = runnable
        mainHandler.postDelayed(runnable, DUMMY_RELEASE_DELAY_MS)
    }

    private fun cancelDummyRelease() {
        dummyReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        dummyReleaseRunnable = null
    }

    private fun releaseDummyProvider() {
        cancelDummyRelease()
        val provider = dummyProvider ?: return
        dummyProvider = null
        if (preview?.surfaceProvider === provider) {
            // The use case is being torn down along with this surface; drop it first so a
            // later bind starts from a clean state.
            runCatching { preview?.surfaceProvider = uiSurfaceProvider }
        }
        provider.release()
    }

    /**
     * An invisible consumer that behaves like a screen nobody is looking at: it accepts the
     * preview frames and drops them, so the Camera2 repeating request always has a live
     * surface and keeps feeding every output (video included) while the real preview surface
     * is gone.
     *
     * An [ImageReader] in PRIVATE format is used instead of a bare [android.graphics.SurfaceTexture]:
     * a detached SurfaceTexture needs a current GL context to drain its buffers
     * (`updateTexImage`), and an undrained buffer queue is exactly what stalls the camera.
     * The reader hands every frame back as soon as it arrives.
     */
    private class DummyPreviewSurface(
        private val executor: Executor,
        private val handler: Handler
    ) : Preview.SurfaceProvider {

        private var reader: ImageReader? = null
        private var released = false

        override fun onSurfaceRequested(request: SurfaceRequest) {
            if (released) return
            val width = request.resolution.width.coerceAtLeast(1)
            val height = request.resolution.height.coerceAtLeast(1)
            val current = obtainReader(width, height)
            request.provideSurface(current.surface, executor) { result ->
                Log.d(TAG, "Offscreen preview surface result: " + result.resultCode)
            }
        }

        private fun obtainReader(width: Int, height: Int): ImageReader {
            val existing = reader
            if (existing != null && existing.width == width && existing.height == height) {
                return existing
            }
            existing?.let { runCatching { it.close() } }
            val created = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, MAX_IMAGES).apply {
                setOnImageAvailableListener({ available ->
                    if (released) return@setOnImageAvailableListener
                    runCatching { available.acquireLatestImage()?.close() }
                        .onFailure { Log.w(TAG, "Dropping an offscreen preview frame failed", it) }
                }, handler)
            }
            reader = created
            return created
        }

        fun release() {
            if (released) return
            released = true
            val existing = reader
            reader = null
            existing?.let {
                runCatching { it.setOnImageAvailableListener(null, null) }
                runCatching { it.close() }
                    .onFailure { Log.w(TAG, "Could not close the offscreen preview reader", it) }
            }
        }
    }

    companion object {
        private const val TAG = "CameraController"

        /** Time given to the running session to start using the UI surface before the dummy dies. */
        private const val DUMMY_RELEASE_DELAY_MS = 1_000L

        /** A couple of spare buffers so a busy main thread never starves the camera queue. */
        private const val MAX_IMAGES = 3
    }
}
