package com.cekavis.rtspcamera.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.cekavis.rtspcamera.BuildConfig
import com.cekavis.rtspcamera.model.VideoConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** All EGL and SurfaceTexture operations are confined to one dedicated thread. */
class GlRenderer(
    private val config: VideoConfig,
    private val baseRotation: Int,
    private val battery: () -> Int,
    private val onFrameRendered: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("camera-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var texture = 0
    private var overlayTexture = 0
    private var cameraProgram = 0
    private var overlayProgram = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var stopped = false
    private var lastFrameNs = 0L
    private var geometryLogged = false
    private val framePacer = FramePacer(config.fps)
    private var overlaySecond = Long.MIN_VALUE
    private var overlayBitmap: Bitmap? = null
    private val textureMatrix = FloatArray(16)
    private val producerMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coordinates = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val bitmapCoordinates = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private var overlayPositions = floats(-1f, .8f, 1f, .8f, -1f, 1f, 1f, 1f)

    suspend fun start(): Surface = onGl {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "无法初始化 GPU" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "EGL 初始化失败" }
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142, 1, EGL14.EGL_NONE) // EGL_RECORDABLE_ANDROID
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        offscreen = EGL14.eglCreatePbufferSurface(display, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(offscreen)
        cameraProgram = program(VERTEX, OES_FRAGMENT)
        overlayProgram = program(VERTEX, TEXTURE_FRAGMENT)
        texture = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        overlayTexture = createTexture(GLES20.GL_TEXTURE_2D)
        CameraFrameTransform.model(config.rotation + baseRotation, config.mirror, cameraMatrix)
        val st = SurfaceTexture(texture).apply { setDefaultBufferSize(config.width, config.height) }
        surfaceTexture = st
        st.setOnFrameAvailableListener({ render() }, handler)
        Surface(st).also { cameraSurface = it }
    }

    suspend fun setEncoder(surface: Surface?) = onGl {
        if (stopped) return@onGl
        makeCurrent(offscreen)
        if (encoderWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderWindow)
        encoderWindow = if (surface != null) window(surface) else EGL14.EGL_NO_SURFACE
    }

    suspend fun setPreview(surface: Surface?, width: Int = 0, height: Int = 0) = onGl {
        if (stopped) return@onGl
        makeCurrent(offscreen)
        if (previewWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewWindow)
        previewWindow = EGL14.EGL_NO_SURFACE
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        if (surface != null && surface.isValid && width > 0 && height > 0) previewWindow = window(surface)
    }

    private fun render() {
        if (stopped) return
        try {
            makeCurrent(offscreen)
            val st = surfaceTexture ?: return
            st.updateTexImage()
            val timestamp = st.timestamp.takeIf { it > 0 } ?: System.nanoTime()
            if (!framePacer.shouldRender(timestamp)) return
            lastFrameNs = max(timestamp, lastFrameNs + 1)
            st.getTransformMatrix(producerMatrix)
            if (BuildConfig.DEBUG && !geometryLogged) {
                geometryLogged = true
                Log.d("CameraGeometry", "input=${config.width}x${config.height} output=${config.outputWidth}x${config.outputHeight} " +
                    "base=$baseRotation rotation=${config.rotation} mirror=${config.mirror} producer=${producerMatrix.contentToString()}")
            }
            updateOverlay()
            if (encoderWindow != EGL14.EGL_NO_SURFACE) {
                makeCurrent(encoderWindow)
                draw(config.outputWidth, config.outputHeight)
                EGLExt.eglPresentationTimeANDROID(display, encoderWindow, lastFrameNs)
                check(EGL14.eglSwapBuffers(display, encoderWindow)) { "编码输入画面提交失败" }
            }
            if (previewWindow != EGL14.EGL_NO_SURFACE && previewSurface?.isValid == true) {
                // A UI surface may disappear between isValid and eglSwapBuffers. It must never kill streaming.
                if (EGL14.eglMakeCurrent(display, previewWindow, previewWindow, context)) {
                    draw(previewWidth, previewHeight)
                    if (!EGL14.eglSwapBuffers(display, previewWindow)) detachInvalidPreview()
                } else detachInvalidPreview()
            }
            if (encoderWindow != EGL14.EGL_NO_SURFACE || previewWindow != EGL14.EGL_NO_SURFACE) onFrameRendered()
        } catch (error: Throwable) {
            if (!stopped) { stopped = true; onError(error) }
        }
    }

    private fun detachInvalidPreview() {
        makeCurrent(offscreen)
        EGL14.eglDestroySurface(display, previewWindow)
        previewWindow = EGL14.EGL_NO_SURFACE
        previewSurface = null
    }

    private fun draw(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val aspect = config.outputWidth.toFloat() / config.outputHeight
        val drawWidth = if (width.toFloat() / height > aspect) (height * aspect).roundToInt() else width
        val drawHeight = if (width.toFloat() / height > aspect) height else (width / aspect).roundToInt()
        GLES20.glViewport((width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight)
        drawCameraQuad(cameraProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture, positions, coordinates,
            cameraMatrix, producerMatrix, textureMatrix)
        if (overlayBitmap != null) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // Android Bitmap is premultiplied.
            drawQuad(overlayProgram, GLES20.GL_TEXTURE_2D, overlayTexture, overlayPositions, bitmapCoordinates, identity, identity)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun updateOverlay() {
        if (!config.showTimestamp && !config.showBattery) return
        val second = System.currentTimeMillis() / 1000
        if (second == overlaySecond) return
        overlaySecond = second
        val fontSize = max(18f, config.outputHeight * .025f)
        val height = (fontSize * 2.2f).roundToInt()
        val bitmap = overlayBitmap ?: Bitmap.createBitmap(config.outputWidth, height, Bitmap.Config.ARGB_8888).also {
            overlayBitmap = it
            val bottom = 1f - height.toFloat() / config.outputHeight * 2
            overlayPositions = floats(-1f, bottom, 1f, bottom, -1f, 1f, 1f, 1f)
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = fontSize
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        val padding = max(12f, config.outputWidth * .015f)
        if (config.showTimestamp) canvas.drawText(ZonedDateTime.now().format(TIME_FORMAT), padding, fontSize * 1.45f, paint)
        if (config.showBattery) {
            val percent = battery()
            val text = if (percent >= 0) "$percent%" else "—%"
            canvas.drawText(text, bitmap.width - padding - paint.measureText(text), fontSize * 1.45f, paint)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    suspend fun close() {
        try { onGl {
            stopped = true
            surfaceTexture?.setOnFrameAvailableListener(null)
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (context != EGL14.EGL_NO_CONTEXT && offscreen != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, offscreen, offscreen, context)
                    GLES20.glDeleteTextures(2, intArrayOf(texture, overlayTexture), 0)
                    GLES20.glDeleteProgram(cameraProgram)
                    GLES20.glDeleteProgram(overlayProgram)
                }
                cameraSurface?.release(); cameraSurface = null
                surfaceTexture?.release(); surfaceTexture = null
                overlayBitmap?.recycle(); overlayBitmap = null
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                listOf(encoderWindow, previewWindow, offscreen).filter { it != EGL14.EGL_NO_SURFACE }
                    .forEach { EGL14.eglDestroySurface(display, it) }
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        } } finally {
            thread.quitSafely()
            withContext(Dispatchers.IO) { thread.join(1500) }
        }
    }

    private fun window(surface: Surface): EGLSurface = EGL14.eglCreateWindowSurface(display, eglConfig, surface,
        intArrayOf(EGL14.EGL_NONE), 0).also { check(it != EGL14.EGL_NO_SURFACE) { "无法创建画面输出" } }
    private fun makeCurrent(target: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, target, target, context)) { "EGL context unavailable" }
    }
    private suspend fun <T> onGl(block: () -> T): T {
        val result = CompletableDeferred<T>()
        check(handler.post { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } })
        return result.await()
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        internal fun floats(vararg data: Float): FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
        private fun createTexture(target: Int): Int {
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }
        internal fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, code: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, code); GLES20.glCompileShader(id)
                val result = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, result, 0)
                check(result[0] != 0) { GLES20.glGetShaderInfoLog(id) }
                return id
            }
            val v = shader(GLES20.GL_VERTEX_SHADER, vertex); val f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, v); GLES20.glAttachShader(id, f); GLES20.glLinkProgram(id)
            val result = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, result, 0)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            check(result[0] != 0) { GLES20.glGetProgramInfoLog(id) }
            return id
        }
        internal fun drawQuad(program: Int, target: Int, texture: Int, positions: FloatBuffer, coordinates: FloatBuffer,
                             mvp: FloatArray, textureMatrix: FloatArray) {
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
            positions.position(0); coordinates.position(0)
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, textureMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(coord)
        }
        internal fun drawCameraQuad(program: Int, target: Int, texture: Int, positions: FloatBuffer, coordinates: FloatBuffer,
                                    model: FloatArray, producer: FloatArray, normalized: FloatArray) {
            CameraFrameTransform.texture(producer, normalized)
            drawQuad(program, target, texture, positions, coordinates, model, normalized)
        }
        internal const val VERTEX = "attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uMvp; uniform mat4 uTexMatrix; varying vec2 vTexCoord; void main(){gl_Position=uMvp*vec4(aPosition,0.0,1.0);vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;}"
        private const val OES_FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES uTexture; void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}"
        internal const val TEXTURE_FRAGMENT = "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture; void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}"
    }
}
