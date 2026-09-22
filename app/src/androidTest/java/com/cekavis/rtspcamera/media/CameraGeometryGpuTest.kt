package com.cekavis.rtspcamera.media

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/** Pixel tests through the production camera shader, with independent CPU rotation/flip oracles.
 * No camera, service, settings or credentials are modified by this test.
 */
@RunWith(AndroidJUnit4::class)
class CameraGeometryGpuTest {
    @Test fun circlesAndCornerMarkersKeepTheirShapeForEveryOrientation() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val candidates = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, candidates, 0, 1, count, 0))
        val config = checkNotNull(candidates[0])
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 400, EGL14.EGL_HEIGHT, 400, EGL14.EGL_NONE), 0)
        var cases = 0
        try {
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val program = GlRenderer.program(GlRenderer.VERTEX, GlRenderer.TEXTURE_FRAGMENT)
            val positions = GlRenderer.floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val coordinates = GlRenderer.floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            for ((width, height) in listOf(320 to 180, 256 to 192)) {
                val source = fixture(width, height)
                val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
                for ((index, producer) in producerTransforms().withIndex()) {
                    for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
                        val outWidth = if (rotation % 180 == 0) width else height
                        val outHeight = if (rotation % 180 == 0) height else width
                        GLES20.glViewport(0, 0, outWidth, outHeight)
                        val model = FloatArray(16)
                        CameraFrameTransform.model(rotation, mirror, model)
                        GlRenderer.drawCameraQuad(program, GLES20.GL_TEXTURE_2D, ids[0], positions, coordinates,
                            model, producer, FloatArray(16))
                        val pixels = readPixels(outWidth, outHeight)
                        val expected = rotateAndMirrorPixels(source, width, height, rotation, mirror)
                        val label = "${width}x$height producer=$index rotation=$rotation mirror=$mirror"
                        val bad = pixels.indices.count { different(pixels[it], expected[it]) }
                        assertTrue("$label: $bad pixels differ from independently transposed source", bad < pixels.size / 100)
                        assertCircle(pixels, outWidth, outHeight, label)
                        assertEquals("$label GL error", GLES20.GL_NO_ERROR, GLES20.glGetError())
                        if (index == 4 && rotation == 0 && !mirror) {
                            // Negative control: the original path drew the producer matrix directly.
                            // The same pixel oracle must detect its distorted circle, on the real GPU.
                            GlRenderer.drawQuad(program, GLES20.GL_TEXTURE_2D, ids[0], positions, coordinates, model, producer)
                            val original = readPixels(outWidth, outHeight)
                            val axes = circleAxes(original, outWidth, outHeight)
                            assertTrue("Negative control must expose the previous stretching", axes.first > axes.second * 1.5)
                            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                                putString("geometry_negative_control", "${width}x$height: previous circle ${axes.first}x${axes.second}, fixed circle equal axes")
                            })
                        }
                        cases++
                    }
                }
            }
            GLES20.glDeleteTextures(1, ids, 0)
            GLES20.glDeleteProgram(program)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putInt("geometry_gpu_pixel_cases", cases)
                putString("geometry_oracle", "Independent pixel transpose + horizontal flip; white circle axes; colored corner markers")
            })
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        assertEquals(128, cases)
    }

    private fun fixture(width: Int, height: Int): IntArray {
        val radius = min(width, height) / 5
        return IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            val dx = x - width / 2; val dy = y - height / 2
            when {
                dx * dx + dy * dy <= radius * radius -> Color.WHITE
                x in 8..39 && y in 8..23 -> Color.RED
                x in width - 40 until width - 8 && y in 8..39 -> Color.GREEN
                x in 8..23 && y in height - 40 until height - 8 -> Color.BLUE
                x in width - 40 until width - 8 && y in height - 24 until height - 8 -> Color.YELLOW
                else -> Color.BLACK
            }
        }
    }

    private fun rotateAndMirrorPixels(source: IntArray, width: Int, height: Int, rotation: Int, mirror: Boolean): IntArray {
        val outWidth = if (rotation % 180 == 0) width else height
        val outHeight = if (rotation % 180 == 0) height else width
        val output = IntArray(outWidth * outHeight)
        for (y in 0 until height) for (x in 0 until width) {
            var destinationX: Int
            val destinationY: Int
            when (rotation) {
                90 -> { destinationX = height - 1 - y; destinationY = x }
                180 -> { destinationX = width - 1 - x; destinationY = height - 1 - y }
                270 -> { destinationX = y; destinationY = width - 1 - x }
                else -> { destinationX = x; destinationY = y }
            }
            if (mirror) destinationX = outWidth - 1 - destinationX
            output[destinationY * outWidth + destinationX] = source[y * width + x]
        }
        return output
    }

    private fun assertCircle(pixels: IntArray, width: Int, height: Int, label: String) {
        val (horizontal, vertical) = circleAxes(pixels, width, height)
        assertTrue("$label: calibration circle is missing", horizontal > 0 && vertical > 0)
        assertTrue("$label: circle stretched to $horizontal x $vertical", abs(horizontal - vertical) <= 1)
    }

    private fun circleAxes(pixels: IntArray, width: Int, height: Int): Pair<Int, Int> {
        var left = width; var right = -1; var top = height; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y * width + x]
            if (Color.red(color) > 240 && Color.green(color) > 240 && Color.blue(color) > 240) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        return (right - left + 1) to (bottom - top + 1)
    }

    private fun readPixels(width: Int, height: Int): IntArray {
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        return IntArray(width * height) { index ->
            val offset = ((height - 1 - index / width) * width + index % width) * 4
            Color.rgb(bytes.get(offset).toInt() and 255, bytes.get(offset + 1).toInt() and 255, bytes.get(offset + 2).toInt() and 255)
        }
    }

    private fun different(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) > 20 ||
        abs(Color.green(a) - Color.green(b)) > 20 || abs(Color.blue(a) - Color.blue(b)) > 20

    private fun producerTransforms() = listOf(
        affine(1f, 0f, 0f, -1f, 0f, 1f), affine(-1f, 0f, 0f, -1f, 1f, 1f),
        affine(1f, 0f, 0f, 1f, 0f, 0f), affine(-1f, 0f, 0f, 1f, 1f, 0f),
        // First off-diagonal fixture is the actual Nothing A142 back-camera matrix.
        affine(0f, -1f, -1f, 0f, 1f, 1f), affine(0f, 1f, -1f, 0f, 0f, 1f),
        affine(0f, -1f, 1f, 0f, 1f, 0f), affine(0f, 1f, 1f, 0f, 0f, 0f),
    )

    private fun affine(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float) =
        floatArrayOf(a, c, 0f, 0f, b, d, 0f, 0f, 0f, 0f, 1f, 0f, x, y, 0f, 1f)
}
