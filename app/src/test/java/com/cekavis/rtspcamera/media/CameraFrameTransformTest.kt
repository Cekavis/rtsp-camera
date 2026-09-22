package com.cekavis.rtspcamera.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CameraFrameTransformTest {
    @Test fun removesMeasuredNothingA142AxisSwap() {
        val measured = floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val result = FloatArray(16)
        CameraFrameTransform.texture(measured, result)
        assertArrayEquals(affine(1f, 0f, 0f, -1f, 0f, 1f), result, .00001f)
    }

    @Test fun removesAllProducerOrientationsWhilePreservingAsymmetricCrop() {
        // Explicit D4 fixtures, independently expressed as the four sampled corners.
        val transforms = listOf(
            affine(1f, 0f, 0f, -1f, 0f, 1f), affine(-1f, 0f, 0f, -1f, 1f, 1f),
            affine(1f, 0f, 0f, 1f, 0f, 0f), affine(-1f, 0f, 0f, 1f, 1f, 0f),
            affine(0f, -1f, -1f, 0f, 1f, 1f), affine(0f, 1f, -1f, 0f, 0f, 1f),
            affine(0f, -1f, 1f, 0f, 1f, 0f), affine(0f, 1f, 1f, 0f, 0f, 0f),
        )
        for (transform in transforms) {
            // Texture occupies x=[.12,.92], y=[.07,.67], deliberately off-center.
            transform[0] *= .8f; transform[4] *= .8f; transform[12] = .12f + .8f * transform[12]
            transform[1] *= .6f; transform[5] *= .6f; transform[13] = .07f + .6f * transform[13]
            val result = FloatArray(16)
            CameraFrameTransform.texture(transform, result)
            assertArrayEquals(affine(.8f, 0f, 0f, -.6f, .12f, .67f), result, .00001f)
        }
    }

    @Test fun leavesUnrotatedSurfaceTextureSamplingFlipIntact() {
        val texture = affine(1f, 0f, 0f, -1f, 0f, 1f)
        val result = FloatArray(16)
        CameraFrameTransform.texture(texture, result)
        assertArrayEquals(texture, result, 0f)
    }

    private fun affine(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float) =
        floatArrayOf(a, c, 0f, 0f, b, d, 0f, 0f, 0f, 0f, 1f, 0f, x, y, 0f, 1f)
}
