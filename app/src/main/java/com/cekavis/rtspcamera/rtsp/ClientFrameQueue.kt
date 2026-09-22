package com.cekavis.rtspcamera.rtsp

import com.cekavis.rtspcamera.model.EncodedFrame
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** Bounded by both bytes and frames. After loss, dependents are discarded until a keyframe. */
internal class ClientFrameQueue(private val maxFrames: Int = 30, private val maxBytes: Int = 4 * 1024 * 1024) {
    enum class Offer { ACCEPTED, WAITING_FOR_KEYFRAME, RESYNC_REQUIRED, CLOSED }
    private val lock = Any()
    private val frames = ArrayDeque<EncodedFrame>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var bytes = 0
    private var waitingForKeyframe = true
    private var closed = false

    init { require(maxFrames > 0 && maxBytes > 0) }

    fun offer(frame: EncodedFrame): Offer = synchronized(lock) {
        if (closed) return@synchronized Offer.CLOSED
        if (waitingForKeyframe && !frame.isKeyFrame) return@synchronized Offer.WAITING_FOR_KEYFRAME
        val overflow = frames.size >= maxFrames || frame.data.size > maxBytes - bytes
        if (overflow) {
            frames.clear()
            bytes = 0
            waitingForKeyframe = true
            if (!frame.isKeyFrame || frame.data.size > maxBytes) return@synchronized Offer.RESYNC_REQUIRED
        }
        frames.addLast(frame)
        bytes += frame.data.size
        waitingForKeyframe = false
        available.trySend(Unit)
        if (overflow) Offer.RESYNC_REQUIRED else Offer.ACCEPTED
    }

    suspend fun take(): EncodedFrame? {
        while (true) {
            synchronized(lock) {
                val next = frames.pollFirst()
                if (next != null) { bytes -= next.data.size; return next }
                if (closed) return null
            }
            if (available.receiveCatching().isClosed) return null
        }
    }

    fun clear() = synchronized(lock) { frames.clear(); bytes = 0; waitingForKeyframe = true }
    fun close() = synchronized(lock) { closed = true; frames.clear(); bytes = 0; available.close(); Unit }
    internal fun bufferedBytes(): Int = synchronized(lock) { bytes }
    internal fun bufferedFrames(): Int = synchronized(lock) { frames.size }
}
