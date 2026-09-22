package com.cekavis.rtspcamera.model

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

enum class VideoCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264"), HEVC("video/hevc", "HEVC / H.265")
}

data class VideoConfig(
    val cameraId: String = "0",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 4_000_000,
    val codec: VideoCodec = VideoCodec.H264,
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val showTimestamp: Boolean = false,
    val showBattery: Boolean = false,
    val fpsMin: Int = fps,
) {
    val outputWidth get() = if (rotation % 180 == 0) width else height
    val outputHeight get() = if (rotation % 180 == 0) height else width
    val fpsLabel get() = frameRateLabel(fpsMin, fps)
}

data class ServerConfig(
    val port: Int = 8554,
    val authEnabled: Boolean = false,
    val username: String = "camera",
    val password: String = "",
    val authConfigured: Boolean = false,
) {
    override fun toString() = "ServerConfig(port=$port, authEnabled=$authEnabled, credentials=<redacted>)"
}

data class AppConfig(
    val video: VideoConfig = VideoConfig(),
    val server: ServerConfig = ServerConfig(),
    val keepScreenOn: Boolean = true,
)

data class CaptureMode(val width: Int, val height: Int, val fps: Int, val codec: VideoCodec, val fpsMin: Int = fps) {
    val fpsLabel get() = frameRateLabel(fpsMin, fps)
}

private fun frameRateLabel(min: Int, max: Int) = if (min == max) "固定 $max fps" else "$min–$max fps"
data class CameraOption(val id: String, val label: String, val modes: List<CaptureMode>)
enum class StreamPhase { STOPPED, IDLE, STARTING, STREAMING, ERROR }
data class AppState(
    val config: AppConfig = AppConfig(),
    val loaded: Boolean = false,
    val serviceRunning: Boolean = false,
    val phase: StreamPhase = StreamPhase.STOPPED,
    val addresses: List<String> = emptyList(),
    val connectedClients: Int = 0,
    val playingClients: Int = 0,
    val cameraActive: Boolean = false,
    val previewActive: Boolean = false,
    val actualFps: Float = 0f,
    val actualBitrate: Long = 0,
    val uptimeSeconds: Long = 0,
    val batteryPercent: Int = -1,
    val batteryTemperature: Float = 0f,
    val batteryOptimizationExempt: Boolean = false,
    val applyingSettings: Boolean = false,
    val error: String? = null,
)

data class CodecConfig(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val sps: ByteArray,
    val pps: ByteArray,
    val vps: ByteArray? = null,
    val generation: Long = 0,
) {
    fun sameParameters(other: CodecConfig) = codec == other.codec && width == other.width &&
        height == other.height && sps.contentEquals(other.sps) && pps.contentEquals(other.pps) &&
        (vps?.contentEquals(other.vps ?: byteArrayOf()) ?: (other.vps == null))
}
data class EncodedFrame(val data: ByteArray, val presentationTimeUs: Long, val isKeyFrame: Boolean)

interface RtspCallbacks {
    suspend fun acquireVideo(clientId: String): CodecConfig
    suspend fun releaseVideo(clientId: String)
    fun requestKeyFrame()
    fun onClientCounts(connected: Int, playing: Int)
    fun onError(message: String)
}

interface AppController {
    val state: StateFlow<AppState>
    val cameras: StateFlow<List<CameraOption>>
    fun startService()
    fun stopService()
    fun applyConfig(config: AppConfig)
    fun attachPreview(surface: Surface, width: Int, height: Int)
    fun detachPreview()
    fun clearError()
    fun refreshCapabilities()
}
