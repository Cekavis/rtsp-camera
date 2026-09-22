package com.cekavis.rtspcamera.device

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import com.cekavis.rtspcamera.CameraApplication
import com.cekavis.rtspcamera.model.AppConfig
import com.cekavis.rtspcamera.model.AppController
import com.cekavis.rtspcamera.model.AppState
import com.cekavis.rtspcamera.model.VideoCodec
import com.cekavis.rtspcamera.ui.MainActivity
import com.cekavis.rtspcamera.service.CameraService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Locale

/**
 * Device-only harness. Run one method at a time; never supply user credentials in runner arguments.
 * Supported arguments: codec=H264|HEVC, cameraId, width, height, fps, fpsMin, rotation, mirror, time, battery, auth.
 * preserveOtherSettings=true changes only specified video fields and never changes credentials/authentication.
 * Instrumentation.finish() terminates the app process, so external playback should normally use
 * configureOnlyForExternalPlayback followed by a normal launcher/UI service start.
 */
@RunWith(AndroidJUnit4::class)
class DeviceHarnessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun configureForExternalPlayback() {
        val harness = launchConfigured(InstrumentationRegistry.getArguments())
        try {
            startAndAwaitIdle(harness)
            report("ready_while_instrumentation_is_running", harness.controller.state.value)
            // Intentionally do not stop the foreground service. Finishing instrumentation still
            // terminates the target process; this method is not a process-lifetime guarantee.
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun configureOnlyForExternalPlayback() {
        val harness = launchConfigured(InstrumentationRegistry.getArguments(), stopBeforeConfig = true)
        try {
            assertFalse("配置入口不应启动相机", harness.controller.state.value.cameraActive)
            assertFalse("配置入口不应启动服务", harness.controller.state.value.serviceRunning)
            report("configuration_saved_start_from_launcher", harness.controller.state.value)
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun previewDemandFollowsVisibleActivityLifecycle() {
        val harness = launchConfigured(Bundle().apply { putString("codec", "H264") })
        try {
            startAndAwaitIdle(harness)
            clickVisibleText("本机预览")
            revealPreviewSurface(harness)
            awaitState("真实 SurfaceView 预览应开启相机", harness.controller) {
                it.serviceRunning && it.cameraActive && it.previewActive
            }
            awaitState("预览应产出视频帧", harness.controller) { it.actualFps > 0f }

            clickVisibleText("结束预览")
            awaitState("显式结束预览应关闭相机", harness.controller) {
                it.serviceRunning && !it.cameraActive && !it.previewActive
            }
            assertIdleRemains(harness.controller)

            clickVisibleText("本机预览")
            revealPreviewSurface(harness)
            awaitState("相机应可再次开启", harness.controller) { it.cameraActive && it.previewActive }
            harness.scenario.moveToState(Lifecycle.State.CREATED)
            awaitState("Activity ON_STOP 应释放预览和相机", harness.controller) {
                it.serviceRunning && !it.cameraActive && !it.previewActive
            }
            harness.scenario.moveToState(Lifecycle.State.RESUMED)
            assertIdleRemains(harness.controller)
            report("preview_and_background_lifecycle_passed", harness.controller.state.value)
        } catch (error: Throwable) {
            val device = UiDevice.getInstance(instrumentation)
            val directory = instrumentation.targetContext.filesDir
            device.dumpWindowHierarchy(File(directory, "preview-failure.xml"))
            device.takeScreenshot(File(directory, "preview-failure.png"))
            throw AssertionError("${error.message}; ${safeState(harness.controller.state.value)}; screenOn=${device.isScreenOn}", error)
        } finally {
            try {
                onMain { harness.controller.detachPreview(); harness.controller.stopService() }
                awaitState("测试结束应停止服务并释放相机", harness.controller, failOnAppError = false) {
                    !it.serviceRunning && !it.cameraActive && !it.previewActive
                }
            } finally {
                harness.scenario.close()
            }
        }
    }

    @Test
    fun screensaverRestoresBrightnessAndKeepScreenPreference() {
        val harness = launchConfigured(Bundle())
        val original = harness.controller.state.value.config
        try {
            startAndAwaitIdle(harness)
            var oldBrightness = -1f
            harness.scenario.onActivity { oldBrightness = it.window.attributes.screenBrightness }
            onMain { harness.controller.applyConfig(original.copy(keepScreenOn = true)) }
            awaitState("保持屏幕选项应保存", harness.controller) { !it.applyingSettings && it.config.keepScreenOn }
            await("窗口应保持开启", harness.controller) {
                var enabled = false
                harness.scenario.onActivity { enabled = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
                enabled
            }
            clickVisibleText("屏幕保护")
            await("屏保应使用低亮度", harness.controller) {
                var dim = false
                harness.scenario.onActivity { dim = kotlin.math.abs(it.window.attributes.screenBrightness - .05f) < .001f }
                dim
            }
            assertIdleRemains(harness.controller)
            UiDevice.getInstance(instrumentation).apply { click(displayWidth / 2, displayHeight / 2) }
            await("退出屏保应恢复亮度", harness.controller) {
                var restored = false
                harness.scenario.onActivity { restored = it.window.attributes.screenBrightness == oldBrightness }
                restored
            }
            onMain { harness.controller.applyConfig(original.copy(keepScreenOn = false)) }
            awaitState("关闭保持屏幕选项应保存", harness.controller) { !it.applyingSettings && !it.config.keepScreenOn }
            await("关闭选项应清除窗口标志", harness.controller) {
                var cleared = false
                harness.scenario.onActivity { cleared = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0 }
                cleared
            }
        } finally {
            onMain { harness.controller.applyConfig(original) }
            awaitState("恢复屏幕配置", harness.controller, failOnAppError = false) { !it.applyingSettings }
            onMain { harness.controller.stopService() }
            awaitState("停止测试服务", harness.controller, failOnAppError = false) { !it.serviceRunning }
            harness.scenario.close()
        }
    }

    @Test
    fun occupiedPortRollsBackToWorkingConfiguration() {
        val harness = launchConfigured(Bundle())
        try {
            startAndAwaitIdle(harness)
            val original = harness.controller.state.value.config
            ServerSocket(0).use { occupied ->
                onMain { harness.controller.applyConfig(original.copy(server = original.server.copy(port = occupied.localPort))) }
                awaitState("端口冲突应回滚配置并显示错误", harness.controller, failOnAppError = false) {
                    !it.applyingSettings && it.error != null && it.config == original && it.serviceRunning
                }
                java.net.Socket("127.0.0.1", original.server.port).use { socket ->
                    socket.soTimeout = 3_000
                    socket.getOutputStream().write("OPTIONS rtsp://127.0.0.1:${original.server.port}/live RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                    assertTrue("回滚后原监听器必须可响应", socket.getInputStream().bufferedReader().readLine().contains("200"))
                }
                assertIdleRemains(harness.controller)
            }
        } finally {
            onMain { harness.controller.clearError(); harness.controller.stopService() }
            awaitState("停止测试服务", harness.controller, failOnAppError = false) { !it.serviceRunning }
            harness.scenario.close()
        }
    }

    @Test
    fun unconfiguredAuthenticationCannotStartService() {
        val harness = launchConfigured(Bundle(), stopBeforeConfig = true)
        val original = harness.controller.state.value.config
        try {
            val unset = original.copy(server = original.server.copy(authConfigured = false))
            onMain { harness.controller.applyConfig(unset) }
            awaitState("保存未配置状态", harness.controller) { !it.applyingSettings && it.config == unset }
            harness.scenario.onActivity {
                ContextCompat.startForegroundService(it, Intent(it, CameraService::class.java))
            }
            awaitState("服务入口必须拒绝未确认的认证配置", harness.controller, failOnAppError = false) {
                it.error != null && !it.serviceRunning && !it.cameraActive
            }
        } finally {
            onMain { harness.controller.clearError(); harness.controller.applyConfig(original) }
            awaitState("恢复访问配置", harness.controller, failOnAppError = false) { !it.applyingSettings && it.config == original }
            harness.scenario.close()
        }
    }

    private data class Harness(val scenario: ActivityScenario<MainActivity>, val controller: AppController)

    private fun launchConfigured(arguments: Bundle, stopBeforeConfig: Boolean = false): Harness {
        require(!arguments.containsKey("password")) {
            "测试入口不接受密码参数；auth=true 仅使用测试 APK 生成的临时凭据。"
        }
        grantRequiredPermissions()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var controller: AppController
            scenario.onActivity { controller = (it.application as CameraApplication).controller }
            awaitState("等待应用配置加载", controller, failOnAppError = false) { it.loaded }
            onMain { controller.clearError(); controller.refreshCapabilities(); controller.detachPreview() }
            await("等待相机能力枚举", controller) { controller.cameras.value.any { it.modes.isNotEmpty() } }
            if (stopBeforeConfig && controller.state.value.serviceRunning) {
                onMain { controller.stopService() }
                awaitState("配置前停止现有服务", controller, failOnAppError = false) { !it.serviceRunning && !it.cameraActive }
            }
            val config = requestedConfig(arguments, controller)
            onMain { controller.applyConfig(config) }
            awaitState("等待配置持久化完成", controller) {
                !it.applyingSettings && it.config == config
            }
            report("configuration_saved", controller.state.value)
            return Harness(scenario, controller)
        } catch (error: Throwable) {
            scenario.close()
            throw error
        }
    }

    private fun grantRequiredPermissions() {
        val context = instrumentation.targetContext
        val required = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        required.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
            assertTrue("设备测试缺少必要运行时权限", ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun requestedConfig(arguments: Bundle, controller: AppController): AppConfig {
        val original = controller.state.value.config
        val preserve = booleanArgument(arguments, "preserveOtherSettings", false)
        val codec = when ((arguments.getString("codec") ?: if (preserve) original.video.codec.name else "H264").uppercase(Locale.ROOT)) {
            "H264" -> VideoCodec.H264
            "HEVC" -> VideoCodec.HEVC
            else -> throw IllegalArgumentException("codec 只支持 H264 或 HEVC")
        }
        val requestedCamera = arguments.getString("cameraId") ?: original.video.cameraId
        val candidates = controller.cameras.value.filter { it.id == requestedCamera }.flatMap { camera ->
            camera.modes.filter { it.codec == codec }.map { camera to it }
        }
        require(candidates.isNotEmpty()) { "设备没有所请求编码格式的可用模式" }
        val preferred = candidates.minWithOrNull(
            compareBy<Pair<com.cekavis.rtspcamera.model.CameraOption, com.cekavis.rtspcamera.model.CaptureMode>> {
                if (it.first.id == requestedCamera) 0 else 1
            }.thenBy { if (it.second.width == 1920 && it.second.height == 1080 && it.second.fps == 30 && it.second.fpsMin == 30) 0 else 1 }
                .thenBy { if (it.second.width == 1280 && it.second.height == 720 && it.second.fps == 30 && it.second.fpsMin == 30) 0 else 1 },
        ) ?: error("设备未提供可用模式")
        val width = integerArgument(arguments, "width", if (preserve) original.video.width else preferred.second.width)
        val height = integerArgument(arguments, "height", if (preserve) original.video.height else preferred.second.height)
        val fps = integerArgument(arguments, "fps", if (preserve) original.video.fps else preferred.second.fps)
        val fpsMin = integerArgument(arguments, "fpsMin", if (preserve && !arguments.containsKey("fps")) original.video.fpsMin else fps)
        val selected = candidates.firstOrNull {
            it.first.id == requestedCamera && it.second.width == width && it.second.height == height && it.second.fps == fps && it.second.fpsMin == fpsMin
        } ?: candidates.firstOrNull { it.second.width == width && it.second.height == height && it.second.fps == fps && it.second.fpsMin == fpsMin }
        require(selected != null) { "设备不支持请求的视频尺寸、帧率与编码组合" }
        val rotation = integerArgument(arguments, "rotation", if (preserve) original.video.rotation else 0)
        require(rotation in setOf(0, 90, 180, 270)) { "rotation 只支持 0、90、180、270" }
        val auth = booleanArgument(arguments, "auth", false)
        return original.copy(
            video = original.video.copy(
                cameraId = selected.first.id, width = width, height = height, fps = fps, fpsMin = fpsMin, codec = codec,
                rotation = rotation,
                mirror = booleanArgument(arguments, "mirror", preserve && original.video.mirror),
                showTimestamp = booleanArgument(arguments, "time", preserve && original.video.showTimestamp),
                showBattery = booleanArgument(arguments, "battery", preserve && original.video.showBattery),
            ),
            server = if (preserve) original.server else original.server.copy(
                authConfigured = true,
                authEnabled = auth,
                username = if (auth) HARNESS_USERNAME else "camera",
                password = if (auth) temporaryHarnessPassword() else "",
            ),
        )
    }

    private fun startAndAwaitIdle(harness: Harness) {
        harness.scenario.moveToState(Lifecycle.State.RESUMED)
        onMain { harness.controller.startService() }
        awaitState("等待前台服务启动", harness.controller) { it.serviceRunning }
        assertIdleRemains(harness.controller)
    }

    private fun assertIdleRemains(controller: AppController) {
        val deadline = SystemClock.elapsedRealtime() + 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = controller.state.value
            assertTrue("监听服务应保持运行；${safeState(state)}", state.serviceRunning)
            assertTrue("此测试需要没有外部播放客户端；${safeState(state)}", state.playingClients == 0)
            assertFalse("空闲时相机必须保持关闭；${safeState(state)}", state.cameraActive)
            assertFalse("空闲时不应保留本机预览；${safeState(state)}", state.previewActive)
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun clickVisibleText(text: String) {
        val device = UiDevice.getInstance(instrumentation)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var direction = Direction.DOWN
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = device.findObject(By.text(text))
            if (target != null) {
                target.click()
                return
            }
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null && !scrollable.scroll(direction, .6f)) {
                direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("未找到所需预览操作按钮")
    }

    private fun revealPreviewSurface(harness: Harness) {
        val device = UiDevice.getInstance(instrumentation)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var visible = false
            // AndroidView's SurfaceView is not consistently exported through UiAutomation.
            // Check the actual native view, valid surface and visible area instead of its label.
            harness.scenario.onActivity { activity ->
                fun inspect(view: View) {
                    if (view is SurfaceView) {
                        val bounds = Rect()
                        visible = view.isShown && view.holder.surface.isValid && view.getGlobalVisibleRect(bounds) &&
                            bounds.width() >= view.width * .8f && bounds.height() >= view.height * .8f
                    } else if (view is ViewGroup) {
                        for (index in 0 until view.childCount) inspect(view.getChildAt(index))
                    }
                }
                inspect(activity.window.decorView)
            }
            if (visible) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 30)
            SystemClock.sleep(300L)
        }
        throw AssertionError("预览 SurfaceView 未进入可见区域")
    }

    private fun awaitState(
        label: String,
        controller: AppController,
        timeoutMs: Long = 25_000L,
        failOnAppError: Boolean = true,
        predicate: (AppState) -> Boolean,
    ) = await(label, controller, timeoutMs, failOnAppError) { predicate(controller.state.value) }

    private fun await(
        label: String,
        controller: AppController,
        timeoutMs: Long = 25_000L,
        failOnAppError: Boolean = true,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            val state = controller.state.value
            if (failOnAppError && state.error != null && !state.applyingSettings) {
                throw AssertionError("$label：应用报告错误；${safeState(state)}")
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("$label：超时；${safeState(controller.state.value)}")
    }

    private fun integerArgument(arguments: Bundle, key: String, default: Int): Int {
        val value = arguments.getString(key) ?: return default
        return value.toIntOrNull() ?: throw IllegalArgumentException("$key 必须是整数")
    }

    private fun booleanArgument(arguments: Bundle, key: String, default: Boolean): Boolean {
        return when (arguments.getString(key)?.lowercase(Locale.ROOT)) {
            null -> default
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$key 必须是 true 或 false")
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun safeState(state: AppState): String =
        "phase=${state.phase}, running=${state.serviceRunning}, camera=${state.cameraActive}, preview=${state.previewActive}, " +
            "clients=${state.playingClients}, applying=${state.applyingSettings}, errorPresent=${state.error != null}"

    private fun report(stage: String, state: AppState) {
        val video = state.config.video
        instrumentation.sendStatus(2, Bundle().apply {
            putString("rtsp_harness_stage", stage)
            putString("rtsp_harness_codec", video.codec.name)
            putString("rtsp_harness_camera_id", video.cameraId)
            putInt("rtsp_harness_width", video.width)
            putInt("rtsp_harness_height", video.height)
            putInt("rtsp_harness_fps", video.fps)
            putInt("rtsp_harness_fps_min", video.fpsMin)
            putInt("rtsp_harness_rotation", video.rotation)
            putBoolean("rtsp_harness_mirror", video.mirror)
            putBoolean("rtsp_harness_time_overlay", video.showTimestamp)
            putBoolean("rtsp_harness_battery_overlay", video.showBattery)
            putBoolean("rtsp_harness_auth_enabled", state.config.server.authEnabled)
            putBoolean("rtsp_harness_service_running", state.serviceRunning)
            putBoolean("rtsp_harness_camera_active", state.cameraActive)
            putBoolean("rtsp_harness_configuration_saved", !state.applyingSettings)
            putString("rtsp_harness_lifetime", "Instrumentation completion exits the app process; launch the app normally for external playback.")
        })
    }

    companion object {
        private const val POLL_MS = 50L
        // Synthetic test-only credentials. Never print these, pass them as runner arguments,
        // or reuse them outside the temporary device test configuration.
        private const val HARNESS_USERNAME = "rtsp-device-harness"
        private fun temporaryHarnessPassword(): String = MessageDigest.getInstance("SHA-256")
            .digest("rtsp-camera-test-apk-temporary-credential-v1".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            .take(24)
    }
}
