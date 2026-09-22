package com.cekavis.rtspcamera.media

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only capability checks: never opens a camera or modifies the installed application's settings. */
@RunWith(AndroidJUnit4::class)
class NativeCameraCapabilitiesTest {
    @Test
    fun nativeRangesRemainWholeWithoutCreatingFixedEndpointsOrInteriorRates() {
        val native = arrayOf(Range(10, 10), Range(15, 15), Range(15, 20), Range(20, 20), Range(5, 30), Range(30, 30))
        val result = nativeFrameRateRanges(native, 0L)
        assertEquals(native.toSet(), result.toSet())
        assertEquals(native.size, result.size)
        assertTrue(result.none { it == Range(5, 5) || it == Range(24, 24) || it == Range(25, 25) })
    }

    @Test
    fun frameDurationFilteringDropsEntireRangeInsteadOfClippingIt() {
        val result = nativeFrameRateRanges(arrayOf(Range(5, 30), Range(15, 20)), 50_000_000L)
        assertEquals(listOf(Range(15, 20)), result)
        assertTrue(nativeFrameRateRanges(arrayOf(Range(5, 30)), 50_000_000L).isEmpty())
    }

    @Test
    fun nativeHighFrameRateIsNotReplacedByAHardcodedSixtyFpsCeiling() {
        val native = arrayOf(Range(30, 120))
        assertEquals(native.toList(), nativeFrameRateRanges(native, 8_333_333L))
    }

    @Test
    fun everyAdvertisedModeUsesASizeAndCompleteRangeFromCamera2() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val capabilities = CameraCapabilities(context)
        val cameras = capabilities.enumerate()
        assertTrue("设备应至少暴露一组可编码的相机能力", cameras.isNotEmpty())
        cameras.forEach { camera ->
            val characteristics = manager.getCameraCharacteristics(camera.id)
            val nativeSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { it.width to it.height }.toSet()
            val nativeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().toSet()
            camera.modes.forEach { mode ->
                assertTrue("显示的尺寸必须来自 Camera2", (mode.width to mode.height) in nativeSizes)
                assertTrue("显示的范围必须与 Camera2 原生范围完全相同", Range(mode.fpsMin, mode.fps) in nativeRanges)
            }
            camera.modes.distinctBy { it.fpsMin to it.fps }.forEach { mode ->
                assertEquals(Range(mode.fpsMin, mode.fps), capabilities.fpsRange(camera.id, mode.fpsMin, mode.fps))
            }
            val unsupportedUpper = nativeRanges.maxOf { it.upper } + 1
            try {
                capabilities.fpsRange(camera.id, 1, unsupportedUpper)
                fail("不存在的范围不得回退到包含其端点的原生范围")
            } catch (_: IllegalStateException) {
                // Expected: selection must be exact, with no fabricated or nearest-range fallback.
            }
        }
    }
}
