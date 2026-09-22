package com.cekavis.rtspcamera.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameRateTest {
    @Test
    fun legacyConstructorsContinueToDescribeFixedFrameRates() {
        assertEquals(20, VideoConfig(fps = 20).fpsMin)
        assertEquals(20, CaptureMode(1280, 720, 20, VideoCodec.H264).fpsMin)
    }

    @Test
    fun labelsDistinguishVariableAndFixedRanges() {
        assertEquals("5–30 fps", VideoConfig(fps = 30, fpsMin = 5).fpsLabel)
        assertEquals("15–20 fps", CaptureMode(1280, 720, 20, VideoCodec.HEVC, fpsMin = 15).fpsLabel)
        assertEquals("固定 30 fps", VideoConfig(fps = 30, fpsMin = 30).fpsLabel)
    }
}
