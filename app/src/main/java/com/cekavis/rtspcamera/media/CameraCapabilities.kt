package com.cekavis.rtspcamera.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import com.cekavis.rtspcamera.model.*

class CameraCapabilities(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val encoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && !it.name.endsWith(".secure") &&
                if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated
                else !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.")
        }
    }

    fun encoder(config: VideoConfig): MediaCodecInfo? = encoders.firstOrNull { encoder ->
        runCatching {
            val caps = encoder.getCapabilitiesForType(config.codec.mime)
            val video = caps.videoCapabilities ?: return@runCatching false
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                video.areSizeAndRateSupported(config.outputWidth, config.outputHeight, config.fps.toDouble()) &&
                video.bitrateRange.contains(config.bitrate)
        }.getOrDefault(false)
    }

    fun enumerate(): List<CameraOption> = manager.cameraIdList.mapNotNull { id ->
        runCatching {
            val c = manager.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val modes = mutableListOf<CaptureMode>()
            map.getOutputSizes(SurfaceTexture::class.java).orEmpty().forEach { size ->
                val duration = runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrDefault(0L)
                nativeFrameRateRanges(ranges, duration).forEach { range ->
                    VideoCodec.entries.forEach { codec ->
                        val config = VideoConfig(id, size.width, size.height, range.upper, codec = codec, fpsMin = range.lower)
                        if (encoder(config) != null) modes += CaptureMode(size.width, size.height, range.upper, codec, range.lower)
                    }
                }
            }
            val side = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                else -> "后置"
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            CameraOption(id, "$side · 相机 $id" + (focal?.let { " · ${"%.1f".format(it)} mm" } ?: ""),
                modes.sortedWith(compareByDescending<CaptureMode> { it.width * it.height }
                    .thenByDescending { it.fps }.thenByDescending { it.fpsMin }))
        }.getOrNull()
    }.filter { it.modes.isNotEmpty() }

    fun validate(config: AppConfig, cameras: List<CameraOption>) {
        require(config.server.port in 1024..65535) { "端口必须为 1024–65535" }
        require(config.video.rotation in listOf(0, 90, 180, 270)) { "不支持的旋转角度" }
        val video = config.video
        require(video.fpsMin > 0 && video.fpsMin <= video.fps) { "AE 帧率范围无效，请在设置中重新选择" }
        require(cameras.any { camera -> camera.id == video.cameraId && camera.modes.any {
            it.width == video.width && it.height == video.height && it.fpsMin == video.fpsMin &&
                it.fps == video.fps && it.codec == video.codec
        } }) { "相机未提供此原生分辨率、AE 帧率范围或编码组合，请在设置中重新选择" }
        require(encoder(video) != null) { "硬件编码器不支持此输出尺寸、旋转角度或码率" }
        if (config.server.authEnabled) {
            require(config.server.username.isNotBlank() && config.server.password.isNotEmpty()) { "请设置用户名和密码" }
            require(config.server.username.none { it == ':' || it == '\r' || it == '\n' || it == '"' } &&
                config.server.username.length <= 128 && config.server.password.length <= 1024) { "用户名或密码格式无效" }
        }
    }

    fun characteristics(id: String): CameraCharacteristics = manager.getCameraCharacteristics(id)

    fun fpsRange(id: String, fpsMin: Int, fps: Int): Range<Int> = characteristics(id)
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        .firstOrNull { it.lower == fpsMin && it.upper == fps }
        ?: error("相机未暴露所选 AE 帧率范围，请在设置中重新选择")
}

/** Keeps only complete Camera2 ranges; never derives fixed rates or clips range endpoints. */
internal fun nativeFrameRateRanges(available: Array<out Range<Int>>, minFrameDurationNs: Long): List<Range<Int>> =
    available.filter { range ->
        range.lower > 0 && (minFrameDurationNs <= 0 || range.upper <= 1_000_000_000.0 / minFrameDurationNs + .1)
    }.distinct().sortedWith(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
