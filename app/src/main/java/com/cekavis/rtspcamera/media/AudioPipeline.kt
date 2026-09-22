package com.cekavis.rtspcamera.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.cekavis.rtspcamera.model.AudioCodecConfig
import com.cekavis.rtspcamera.model.AudioConfig
import com.cekavis.rtspcamera.model.EncodedAudioFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One microphone/AAC encoder shared by PLAY clients. Preview and idle clients never record audio. */
class AudioPipeline(
    private val context: Context,
    private val config: AudioConfig,
    private val onFrame: (EncodedAudioFrame) -> Unit,
    private val onActive: (Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val callbacks = Any()
    private val clients = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: Capture? = null
    private var closed = false

    suspend fun acquire(clientId: String) = mutex.withLock {
        check(!closed) { "音频服务已停止" }
        if (!config.enabled) return@withLock
        capture?.failure?.let { throw it }
        if (!clients.add(clientId)) return@withLock
        try {
            val session = capture ?: Capture().also { next ->
                synchronized(callbacks) { capture = next }
                next.job = scope.launch { record(next) }
            }
            withTimeout(8_000) { session.ready.await() }
        } catch (error: Throwable) {
            clients -= clientId
            if (clients.isEmpty()) withContext(NonCancellable) { stopCapture() }
            throw error
        }
    }

    suspend fun release(clientId: String) = mutex.withLock {
        clients -= clientId
        if (clients.isEmpty()) withContext(NonCancellable) { stopCapture() }
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        synchronized(callbacks) { closed = true }
        clients.clear()
        try { withContext(NonCancellable) { stopCapture() } }
        finally { scope.cancel() }
    }

    private suspend fun stopCapture() {
        val old = synchronized(callbacks) {
            capture?.also { it.stopping = true; capture = null }
        } ?: return
        old.job?.cancelAndJoin()
        onActive(false)
        old.cleanupFailure?.let { throw it }
    }

    private suspend fun record(session: Capture) {
        var recorder: AudioRecord? = null
        var encoder: MediaCodec? = null
        var route: BluetoothMicrophoneRoute? = null
        var encoderStarted = false
        var failure: Throwable? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("麦克风权限未授权，请在应用中允许麦克风访问后重试")
            val device = MicrophoneInputs(context).resolve(config.deviceKey)
            if (device != null && isBluetoothMicrophone(device.type)) {
                route = BluetoothMicrophoneRoute(context.getSystemService(AudioManager::class.java), device)
                route.open()
            }
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 48 kHz 单声道麦克风录音" }
            recorder = AudioRecord.Builder()
                .setAudioSource(if (route != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE))
                .build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败，请检查设备连接和麦克风隐私开关" }
            if (device != null) check(recorder.setPreferredDevice(device)) { "系统无法选择此麦克风，请选择其他音频来源" }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BUFFER_BYTES)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败，请检查权限、隐私开关或其他录音应用" }
            encode(session, recorder, encoder, device)
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun release(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (session.cleanupFailure == null) session.cleanupFailure = error
                    else session.cleanupFailure?.addSuppressed(error)
                }
            }
            release { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
            release { recorder?.release() }
            release { if (encoderStarted) encoder?.stop() }
            release { encoder?.release() }
            release { route?.close() }
            if (failure == null || failure is CancellationException) {
                if (session.cleanupFailure != null) failure = session.cleanupFailure
            } else session.cleanupFailure?.let { failure?.addSuppressed(it) }
            val error = failure
            if (error != null && error !is CancellationException) session.failure = error
            session.ready.completeExceptionally(error ?: CancellationException("麦克风录音已停止"))
            synchronized(callbacks) {
                if (capture === session && !closed && !session.stopping) {
                    onActive(false)
                    if (error != null && error !is CancellationException)
                        onFailure(error.message ?: "麦克风录音或 AAC 编码失败")
                }
            }
        }
    }

    private suspend fun encode(session: Capture, recorder: AudioRecord, encoder: MediaCodec, device: AudioDeviceInfo?) {
        val pcm = ByteArray(PCM_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        val timestamp = AudioTimestamp()
        val clock = AudioSampleClock(SAMPLE_RATE)
        val startedUs = System.nanoTime() / 1_000
        var lastProgressUs = startedUs
        var framesRead = 0L
        var pendingBytes = 0
        var pendingPtsUs = 0L
        var routeConfirmed = false
        var formatConfirmed = false
        var active = false
        while (currentCoroutineContext().isActive) {
            val nowUs = System.nanoTime() / 1_000
            check(nowUs - lastProgressUs < 5_000_000) { "麦克风或音频编码器没有输出，请检查设备连接或其他录音应用" }
            if (device != null) {
                val matches = recorder.routedDevice?.id == device.id
                if (!matches && routeConfirmed) error("所选麦克风已断开或系统切换了录音设备，请重新选择音频来源")
                if (!matches) {
                    check(nowUs - startedUs < 5_000_000) { "系统未能路由到所选麦克风，请重新连接或选择其他音频来源" }
                    val skipped = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                    checkRead(skipped)
                    framesRead += skipped / BYTES_PER_SAMPLE
                    delay(10)
                    continue
                }
                routeConfirmed = true
            }
            drain@ while (true) {
                when (val output = encoder.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break@drain
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = encoder.outputFormat
                        check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE &&
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) { "AAC 编码器输出格式不受支持" }
                        val specific = format.getByteBuffer("csd-0")?.duplicate()
                        val expected = AudioCodecConfig().config
                        check(specific != null && specific.remaining() >= expected.size && expected.all { specific.get() == it }) {
                            "AAC 编码器未输出所需的 AAC-LC 格式"
                        }
                        formatConfirmed = true
                    }
                    else -> if (output >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(formatConfirmed) { "AAC 编码器尚未提供音频格式" }
                                val buffer = checkNotNull(encoder.getOutputBuffer(output)).duplicate()
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val data = ByteArray(info.size).also { buffer.get(it) }
                                synchronized(callbacks) {
                                    if (capture === session && !closed && !session.stopping) {
                                        if (!active) {
                                            active = true
                                            onActive(true)
                                            session.ready.complete(Unit)
                                        }
                                        onFrame(EncodedAudioFrame(data, info.presentationTimeUs))
                                    }
                                }
                                lastProgressUs = System.nanoTime() / 1_000
                            }
                        } finally { encoder.releaseOutputBuffer(output, false) }
                    }
                }
            }
            if (pendingBytes == 0) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                checkRead(count)
                if (count == 0) { delay(5); continue }
                val firstFrame = framesRead
                framesRead += count / BYTES_PER_SAMPLE
                if (!clock.initialized) {
                    val haveTimestamp = recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    val readEndUs = System.nanoTime() / 1_000
                    // Give the HAL time to expose its capture clock before using a read-time estimate.
                    if (!haveTimestamp && readEndUs - startedUs < 250_000) continue
                    clock.anchor(framesRead, readEndUs,
                        if (haveTimestamp) timestamp.framePosition else null,
                        if (haveTimestamp) timestamp.nanoTime else null)
                }
                pendingBytes = count
                pendingPtsUs = clock.presentationTimeUs(firstFrame)
            }
            val input = encoder.dequeueInputBuffer(10_000)
            if (input >= 0) {
                val buffer = checkNotNull(encoder.getInputBuffer(input))
                buffer.clear()
                check(buffer.remaining() >= pendingBytes) { "AAC 编码器输入缓冲区过小" }
                buffer.put(pcm, 0, pendingBytes)
                encoder.queueInputBuffer(input, 0, pendingBytes, pendingPtsUs, 0)
                pendingBytes = 0
            }
        }
    }

    private fun checkRead(count: Int) {
        check(count >= 0) {
            if (count == AudioRecord.ERROR_DEAD_OBJECT) "麦克风已断开或音频服务已重启，请重新启动服务"
            else "读取麦克风失败（错误 $count），请检查权限和设备连接"
        }
        check(count % BYTES_PER_SAMPLE == 0) { "麦克风返回了不完整的音频采样" }
    }

    private class Capture {
        val ready = CompletableDeferred<Unit>()
        var job: Job? = null
        var stopping = false
        @Volatile var failure: Throwable? = null
        var cleanupFailure: Throwable? = null
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_SAMPLE = 2
        private const val PCM_BUFFER_BYTES = 1_024 * BYTES_PER_SAMPLE
    }
}

/** Anchors PCM frame positions once to CLOCK_MONOTONIC, shared with the video pipeline. */
internal class AudioSampleClock(private val sampleRate: Int) {
    private var originUs: Long? = null
    val initialized get() = originUs != null

    fun anchor(framesRead: Long, readEndUs: Long, timestampFrame: Long?, timestampNanoTime: Long?) {
        if (initialized) return
        originUs = if (timestampFrame != null && timestampNanoTime != null)
            timestampNanoTime / 1_000 - durationUs(timestampFrame)
        else readEndUs - durationUs(framesRead)
    }

    fun presentationTimeUs(frame: Long): Long = checkNotNull(originUs) + durationUs(frame)

    private fun durationUs(frames: Long): Long =
        frames / sampleRate * 1_000_000 + frames % sampleRate * 1_000_000 / sampleRate
}

/** Holds only this capture's communication/SCO request and restores mode on every exit path. */
private class BluetoothMicrophoneRoute(private val manager: AudioManager, private val input: AudioDeviceInfo) {
    private var previousMode: Int? = null
    private var previousSco = false
    private var requested = false

    @Suppress("DEPRECATION")
    fun open() {
        check(manager.mode == AudioManager.MODE_NORMAL) { "通话期间无法切换蓝牙麦克风，请结束通话后重试" }
        previousMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val output = checkNotNull(communicationDevice(manager, input)) { "所选蓝牙麦克风没有可用录音路由" }
            check(manager.setCommunicationDevice(output)) { "系统拒绝了蓝牙麦克风路由，请重新连接设备" }
            requested = true
        } else {
            check(manager.isBluetoothScoAvailableOffCall) { "此设备不支持蓝牙麦克风录音" }
            previousSco = manager.isBluetoothScoOn
            requested = true
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
        }
    }

    @Suppress("DEPRECATION")
    fun close() {
        try {
            if (requested) {
                if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
                else try { manager.stopBluetoothSco() } finally { manager.isBluetoothScoOn = previousSco }
            }
        } finally {
            previousMode?.let { if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = it }
        }
    }
}
