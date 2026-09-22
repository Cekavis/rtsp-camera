package com.cekavis.rtspcamera.rtsp

internal enum class SessionPhase { CONNECTED, DESCRIBED, READY, PLAYING, PAUSED, CLOSED }

internal class SessionState {
    @Volatile var phase = SessionPhase.CONNECTED
        private set

    fun describe() {
        requireState(phase != SessionPhase.PLAYING && phase != SessionPhase.CLOSED)
        phase = SessionPhase.DESCRIBED
    }

    fun setup() {
        requireState(phase == SessionPhase.DESCRIBED || phase == SessionPhase.READY)
        phase = SessionPhase.READY
    }

    fun play() {
        requireState(phase == SessionPhase.READY || phase == SessionPhase.PAUSED || phase == SessionPhase.PLAYING)
        phase = SessionPhase.PLAYING
    }

    fun pause() {
        requireState(phase == SessionPhase.PLAYING || phase == SessionPhase.PAUSED)
        phase = SessionPhase.PAUSED
    }

    fun renegotiate() { requireState(phase != SessionPhase.CLOSED); phase = SessionPhase.CONNECTED }
    fun close() { phase = SessionPhase.CLOSED }
    private fun requireState(valid: Boolean) { if (!valid) throw RtspProtocolException(455, "Invalid session state") }
}
