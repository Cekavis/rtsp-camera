package com.cekavis.rtspcamera.device

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.cekavis.rtspcamera.CameraApplication
import com.cekavis.rtspcamera.model.*
import com.cekavis.rtspcamera.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket

/** Real service/permission/encoder integration. Restores the saved configuration in finally. */
@RunWith(AndroidJUnit4::class)
class AudioServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun microphoneFollowsSelectedTracksAndSurvivesBackgroundPlayback() {
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val context = instrumentation.targetContext
        listOfNotNull(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else null).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: AppController
            scenario.onActivity { controller = (it.application as CameraApplication).controller }
            await(controller) { it.loaded }
            val original = controller.state.value.config
            try {
                onMain { controller.stopService() }
                await(controller) { !it.serviceRunning }
                val port = ServerSocket(0).use { it.localPort }
                val silent = original.copy(audio = AudioConfig(), server = original.server.copy(
                    port = port, authConfigured = true, authEnabled = false))
                apply(controller, silent)
                onMain { controller.startService() }
                await(controller) { it.serviceRunning }
                assertFalse(controller.state.value.microphoneActive)
                Client(port).use { assertFalse(it.describe().contains("m=audio")) }
                await(controller) { !it.cameraActive }
                report("audio_off_verified")

                val audible = silent.copy(audio = AudioConfig(AudioSource.MICROPHONE))
                apply(controller, audible) // Promotes the already-running foreground service.
                assertFalse(controller.state.value.microphoneActive)
                Client(port).use { client ->
                    assertTrue(client.describe().contains("MPEG4-GENERIC/48000/1"))
                    client.setup(0)
                    client.request("PLAY")
                    client.awaitRtp(setOf(0))
                    assertFalse("Video-only clients must not acquire the microphone", controller.state.value.microphoneActive)
                }
                await(controller) { !it.cameraActive && !it.microphoneActive }
                report("video_only_verified")

                // Only audio is selected. A background connection must not require a new permission prompt.
                scenario.moveToState(Lifecycle.State.CREATED)
                Client(port).use { client ->
                    client.describe()
                    client.setup(1)
                    client.request("PLAY")
                    client.awaitRtp(setOf(2))
                    await(controller) { it.microphoneActive && !it.cameraActive }
                    client.request("PAUSE")
                    await(controller) { !it.microphoneActive && !it.cameraActive }
                    client.request("PLAY")
                    client.awaitRtp(setOf(2))
                    await(controller) { it.microphoneActive }
                }
                await(controller) { !it.microphoneActive && !it.cameraActive }
                report("background_audio_pause_resume_verified")

                Client(port).use { client ->
                    client.describe()
                    client.setup(0)
                    client.setup(1)
                    client.request("PLAY")
                    client.awaitRtp(setOf(0, 2))
                    await(controller) { it.microphoneActive && it.cameraActive }
                    report("dual_track_verified")
                    device.wakeUp()
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    apply(controller, silent) // Changing audio invalidates the existing RTSP session.
                    await(controller) { !it.microphoneActive && !it.cameraActive }
                }
                Client(port).use { assertFalse(it.describe().contains("m=audio")) }
            } finally {
                // Restore settings even if the screen is off or ActivityScenario cannot resume.
                onMain { controller.stopService() }
                await(controller, checkErrors = false) { !it.serviceRunning && !it.microphoneActive && !it.cameraActive }
                onMain { controller.clearError(); controller.applyConfig(original) }
                await(controller) { !it.applyingSettings && it.config == original }
            }
        }
    }

    private fun report(stage: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("audio_stage", stage)
    })

    private fun apply(controller: AppController, config: AppConfig) {
        onMain { controller.clearError(); controller.applyConfig(config) }
        await(controller) { !it.applyingSettings && it.config == config }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(controller: AppController, checkErrors: Boolean = true, predicate: (AppState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            val state = controller.state.value
            if (checkErrors && state.error != null) fail(state.error)
            if (predicate(state)) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Service state did not reach the expected audio/camera lifecycle state")
    }

    private class Client(port: Int) : Closeable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        private val input = socket.getInputStream().buffered()
        private val url = "rtsp://127.0.0.1:$port/live"
        private var sequence = 0
        private var session: String? = null

        fun describe() = request("DESCRIBE", headers = "Accept: application/sdp\r\n")
        fun setup(track: Int) = request("SETUP", "/trackID=$track",
            "Transport: RTP/AVP/TCP;unicast;interleaved=${track * 2}-${track * 2 + 1}\r\n")

        fun request(method: String, suffix: String = "", headers: String = ""): String {
            socket.getOutputStream().write(("$method $url$suffix RTSP/1.0\r\nCSeq: ${++sequence}\r\n" +
                (session?.let { "Session: $it\r\n" } ?: "") + headers + "\r\n").toByteArray())
            var first: Int
            do {
                first = byte()
                if (first == '$'.code) packet()
            } while (first == '$'.code)
            assertTrue("Expected RTSP success", (first.toChar() + line()).contains(" 200 "))
            val values = linkedMapOf<String, String>()
            while (true) {
                val line = line()
                if (line.isEmpty()) break
                values[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            values["session"]?.let { session = it.substringBefore(';') }
            return bytes(values["content-length"]?.toInt() ?: 0).toString(Charsets.UTF_8)
        }

        fun awaitRtp(channels: Set<Int>) {
            val remaining = channels.toMutableSet()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (remaining.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                assertEquals('$'.code, byte())
                val (channel, data) = packet()
                if (channel in channels) {
                    assertTrue(data.size > 12)
                    assertEquals(2, (data[0].toInt() and 0xff) ushr 6)
                    assertEquals(if (channel == 0) 96 else 97, data[1].toInt() and 0x7f)
                    remaining.remove(channel)
                }
            }
            assertTrue("Missing RTP channels: $remaining", remaining.isEmpty())
        }

        private fun packet(): Pair<Int, ByteArray> {
            val channel = byte()
            return channel to bytes((byte() shl 8) or byte())
        }
        private fun byte() = input.read().also { check(it >= 0) { "RTSP connection closed" } }
        private fun line() = buildString {
            while (true) {
                val next = byte()
                if (next == '\n'.code) break
                if (next != '\r'.code) append(next.toChar())
            }
        }
        private fun bytes(size: Int) = ByteArray(size).also { value ->
            var offset = 0
            while (offset < size) {
                val count = input.read(value, offset, size - offset)
                check(count > 0) { "RTSP payload truncated" }
                offset += count
            }
        }
        override fun close() { socket.close() }
    }
}
