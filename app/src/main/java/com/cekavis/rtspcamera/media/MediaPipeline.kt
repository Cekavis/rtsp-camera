package com.cekavis.rtspcamera.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.cekavis.rtspcamera.model.*
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the single camera/encoder. Demand changes are serialized, including asynchronous camera opens. */
class MediaPipeline(
    private val context: Context,
    val config: VideoConfig,
    private val capabilities: CameraCapabilities,
    private val battery: () -> Int,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onFrameRendered: () -> Unit,
    private val onActive: (camera: Boolean, preview: Boolean, opening: Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val demands = mutableSetOf<String>()
    private var preview: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var gl: GlRenderer? = null
    private var encoder: VideoEncoder? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var deviceClosed = CompletableDeferred<Unit>()
    private var format = CompletableDeferred<CodecConfig>()
    private val failed = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var epoch = 0L

    suspend fun acquire(id: String): CodecConfig = mutex.withLock {
        check(!closed) { "视频服务已停止" }
        demands += id
        try {
            ensureCapture(encoding = true)
            withTimeout(8_000) { format.await() }
        } catch (e: Throwable) {
            demands -= id
            withContext(NonCancellable) { reconcile() }
            throw e
        }
    }

    suspend fun release(id: String) = mutex.withLock {
        demands -= id
        reconcile()
    }

    suspend fun attachPreview(surface: Surface, width: Int, height: Int) = mutex.withLock {
        if (closed || !surface.isValid || width <= 0 || height <= 0) return@withLock
        preview = surface; previewWidth = width; previewHeight = height
        try {
            ensureCapture(encoding = demands.isNotEmpty())
            gl?.setPreview(surface, width, height)
            onActive(camera != null, true, false)
        } catch (e: Throwable) {
            preview = null
            withContext(NonCancellable) { reconcile() }
            throw e
        }
    }

    suspend fun detachPreview() = mutex.withLock {
        preview = null
        gl?.setPreview(null)
        reconcile()
    }

    fun requestKeyFrame() {
        runCatching { encoder?.requestKeyframe() }.onFailure { reportFailure(it) }
    }

    private suspend fun ensureCapture(encoding: Boolean) {
        failed.set(false)
        if (gl == null) {
            onActive(false, preview != null, true)
            epoch = NEXT_EPOCH.incrementAndGet()
            val characteristics = capabilities.characteristics(config.cameraId)
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val naturalLandscape = if (sensor % 180 == 90) 90 else 0
            val base = if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                (sensor + naturalLandscape) % 360 else (sensor - naturalLandscape + 360) % 360
            val renderer = GlRenderer(config, base, battery, onFrameRendered, ::reportFailure)
            gl = renderer
            val surface = renderer.start()
            if (encoding) startEncoder(renderer)
            val thread = HandlerThread("camera-capture").apply { start() }
            cameraThread = thread
            val handler = Handler(thread.looper)
            deviceClosed = CompletableDeferred()
            val opened = withTimeout(5_000) { openCamera(handler, epoch) }
            camera = opened
            val capture = withTimeout(5_000) { createSession(opened, surface, handler) }
            session = capture
            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, capabilities.fpsRange(config.cameraId, config.fpsMin, config.fps))
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                val af = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if (af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                if (Build.VERSION.SDK_INT >= 31 && characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                        ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
                    set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
            }.build()
            capture.setRepeatingRequest(request, null, handler)
            preview?.let { renderer.setPreview(it, previewWidth, previewHeight) }
            onActive(true, preview != null, false)
        } else if (encoding && encoder == null) startEncoder(checkNotNull(gl))
    }

    private suspend fun startEncoder(renderer: GlRenderer) {
        val selected = capabilities.encoder(config) ?: error("所选配置没有可用硬件编码器")
        val thisEpoch = epoch
        format = CompletableDeferred()
        val thisFormat = format
        val instance = object : VideoEncoder(object : GetVideoData {
            override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
                if (closed || epoch != thisEpoch || pps == null) return
                thisFormat.complete(CodecConfig(config.codec, config.outputWidth, config.outputHeight,
                    nal(sps), nal(pps), vps?.let(::nal), thisEpoch))
            }
            override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                if (closed || epoch != thisEpoch || info.size <= 0 ||
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                val data = ByteArray(info.size)
                videoBuffer.duplicate().apply { clear(); position(info.offset); limit(info.offset + info.size) }.get(data)
                onFrame(EncodedFrame(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
            }
            override fun onVideoFormat(mediaFormat: MediaFormat) = Unit
        }) {
            override fun chooseEncoder(mime: String): MediaCodecInfo = selected
        }
        encoder = instance
        instance.type = if (config.codec == VideoCodec.HEVC) com.pedro.common.VideoCodec.H265 else com.pedro.common.VideoCodec.H264
        instance.forceCodecType(CodecUtil.CodecType.HARDWARE)
        instance.setEncoderErrorCallback(object : CodecErrorCallback {
            override fun onCodecError(type: CodecUtil.CodecTypeError, e: MediaCodec.CodecException) {
                thisFormat.completeExceptionally(e); reportFailure(e)
            }
            override fun onEncodeError(type: CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
                thisFormat.completeExceptionally(e); reportFailure(e)
                return false // Replacing an encoder input surface must be coordinated with EGL.
            }
        })
        check(instance.prepareVideoEncoder(config.outputWidth, config.outputHeight, config.fps,
            config.bitrate, 0, 1, FormatVideoEncoder.SURFACE)) { "硬件编码器配置失败" }
        instance.start()
        renderer.setEncoder(instance.inputSurface)
    }

    private suspend fun openCamera(handler: Handler, thisEpoch: Long): CameraDevice = suspendCancellableCoroutine { continuation ->
        val closedSignal = deviceClosed
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            continuation.resumeWithException(SecurityException("相机权限未授权")); return@suspendCancellableCoroutine
        }
        var opened: CameraDevice? = null
        continuation.invokeOnCancellation { opened?.close() }
        try {
            context.getSystemService(CameraManager::class.java).openCamera(config.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opened = device
                    if (continuation.isActive && !closed && epoch == thisEpoch) continuation.resume(device) else device.close()
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    val error = IllegalStateException("相机已断开或被其他应用占用")
                    if (continuation.isActive) continuation.resumeWithException(error)
                    else if (!closed && epoch == thisEpoch) reportFailure(error)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    val exception = IllegalStateException("相机不可用（错误 $error），请检查隐私开关或其他相机应用")
                    if (continuation.isActive) continuation.resumeWithException(exception)
                    else if (!closed && epoch == thisEpoch) reportFailure(exception)
                }
                override fun onClosed(device: CameraDevice) { closedSignal.complete(Unit) }
            }, handler)
        } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(device: CameraDevice, surface: Surface, handler: Handler): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            var configured: CameraCaptureSession? = null
            continuation.invokeOnCancellation { configured?.close() }
            try {
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(capture: CameraCaptureSession) {
                        configured = capture
                        if (continuation.isActive && !closed) continuation.resume(capture) else capture.close()
                    }
                    override fun onConfigureFailed(capture: CameraCaptureSession) {
                        capture.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("相机会话配置失败"))
                    }
                }, handler)
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        }

    private suspend fun reconcile() {
        if (demands.isEmpty()) {
            if (preview == null) stopCapture()
            else {
                val oldEncoder = encoder; encoder = null
                try { gl?.setEncoder(null) } finally { oldEncoder?.stop() }
            }
        }
        onActive(camera != null, preview != null && camera != null, false)
    }

    private suspend fun stopCapture() {
        epoch = NEXT_EPOCH.incrementAndGet()
        val oldSession = session; session = null
        val oldCamera = camera; camera = null
        val oldGl = gl; gl = null
        val oldEncoder = encoder; encoder = null
        val oldThread = cameraThread; cameraThread = null
        var failure: Throwable? = null
        suspend fun release(action: suspend () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        withContext(NonCancellable) {
            runCatching { oldSession?.stopRepeating() }
            release { oldSession?.close() }
            release { oldCamera?.let { it.close(); withTimeoutOrNull(1200) { deviceClosed.await() } } }
            release { oldGl?.close() }
            release { oldEncoder?.stop() }
            release { oldThread?.let { it.quitSafely(); withContext(Dispatchers.IO) { it.join(1200) } } }
        }
        failure?.let { throw it }
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        demands.clear(); preview = null
        withContext(NonCancellable) { stopCapture() }
        onActive(false, false, false)
    }

    private fun reportFailure(error: Throwable) {
        if (!closed && failed.compareAndSet(false, true)) {
            format.completeExceptionally(error)
            onFailure(error.message ?: "视频管线发生错误")
        }
    }

    companion object {
        private val NEXT_EPOCH = AtomicLong()
        private fun nal(buffer: ByteBuffer): ByteArray {
            val copy = buffer.duplicate()
            val data = ByteArray(copy.remaining()); copy.get(data)
            val offset = when {
                data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
                data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> 3
                else -> 0
            }
            return data.copyOfRange(offset, data.size)
        }
    }
}
