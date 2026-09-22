package com.cekavis.rtspcamera.media

/** Preserve an absolute sampling schedule when the requested FPS does not divide the sensor FPS. */
internal class FramePacer(fps: Int) {
    private val intervalNs = 1_000_000_000L / fps.also { require(it > 0) }
    private val toleranceNs = minOf(1_000_000L, intervalNs / 10)
    private var nextFrameNs: Long? = null

    fun shouldRender(timestampNs: Long): Boolean {
        val next = nextFrameNs
        if (next == null) {
            nextFrameNs = timestampNs + intervalNs
            return true
        }
        if (timestampNs < next - toleranceNs) return false
        val skipped = ((timestampNs - next + toleranceNs) / intervalNs).coerceAtLeast(0)
        nextFrameNs = next + (skipped + 1) * intervalNs
        return true
    }
}
