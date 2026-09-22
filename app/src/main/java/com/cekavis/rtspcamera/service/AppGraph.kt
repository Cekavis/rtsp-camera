package com.cekavis.rtspcamera.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import androidx.core.content.ContextCompat
import com.cekavis.rtspcamera.data.SettingsRepository
import com.cekavis.rtspcamera.media.CameraCapabilities
import com.cekavis.rtspcamera.media.MicrophoneInputs
import com.cekavis.rtspcamera.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface

class AppGraph(private val context: Context) : AppController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val capabilities = CameraCapabilities(context)
    private val microphoneInputs = MicrophoneInputs(context)
    private val repository = SettingsRepository(context)
    private val settingsMutex = Mutex()
    private val mutableState = MutableStateFlow(AppState())
    private val mutableCameras = MutableStateFlow<List<CameraOption>>(emptyList())
    private val mutableMicrophones = MutableStateFlow<List<MicrophoneOption>>(emptyList())
    override val state = mutableState.asStateFlow()
    override val cameras = mutableCameras.asStateFlow()
    override val microphones = mutableMicrophones.asStateFlow()
    @Volatile internal var service: CameraService? = null

    init {
        context.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshMicrophones()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshMicrophones()
        }, Handler(Looper.getMainLooper()))
        scope.launch {
            val config = try { repository.read() } catch (_: Exception) {
                update { it.copy(error = "无法读取保存的设置或解密凭据，请重新配置") }
                AppConfig()
            }
            refreshCapabilitiesNow()
            update { it.copy(config = config, loaded = true, addresses = addresses(config.server.port),
                batteryOptimizationExempt = batteryExempt()) }
        }
    }

    internal fun update(transform: (AppState) -> AppState) = mutableState.update(transform)
    internal fun error(message: String) = update { it.copy(error = message) }

    override fun startService() {
        if (!state.value.loaded || state.value.applyingSettings) return
        if (!state.value.config.server.authConfigured) { error("请先选择认证方式"); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error("请先授权相机权限"); return
        }
        if (state.value.config.audio.enabled && !microphonePermissionGranted()) {
            error("请先授权麦克风权限，或在设置中关闭音频"); return
        }
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context,
                "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            error("请先授权局域网访问权限"); return
        }
        try {
            clearError()
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java).setAction(CameraService.START))
        } catch (_: Exception) { error("无法启动相机前台服务，请回到应用界面重试") }
    }

    override fun stopService() { service?.requestStop() }

    override fun applyConfig(config: AppConfig) {
        if (state.value.applyingSettings) return
        update { it.copy(applyingSettings = true, error = null) }
        scope.launch {
            settingsMutex.withLock {
                val previous = state.value.config
                try {
                    capabilities.validate(config, cameras.value)
                    validateAudio(config.audio)
                    val active = service
                    if (config.video != previous.video || config.audio != previous.audio || config.server != previous.server) active?.replaceConfig(config)
                    try { repository.save(config) } catch (e: Exception) {
                        active?.replaceConfig(previous)
                        throw e
                    }
                    update { it.copy(config = config, addresses = addresses(config.server.port), error = null) }
                } catch (e: Exception) {
                    error(e.message ?: "无法应用设置，原配置已保留")
                } finally { update { it.copy(applyingSettings = false) } }
            }
        }
    }

    override fun attachPreview(surface: Surface, width: Int, height: Int) { service?.setPreview(surface, width, height) }
    override fun detachPreview() { service?.setPreview(null, 0, 0) }
    override fun clearError() { update { it.copy(error = null) } }
    override fun refreshCapabilities() { scope.launch { refreshCapabilitiesNow(); update { it.copy(batteryOptimizationExempt = batteryExempt()) } } }

    private suspend fun refreshCapabilitiesNow() = withContext(Dispatchers.Default) {
        try { mutableCameras.value = capabilities.enumerate() }
        catch (_: Exception) { error("无法枚举相机能力，请检查相机权限") }
        refreshMicrophones()
    }

    private fun refreshMicrophones() {
        try { mutableMicrophones.value = microphoneInputs.enumerate() }
        catch (_: Exception) { error("无法读取麦克风列表，请检查设备连接") }
    }

    internal fun validateAudio(audio: AudioConfig) {
        if (!audio.enabled) return
        require(microphonePermissionGranted()) { "请先授权麦克风权限，或在设置中关闭音频" }
        require(audio.deviceKey == null || microphoneInputs.enumerate().any { it.key == audio.deviceKey }) {
            "所选麦克风不可用，请重新连接或选择其他来源"
        }
    }

    private fun microphonePermissionGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    companion object {
        fun addresses(port: Int): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter {
                it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni")
            }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { "rtsp://${it.hostAddress}:$port/live" }.distinct()
        }.getOrDefault(emptyList())
    }
}
