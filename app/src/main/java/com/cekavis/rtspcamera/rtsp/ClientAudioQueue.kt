package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.EncodedAudioFrame
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** Independent AAC access units can drop old audio on congestion without waiting for a keyframe. */
internal class ClientAudioQueue(private val maxFrames: Int = 24, private val maxBytes: Int = 64 * 1024) {
    private val lock = Any()
    private val frames = ArrayDeque<EncodedAudioFrame>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var bytes = 0
    private var closed = false

    init { require(maxFrames > 0 && maxBytes > 0) }

    fun offer(frame: EncodedAudioFrame) = synchronized(lock) {
        if (closed || frame.data.isEmpty() || frame.data.size > minOf(maxBytes, 8191)) return@synchronized
        while (frames.size >= maxFrames || bytes + frame.data.size > maxBytes) {
            bytes -= frames.removeFirst().data.size
        }
        frames.addLast(frame)
        bytes += frame.data.size
        available.trySend(Unit)
        Unit
    }

    suspend fun take(): EncodedAudioFrame? {
        while (true) {
            synchronized(lock) {
                val next = frames.pollFirst()
                if (next != null) { bytes -= next.data.size; return next }
                if (closed) return null
            }
            if (available.receiveCatching().isClosed) return null
        }
    }

    fun clear() = synchronized(lock) { frames.clear(); bytes = 0 }
    fun close() = synchronized(lock) { closed = true; frames.clear(); bytes = 0; available.close(); Unit }
    internal fun bufferedBytes(): Int = synchronized(lock) { bytes }
    internal fun bufferedFrames(): Int = synchronized(lock) { frames.size }
}
