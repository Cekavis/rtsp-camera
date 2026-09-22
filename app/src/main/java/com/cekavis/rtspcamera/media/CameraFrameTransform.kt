package com.cekavis.rtspcamera.media

import kotlin.math.hypot

/** Camera2's producer orientation is already included in SurfaceTexture's matrix.
 *
 * AOSP composes it as FlipV * Crop * ProducerTransform. Keep FlipV and the sampling crop,
 * but remove the producer's rotation/mirroring before applying our explicit output transform.
 * Otherwise a 90-degree producer transform stretches H/W content into a W/H viewport.
 * See frameworks/native/libs/gui/GLConsumerUtils.cpp, computeTransformMatrix(), and
 * CameraX's integration/core/OpenGLRenderer.java, updateModelTransform().
 */
internal object CameraFrameTransform {
    fun texture(source: FloatArray, target: FloatArray) {
        val a = source[0]
        val b = source[4]
        val c = source[1]
        val d = source[5]
        val xScale = hypot(a, b)
        val yScale = hypot(c, d)
        require(xScale > 0f && yScale > 0f) { "相机纹理变换无效" }
        source.copyInto(target)
        // Producer transforms are orthogonal, centered on (0.5, 0.5). Multiplying by
        // their inverse reduces the linear block to these scales. Adjust translation
        // about the same center so asymmetric crops and the half-texel inset survive.
        target[0] = xScale
        target[4] = 0f
        target[1] = 0f
        target[5] = -yScale
        target[12] = source[12] + (a + b - xScale) * .5f
        target[13] = source[13] + (c + d + yScale) * .5f
    }

    /** Clockwise rotation followed by horizontal mirroring in the final output coordinates. */
    fun model(rotation: Int, mirror: Boolean, target: FloatArray) {
        val quarter = ((rotation % 360 + 360) % 360) / 90
        val cosine = when (quarter) { 0 -> 1f; 2 -> -1f; else -> 0f }
        val sine = when (quarter) { 1 -> -1f; 3 -> 1f; else -> 0f }
        val horizontal = if (mirror) -1f else 1f
        target.fill(0f)
        target[0] = horizontal * cosine
        target[1] = sine
        target[4] = -horizontal * sine
        target[5] = cosine
        target[10] = 1f
        target[15] = 1f
    }
}
