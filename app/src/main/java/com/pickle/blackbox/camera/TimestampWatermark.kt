package com.pickle.blackbox.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [CameraEffect] that burns the recording date and time (`yyyy-MM-dd HH:mm:ss`) into every
 * frame that reaches the [androidx.camera.video.VideoCapture].
 *
 * The stamp therefore becomes part of the MP4 itself — a player, a gallery app, a share to
 * someone else or a copy onto a PC all show it, which is what a dashcam video is expected to
 * look like. The preview is not affected: the effect only targets `VIDEO_CAPTURE`, so the live
 * picture stays clean and cheap while the saved footage carries the stamp.
 *
 * The effect needs nothing from Google's `camera-effects` artifact (which is not available in
 * this offline build); the renderer is a small EGL 2.0 / GLES20 [SurfaceProcessor] of its own,
 * with a fixed shader pair and a single extra draw call per frame.
 *
 * Creation and every call into the processor happen on one private GL thread
 * ([TimestampWatermarkProcessor.executor]); [close] frees the EGL surface, the context, the GL
 * objects and the thread, in that order, so a rebind (lens switch, rotation) or a finished
 * service never leaves a context or a buffer queue behind.
 */
class TimestampWatermarkEffect internal constructor(
    private val processor: TimestampWatermarkProcessor
) : CameraEffect(
    CameraEffect.VIDEO_CAPTURE,
    processor.executor,
    processor,
    Consumer<Throwable> { throwable -> processor.onEffectError(throwable) }
) {

    /** Stops rendering and frees the GL thread. The effect cannot be used again afterwards. */
    fun close() {
        processor.release()
    }

    companion object {
        /** Builds an effect that stamps the recording time into every video frame. */
        fun create(): TimestampWatermarkEffect =
            TimestampWatermarkEffect(TimestampWatermarkProcessor())
    }
}

/**
 * Draws the camera frames into the video surface with a timestamp overlay on top.
 *
 * Orientation handling, the reason this class exists at all:
 *
 *  * The frame the camera hands over lives in the sensor buffer, so the picture is usually
 *    rotated and may be mirrored relative to how the video is meant to be seen.
 *    [SurfaceOutput.updateTransformMatrix] — fed with the value from
 *    [SurfaceTexture.getTransformMatrix] — returns the texture matrix that additionally
 *    contains the rotation, the mirroring and the crop CameraX wants for this use case, and
 *    its output surface is sized for that rotated picture. Drawing the camera texture through
 *    that matrix therefore puts an upright picture into the output buffer, for the front and
 *    the rear lens and in portrait as well as in landscape alike.
 *  * The stamp is a plain 2D texture drawn as its own quad in that same (already upright)
 *    output buffer, one draw call after the camera quad. It is never rotated with the picture,
 *    so it reads upright in landscape, in portrait and while a `ROTATION_90` / `ROTATION_270`
 *    segment is recorded, and it is never mirrored even when the frame is.
 *  * The text bitmap is rebuilt at most once per second (and when the output size changes) and
 *    uploaded with `glTexSubImage2D` ([GLUtils.texSubImage2D]) into a texture that keeps its
 *    allocation, so a 30 fps stream does not allocate a bitmap, a canvas or a texture per frame.
 */
class TimestampWatermarkProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    // ---------------------------------------------------------------- threading

    private val glThread = HandlerThread(GL_THREAD_NAME).apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** The executor CameraX runs every [SurfaceProcessor] callback on. */
    val executor: Executor = Executor { command ->
        if (!glHandler.post(command)) Log.w(TAG, "Dropping a callback: the GL thread is gone")
    }

    // --------------------------------------------------------------------- EGL

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    /** Tiny offscreen surface that keeps the context current while no video surface is bound. */
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // ------------------------------------------------------------------ output

    private var outputSurfaceOutput: SurfaceOutput? = null
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputWidth = 0
    private var outputHeight = 0

    // ------------------------------------------------------------------- input

    private var inputTexture = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    // ----------------------------------------------------------------- overlay

    private var overlayTexture = 0
    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private var overlayBitmapWidth = 0
    private var overlayBitmapHeight = 0
    /** Frame size the current bitmap was laid out for, as `width shl 32 or height`. */
    private var overlayBitmapFrameKey = -1L
    private var overlayAllocatedWidth = 0
    private var overlayAllocatedHeight = 0
    private var lastDrawnSecond = Long.MIN_VALUE

    // ---------------------------------------------------------------- programs

    private var cameraProgram = 0
    private var overlayProgram = 0
    private var cameraPositionLocation = -1
    private var cameraTexCoordLocation = -1
    private var cameraTexMatrixLocation = -1
    private var cameraSamplerLocation = -1
    private var overlayPositionLocation = -1
    private var overlayTexCoordLocation = -1
    private var overlaySamplerLocation = -1

    private val fullQuadVertices = createFloatBuffer(FULL_QUAD_VERTICES)
    private val fullQuadTexCoords = createFloatBuffer(FULL_QUAD_TEX_COORDS)
    private val stampVertices = createFloatBuffer(FloatArray(8))
    private val stampVertexValues = FloatArray(8)
    private val stampTexCoords = createFloatBuffer(STAMP_TEX_COORDS)

    private val texMatrix = FloatArray(16)
    private val surfaceTransform = FloatArray(16)

    private var swappedFrame = false
    private var glInitFailed = false
    private var released = false

    // ------------------------------------------------------- SurfaceProcessor

    override fun onInputSurface(surfaceRequest: SurfaceRequest) {
        if (released) {
            surfaceRequest.willNotProvideSurface()
            return
        }
        if (!ensureEglContext()) {
            surfaceRequest.willNotProvideSurface()
            return
        }

        releaseInput()

        val resolution = surfaceRequest.resolution
        val texture = createExternalTexture()
        if (texture == 0) {
            Log.e(TAG, "Could not create the camera input texture")
            surfaceRequest.willNotProvideSurface()
            return
        }
        val surfaceTexture = runCatching {
            SurfaceTexture(texture).apply {
                setDefaultBufferSize(resolution.width, resolution.height)
                setOnFrameAvailableListener(this@TimestampWatermarkProcessor, glHandler)
            }
        }.getOrElse { throwable ->
            Log.e(TAG, "Could not create the camera input surface", throwable)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            surfaceRequest.willNotProvideSurface()
            return
        }
        val surface = Surface(surfaceTexture)

        inputTexture = texture
        inputSurfaceTexture = surfaceTexture
        inputSurface = surface

        // The request ends when the session is torn down (rebind, lens switch, release); the
        // consumer below runs on this GL thread, so the buffers can be freed right there.
        surfaceRequest.provideSurface(surface, executor) { result ->
            Log.d(TAG, "Input surface closed: " + result.resultCode)
            if (inputSurfaceTexture === surfaceTexture) releaseInput()
        }
    }

    override fun onOutputSurface(outputSurface: SurfaceOutput) {
        if (released) {
            runCatching { outputSurface.close() }
            return
        }
        if (!ensureEglContext()) {
            runCatching { outputSurface.close() }
            return
        }
        releaseOutput()

        // getSurface()'s listener is the only notification that the provider is done with the
        // buffer, so the EGL window surface is destroyed there before the SurfaceOutput is
        // acknowledged. Writing into a released surface is unsafe on some devices.
        val surface = outputSurface.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                if (outputSurfaceOutput === outputSurface) releaseOutput()
                runCatching { outputSurface.close() }
                Log.d(TAG, "Output surface closed")
            }
        }
        val size = outputSurface.size
        if (size.width <= 0 || size.height <= 0) {
            Log.w(TAG, "Output surface has an empty size: " + size)
            runCatching { outputSurface.close() }
            return
        }

        val eglSurface = createWindowSurface(surface)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            runCatching { outputSurface.close() }
            return
        }

        outputSurfaceOutput = outputSurface
        outputEglSurface = eglSurface
        outputWidth = size.width
        outputHeight = size.height
        // A new segment may come with a new frame size: force a fresh stamp bitmap.
        lastDrawnSecond = Long.MIN_VALUE
        Log.d(TAG, "Output surface ready: " + size.width + "x" + size.height)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (released) return
        // A frame queued by a surface that was already replaced (rebind, lens switch) is stale.
        if (surfaceTexture !== inputSurfaceTexture) return
        val output = outputEglSurface
        val outputInfo = outputSurfaceOutput
        val texture = inputTexture
        if (output == EGL14.EGL_NO_SURFACE || outputInfo == null || texture == 0) return
        if (outputWidth <= 0 || outputHeight <= 0) return
        if (!makeCurrent()) return

        try {
            surfaceTexture.updateTexImage()
            val presentationTimeNs = surfaceTexture.timestamp
            surfaceTexture.getTransformMatrix(texMatrix)
            // Rotation / mirroring / crop for this use case, on top of the buffer layout.
            outputInfo.updateTransformMatrix(surfaceTransform, texMatrix)

            refreshStampBitmap()

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            drawCameraFrame(texture)
            drawStamp()

            EGLExt.eglPresentationTimeANDROID(eglDisplay, output, presentationTimeNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, output) && !swappedFrame) {
                swappedFrame = true
                Log.w(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not render a frame", t)
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun drawCameraFrame(texture: Int) {
        if (cameraProgram == 0) return
        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(cameraSamplerLocation, 0)
        GLES20.glUniformMatrix4fv(cameraTexMatrixLocation, 1, false, surfaceTransform, 0)
        GLES20.glVertexAttribPointer(
            cameraPositionLocation, 2, GLES20.GL_FLOAT, false, 0, fullQuadVertices
        )
        GLES20.glEnableVertexAttribArray(cameraPositionLocation)
        GLES20.glVertexAttribPointer(
            cameraTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, fullQuadTexCoords
        )
        GLES20.glEnableVertexAttribArray(cameraTexCoordLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(cameraPositionLocation)
        GLES20.glDisableVertexAttribArray(cameraTexCoordLocation)
    }

    /**
     * Draws the stamp texture in the bottom-left corner of the output buffer.
     *
     * The output buffer is already upright (see the class comment), so the stamp is drawn
     * axis-aligned: the bitmap's top row (texture t = 0) is placed at the larger `y`, which is
     * the top of the picture.
     */
    private fun drawStamp() {
        val bitmapWidth = overlayBitmapWidth
        val bitmapHeight = overlayBitmapHeight
        if (overlayProgram == 0 || overlayTexture == 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return
        }
        val margin = (min(outputWidth, outputHeight) * STAMP_MARGIN_RATIO).roundToInt()
            .coerceAtLeast(1)
        val left = -1f + 2f * margin / outputWidth
        val right = left + 2f * bitmapWidth / outputWidth
        val bottom = -1f + 2f * margin / outputHeight
        val top = bottom + 2f * bitmapHeight / outputHeight

        stampVertexValues[0] = left
        stampVertexValues[1] = bottom
        stampVertexValues[2] = right
        stampVertexValues[3] = bottom
        stampVertexValues[4] = left
        stampVertexValues[5] = top
        stampVertexValues[6] = right
        stampVertexValues[7] = top
        stampVertices.put(stampVertexValues)
        stampVertices.position(0)
        stampTexCoords.position(0)

        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLES20.glUniform1i(overlaySamplerLocation, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Canvas bitmaps are premultiplied, so the source already carries alpha * color.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glVertexAttribPointer(
            overlayPositionLocation, 2, GLES20.GL_FLOAT, false, 0, stampVertices
        )
        GLES20.glEnableVertexAttribArray(overlayPositionLocation)
        GLES20.glVertexAttribPointer(
            overlayTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, stampTexCoords
        )
        GLES20.glEnableVertexAttribArray(overlayTexCoordLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(overlayPositionLocation)
        GLES20.glDisableVertexAttribArray(overlayTexCoordLocation)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // -------------------------------------------------------------------- stamp

    /**
     * Redraws the stamp bitmap and uploads it when the second changed or the frame size is new.
     * Everything else stays untouched, which is what keeps the cost at one small canvas draw
     * and one texture upload per second instead of one per frame.
     */
    private fun refreshStampBitmap() {
        val second = System.currentTimeMillis() / 1000L
        val frameKey = (outputWidth.toLong() shl 32) or outputHeight.toLong()
        val bitmap = if (overlayBitmap == null || overlayBitmapFrameKey != frameKey) {
            createStampBitmap(frameKey)
        } else {
            overlayBitmap
        }
        val canvas = overlayCanvas
        if (bitmap == null || canvas == null) return
        if (second == lastDrawnSecond) return
        lastDrawnSecond = second

        val textPaint = stampTextPaint()
        val metrics = textPaint.fontMetrics
        val text = STAMP_FORMAT.format(LocalDateTime.now())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(
            RectF(0f, 0f, overlayBitmapWidth.toFloat(), overlayBitmapHeight.toFloat()),
            stampCornerRadius(),
            stampCornerRadius(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STAMP_BACKGROUND }
        )
        canvas.drawText(text, stampPaddingX(), stampPaddingY() - metrics.ascent, textPaint)

        uploadStamp(bitmap)
    }

    /** Builds the bitmap and its canvas, sized for the timestamp at the current frame size. */
    private fun createStampBitmap(frameKey: Long): Bitmap? {
        if (outputWidth <= 0 || outputHeight <= 0) return null
        val textPaint = stampTextPaint()
        val metrics = textPaint.fontMetrics
        // Wide enough for any time of day, so the badge never changes width mid-recording.
        val sample = "0000-00-00 00:00:00"
        val width = ceil(textPaint.measureText(sample) + stampPaddingX() * 2f).toInt()
        val height = ceil(metrics.descent - metrics.ascent + stampPaddingY() * 2f).toInt()
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlayBitmap?.recycle()
        overlayBitmap = bitmap
        overlayCanvas = Canvas(bitmap)
        overlayBitmapWidth = width
        overlayBitmapHeight = height
        overlayBitmapFrameKey = frameKey
        return bitmap
    }

    private fun stampTextPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = stampTextSize()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun uploadStamp(bitmap: Bitmap) {
        if (overlayTexture == 0) overlayTexture = createTexture2D()
        if (overlayTexture == 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        if (overlayAllocatedWidth != bitmap.width || overlayAllocatedHeight != bitmap.height) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            overlayAllocatedWidth = bitmap.width
            overlayAllocatedHeight = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun stampTextSize(): Float =
        (min(outputWidth, outputHeight) * STAMP_TEXT_RATIO).coerceAtLeast(10f)

    private fun stampPaddingX(): Float = stampTextSize() * 0.45f

    private fun stampPaddingY(): Float = stampTextSize() * 0.30f

    private fun stampCornerRadius(): Float = stampTextSize() * 0.30f

    // ---------------------------------------------------------------------- EGL

    private fun ensureEglContext(): Boolean {
        if (glInitFailed) return false
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY && eglContext !== EGL14.EGL_NO_CONTEXT) return true

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "No EGL display")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            return false
        }
        eglDisplay = display

        val config = chooseConfig(display)
        if (config == null) {
            Log.e(TAG, "No usable EGL configuration")
            EGL14.eglTerminate(display)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }
        eglConfig = config

        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context === EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            EGL14.eglTerminate(display)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }
        eglContext = context

        pbufferSurface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (!makeCurrent()) {
            Log.e(TAG, "Could not make the EGL context current")
            glInitFailed = true
            return false
        }
        if (!initPrograms()) {
            Log.e(TAG, "Could not build the GL programs")
            glInitFailed = true
            return false
        }
        Log.d(TAG, "EGL ready: " + GLES20.glGetString(GLES20.GL_VERSION))
        return true
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val base = mutableListOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        // EGL_RECORDABLE_ANDROID lets a MediaCodec consumer take the surface on devices that
        // insist on it; not every driver knows the attribute, hence the second attempt.
        val recordable = base.toMutableList()
        recordable.add(recordable.size - 1, EGL_RECORDABLE_ANDROID)
        recordable.add(recordable.size - 1, 1)
        return tryChooseConfig(display, recordable.toIntArray())
            ?: tryChooseConfig(display, base.toIntArray())
    }

    private fun tryChooseConfig(display: EGLDisplay, attribs: IntArray): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val chosen = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        if (!chosen || count[0] < 1) return null
        return configs[0]
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val display = eglDisplay
        val config = eglConfig
        if (display === EGL14.EGL_NO_DISPLAY || config == null) return EGL14.EGL_NO_SURFACE
        val eglSurface = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            Log.e(
                TAG,
                "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError())
            )
            return EGL14.EGL_NO_SURFACE
        }
        swappedFrame = false
        return eglSurface
    }

    /** Makes the EGL context current against the video surface, or the pbuffer when there is none. */
    private fun makeCurrent(): Boolean {
        val display = eglDisplay
        val context = eglContext
        if (display === EGL14.EGL_NO_DISPLAY || context === EGL14.EGL_NO_CONTEXT) return false
        val target = if (outputEglSurface !== EGL14.EGL_NO_SURFACE) {
            outputEglSurface
        } else {
            pbufferSurface
        }
        if (target === EGL14.EGL_NO_SURFACE) return false
        if (EGL14.eglGetCurrentContext() === context &&
            EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) === target
        ) {
            return true
        }
        if (!EGL14.eglMakeCurrent(display, target, target, context)) {
            Log.e(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- programs

    private fun initPrograms(): Boolean {
        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        if (cameraProgram == 0) return false
        cameraPositionLocation = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        cameraTexCoordLocation = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        cameraTexMatrixLocation = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")
        cameraSamplerLocation = GLES20.glGetUniformLocation(cameraProgram, "uTexture")

        overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        if (overlayProgram == 0) return false
        overlayPositionLocation = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        overlayTexCoordLocation = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        overlaySamplerLocation = GLES20.glGetUniformLocation(overlayProgram, "uTexture")

        return cameraPositionLocation >= 0 && cameraTexCoordLocation >= 0 &&
            cameraTexMatrixLocation >= 0 && cameraSamplerLocation >= 0 &&
            overlayPositionLocation >= 0 && overlayTexCoordLocation >= 0 &&
            overlaySamplerLocation >= 0
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == null) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    // ------------------------------------------------------------------ texture

    private fun createExternalTexture(): Int {
        if (!makeCurrent()) return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return ids[0]
    }

    private fun createTexture2D(): Int {
        if (!makeCurrent()) return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return ids[0]
    }

    // ------------------------------------------------------------------ cleanup

    private fun releaseInput() {
        val surfaceTexture = inputSurfaceTexture
        val surface = inputSurface
        val texture = inputTexture
        inputSurfaceTexture = null
        inputSurface = null
        inputTexture = 0

        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surface?.release() }
        runCatching { surfaceTexture?.release() }
        if (texture != 0 && makeCurrent()) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private fun releaseOutput() {
        outputSurfaceOutput = null
        val eglSurface = outputEglSurface
        outputEglSurface = EGL14.EGL_NO_SURFACE
        outputWidth = 0
        outputHeight = 0
        if (eglSurface !== EGL14.EGL_NO_SURFACE && eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            // Park the context on the pbuffer before the surface it was drawing into goes away.
            val parking = pbufferSurface
            if (parking !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, parking, parking, eglContext)
            } else {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (!EGL14.eglDestroySurface(eglDisplay, eglSurface)) {
                Log.w(
                    TAG,
                    "eglDestroySurface failed: 0x" + Integer.toHexString(EGL14.eglGetError())
                )
            }
        }
    }

    /** Called by [TimestampWatermarkEffect] when the camera is done with the effect. */
    fun release() {
        if (released) return
        released = true
        val cleanup = Runnable {
            releaseInput()
            releaseOutput()
            makeCurrent()
            if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram)
            if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
            cameraProgram = 0
            overlayProgram = 0
            if (overlayTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(overlayTexture), 0)
                overlayTexture = 0
            }
            overlayCanvas = null
            overlayBitmap?.recycle()
            overlayBitmap = null
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (pbufferSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                    pbufferSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            Log.d(TAG, "Released")
            glThread.quitSafely()
        }
        if (Looper.myLooper() === glThread.looper) {
            cleanup.run()
        } else if (!glHandler.post(cleanup)) {
            glThread.quitSafely()
        }
    }

    /** CameraX reports an unrecoverable processing failure here (e.g. a lost surface). */
    fun onEffectError(throwable: Throwable) {
        Log.e(TAG, "Timestamp effect failed; the frames are not being stamped", throwable)
    }

    companion object {
        private const val TAG = "TimestampWatermark"

        private const val GL_THREAD_NAME = "timestamp-watermark-gl"

        /** `EGL_RECORDABLE_ANDROID`, missing from the public EGL14 constants. */
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val STAMP_TEXT_RATIO = 0.042f
        private const val STAMP_MARGIN_RATIO = 0.025f

        /** Black at ~55% opacity; white text on top stays readable on a bright sky. */
        private const val STAMP_BACKGROUND = 0x8C000000.toInt()

        private val STAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** Bottom-left, bottom-right, top-left, top-right — the order of a triangle strip. */
        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        /**
         * Texture coordinates of the stamp quad. `t = 0` is the bitmap's first (top) row, which
         * is placed at the top edge of the quad, so the text is not upside down.
         */
        private val STAMP_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        private const val CAMERA_VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private fun createFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
