package com.cekavis.rtspcamera.service

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.*
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.cekavis.rtspcamera.CameraApplication
import com.cekavis.rtspcamera.R
import com.cekavis.rtspcamera.media.MediaPipeline
import com.cekavis.rtspcamera.model.*
import com.cekavis.rtspcamera.rtsp.RtspServer
import com.cekavis.rtspcamera.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val graph get() = (application as CameraApplication).controller
    @Volatile private var pipeline: MediaPipeline? = null
    @Volatile private var server: RtspServer? = null
    @Volatile private var previewTarget: Triple<Surface, Int, Int>? = null
    private var config = AppConfig()
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var stopping = false
    private var ticker: Job? = null
    private var networkJob: Job? = null
    private var startedAt = 0L
    private var currentAddresses = emptyList<String>()
    private val frames = AtomicLong()
    private val bytes = AtomicLong()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = networkChanged()
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            graph.update { it.copy(batteryPercent = if (level >= 0) level * 100 / scale else -1,
                batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph.service = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "相机服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "持续显示相机与 RTSP 服务状态"; setShowBadge(false)
            })
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { requestStop(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            graph.error("系统未允许恢复相机服务，请打开应用后重新启动")
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch {
            graph.state.first { it.loaded }
            mutex.withLock {
                if (running || stopping) return@withLock
                try {
                    config = graph.state.value.config
                    require(config.server.authConfigured) { "请打开应用重新确认访问保护设置" }
                    graph.capabilities.validate(config, graph.cameras.value)
                    acquireWakeLock()
                    startStack(config)
                    running = true
                    startedAt = SystemClock.elapsedRealtime()
                    startTicker()
                } catch (e: Exception) {
                    graph.error(e.message ?: "RTSP 服务启动失败")
                    stopStack()
                    releaseWakeLock()
                    withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startStack(newConfig: AppConfig) {
        val newPipeline = MediaPipeline(this, newConfig.video, graph.capabilities,
            battery = { graph.state.value.batteryPercent },
            onFrame = { frame -> bytes.addAndGet(frame.data.size.toLong()); server?.publish(frame) },
            onFrameRendered = { frames.incrementAndGet() },
            onActive = { active, preview, opening -> graph.update { old ->
                old.copy(cameraActive = active, previewActive = preview,
                    phase = when { opening -> StreamPhase.STARTING; active -> StreamPhase.STREAMING; else -> StreamPhase.IDLE })
            } },
            onFailure = { reason -> recoverPipeline(reason) })
        pipeline = newPipeline
        val newServer = RtspServer(newConfig.server, object : RtspCallbacks {
            override suspend fun acquireVideo(clientId: String): CodecConfig = try {
                newPipeline.acquire(clientId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                graph.error(error.message ?: "相机或硬件编码器无法启动，请检查所选配置")
                throw error
            }
            override suspend fun releaseVideo(clientId: String) { newPipeline.release(clientId) }
            override fun requestKeyFrame() = newPipeline.requestKeyFrame()
            override fun onClientCounts(connected: Int, playing: Int) {
                graph.update { it.copy(connectedClients = connected, playingClients = playing) }
            }
            override fun onError(message: String) { graph.error(message) }
        })
        server = newServer
        newServer.start()
        config = newConfig
        currentAddresses = AppGraph.addresses(newConfig.server.port)
        graph.update { it.copy(serviceRunning = true, phase = StreamPhase.IDLE, addresses = currentAddresses,
            connectedClients = 0, playingClients = 0, actualFps = 0f, actualBitrate = 0) }
        previewTarget?.takeIf { it.first.isValid }?.let { newPipeline.attachPreview(it.first, it.second, it.third) }
    }

    private suspend fun stopStack() {
        val oldServer = server; server = null
        val oldPipeline = pipeline; pipeline = null
        try { oldServer?.stop() }
        finally {
            try { oldPipeline?.close() }
            finally { frames.set(0); bytes.set(0) }
        }
    }

    private suspend fun failStack(message: String) {
        try { stopStack() }
        finally {
            running = false
            ticker?.cancel()
            releaseWakeLock()
            graph.update { it.copy(serviceRunning = false, phase = StreamPhase.ERROR, cameraActive = false,
                previewActive = false, connectedClients = 0, playingClients = 0,
                actualFps = 0f, actualBitrate = 0, error = message) }
        }
    }

    suspend fun replaceConfig(newConfig: AppConfig) = mutex.withLock {
        if (!running || stopping) return@withLock
        val old = config
        stopStack()
        try { startStack(newConfig) } catch (error: Exception) {
            stopStack()
            try { startStack(old) } catch (_: Exception) {
                failStack("无法恢复原配置，请检查相机状态后重新启动服务")
            }
            throw error
        }
    }

    fun setPreview(surface: Surface?, width: Int, height: Int) {
        previewTarget = surface?.let { Triple(it, width, height) }
        scope.launch {
            mutex.withLock {
                if (!running || stopping) return@withLock
                val target = previewTarget
                try {
                    if (target == null) pipeline?.detachPreview()
                    else pipeline?.attachPreview(target.first, target.second, target.third)
                } catch (e: Exception) { graph.error(e.message ?: "预览无法启动") }
            }
        }
    }

    private fun recoverPipeline(reason: String) {
        scope.launch {
            mutex.withLock {
                if (!running || stopping) return@withLock
                graph.error(reason)
                try { stopStack(); startStack(config) }
                catch (_: Exception) {
                    failStack("相机恢复失败：$reason")
                }
            }
        }
    }

    private fun networkChanged() {
        networkJob?.cancel()
        networkJob = scope.launch {
            delay(750)
            mutex.withLock {
                if (!running || stopping) return@withLock
                val updated = AppGraph.addresses(config.server.port)
                if (updated != currentAddresses) {
                    // Debouncing may cancel a pending change, never a half-completed rebuild.
                    withContext(NonCancellable) {
                        try { stopStack(); startStack(config) }
                        catch (_: Exception) { failStack("网络变化后监听失败，请重新启动服务") }
                    }
                }
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var last = SystemClock.elapsedRealtime()
            var lastNotification = ""
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - last).coerceAtLeast(1)
                val fps = frames.getAndSet(0) * 1000f / elapsed
                val bitrate = bytes.getAndSet(0) * 8000 / elapsed
                graph.update { it.copy(actualFps = fps, actualBitrate = bitrate, uptimeSeconds = (now - startedAt) / 1000) }
                last = now
                val status = notificationText()
                if (status != lastNotification) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    lastNotification = status
                }
            }
        }
    }

    fun requestStop() {
        scope.launch {
            mutex.withLock {
                stopping = true
                ticker?.cancel(); networkJob?.cancel()
                previewTarget = null
                stopStack()
                running = false
                releaseWakeLock()
                graph.update { it.copy(serviceRunning = false, phase = StreamPhase.STOPPED, cameraActive = false,
                    previewActive = false, connectedClients = 0, playingClients = 0, actualFps = 0f, actualBitrate = 0,
                    uptimeSeconds = 0) }
                withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:rtsp-listener").apply { setReferenceCounted(false) }
        wakeLock?.acquire()
    }
    private fun releaseWakeLock() { wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null }

    private fun notificationText(): String {
        val state = graph.state.value
        return when {
            state.phase == StreamPhase.STARTING -> "正在启动相机"
            state.cameraActive -> "相机运行中 · ${state.playingClients} 个客户端" + if (state.previewActive) " · 本机预览" else ""
            state.phase == StreamPhase.ERROR -> "服务需要处理 · 打开应用查看"
            else -> "监听待机 · 相机已关闭"
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_camera).setContentTitle("RTSP Camera")
            .setContentText(notificationText()).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(0, "停止", stop).build()
    }

    override fun onDestroy() {
        if (graph.service === this) graph.service = null
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        ticker?.cancel(); networkJob?.cancel()
        scope.launch {
            mutex.withLock {
                stopStack(); releaseWakeLock()
                if (graph.service == null) graph.update { it.copy(serviceRunning = false, phase = StreamPhase.STOPPED,
                    cameraActive = false, previewActive = false, connectedClients = 0, playingClients = 0,
                    actualFps = 0f, actualBitrate = 0) }
            }
            scope.cancel()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.cekavis.rtspcamera.START"
        const val STOP = "com.cekavis.rtspcamera.STOP"
        private const val CHANNEL = "camera_service"
        private const val NOTIFICATION_ID = 1001
    }
}
