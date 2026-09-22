package com.cekavis.rtspcamera.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cekavis.rtspcamera.model.CameraOption
import com.cekavis.rtspcamera.model.CaptureMode
import com.cekavis.rtspcamera.model.VideoCodec
import com.cekavis.rtspcamera.model.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraModeSelectionTest {
    private val camera = CameraOption("back", "后置相机", listOf(
        CaptureMode(1920, 1080, 30, VideoCodec.H264),
        CaptureMode(1920, 1080, 30, VideoCodec.HEVC),
        CaptureMode(1280, 720, 30, VideoCodec.H264),
        CaptureMode(1280, 720, 60, VideoCodec.H264),
    ))

    @Test
    fun preservesSupportedHevcModeAndVisualSettings() {
        val requested = VideoConfig(cameraId = "back", codec = VideoCodec.HEVC, rotation = 90, mirror = true, showTimestamp = true)
        assertEquals(requested, fitVideoToCamera(requested, camera))
    }

    @Test
    fun unsupportedCodecAtHighFrameRateFallsBackToSupportedCombination() {
        val selected = fitVideoToCamera(VideoConfig(width = 1280, height = 720, fps = 60, codec = VideoCodec.HEVC), camera)
        assertEquals("back", selected.cameraId)
        assertEquals(1280, selected.width)
        assertEquals(720, selected.height)
        assertEquals(60, selected.fps)
        assertEquals(VideoCodec.H264, selected.codec)
    }

    @Test
    fun cameraChangeChoosesClosestSupportedSizeWithoutChangingTransform() {
        val front = CameraOption("front", "前置相机", listOf(CaptureMode(1280, 720, 30, VideoCodec.H264)))
        val selected = fitVideoToCamera(VideoConfig(cameraId = "back", fps = 60, rotation = 270, mirror = true), front)
        assertEquals("front", selected.cameraId)
        assertEquals(1280, selected.width)
        assertEquals(30, selected.fps)
        assertEquals(270, selected.rotation)
        assertEquals(true, selected.mirror)
    }

    @Test
    fun variableAndFixedRangesWithSameUpperBoundStayDistinct() {
        val nativeRanges = CameraOption("back", "后置相机", listOf(
            CaptureMode(1920, 1080, 30, VideoCodec.H264, fpsMin = 5),
            CaptureMode(1920, 1080, 30, VideoCodec.H264, fpsMin = 30),
        ))
        val variable = VideoConfig(cameraId = "back", fps = 30, fpsMin = 5)
        val fixed = variable.copy(fpsMin = 30)
        assertEquals(variable, fitVideoToCamera(variable, nativeRanges))
        assertEquals(fixed, fitVideoToCamera(fixed, nativeRanges))
    }

    @Test
    fun rangeSelectionKeepsBothNativeEndpointsWhenCodecMustChange() {
        val nativeRanges = CameraOption("back", "后置相机", listOf(
            CaptureMode(1920, 1080, 30, VideoCodec.HEVC, fpsMin = 5),
            CaptureMode(1920, 1080, 30, VideoCodec.H264, fpsMin = 15),
        ))
        val selected = fitVideoToCamera(VideoConfig(cameraId = "back", fps = 30, fpsMin = 15, codec = VideoCodec.HEVC), nativeRanges)
        assertEquals(15, selected.fpsMin)
        assertEquals(30, selected.fps)
        assertEquals(VideoCodec.H264, selected.codec)
    }
}
