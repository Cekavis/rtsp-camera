package com.cekavis.rtspcamera.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cekavis.rtspcamera.model.AppConfig
import com.cekavis.rtspcamera.model.AppController

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
internal fun CameraApp(controller: AppController, activity: Activity) {
    val state by controller.state.collectAsStateWithLifecycle()
    val cameras by controller.cameras.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var previewRequested by remember { mutableStateOf(false) }
    var screensaverOpen by rememberSaveable { mutableStateOf(false) }
    var authDialogOpen by rememberSaveable { mutableStateOf(false) }
    var startRequested by rememberSaveable { mutableStateOf(false) }
    var permissionStep by rememberSaveable { mutableStateOf("idle") }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    var permissionProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConfig by remember { mutableStateOf<AppConfig?>(null) }
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK_PERMISSION)
        }
    }
    fun permissionGranted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationRequested = true
        permissionStep = "idle"
    }
    val capturePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionStep = "idle"
        if (requiredPermissions.all(::permissionGranted)) {
            permissionProblem = null
            controller.refreshCapabilities()
        } else {
            startRequested = false
            permissionProblem = if (Build.VERSION.SDK_INT >= 37 && !permissionGranted(LOCAL_NETWORK_PERMISSION)) {
                "需要相机和局域网权限，才能向播放设备提供视频。可重试授权，或前往应用设置开启权限。"
            } else {
                "需要相机权限才能提供视频。可重试授权，或前往应用设置开启权限。"
            }
        }
    }

    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_RESUME -> {
                    controller.refreshCapabilities()
                    if (requiredPermissions.all(::permissionGranted)) permissionProblem = null
                }
                Lifecycle.Event.ON_STOP -> {
                    visible = false
                    previewRequested = false
                    controller.detachPreview()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        controller.refreshCapabilities()
        onDispose {
            lifecycle.removeObserver(observer)
            controller.detachPreview()
        }
    }
    DisposableEffect(activity, visible, state.config.keepScreenOn) {
        if (visible && state.config.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(state.serviceRunning) {
        if (!state.serviceRunning) previewRequested = false
    }
    LaunchedEffect(
        startRequested, state.loaded, state.config.server.authConfigured, state.applyingSettings,
        visible, permissionStep, authDialogOpen,
    ) {
        if (!startRequested || !state.loaded || state.applyingSettings || !visible ||
            !state.config.server.authConfigured || authDialogOpen || permissionStep != "idle"
        ) return@LaunchedEffect
        val missing = requiredPermissions.filterNot(::permissionGranted)
        when {
            missing.isNotEmpty() -> {
                permissionStep = "required"
                capturePermissions.launch(missing.toTypedArray())
            }
            Build.VERSION.SDK_INT >= 33 && !notificationRequested &&
                !permissionGranted(Manifest.permission.POST_NOTIFICATIONS) -> {
                permissionStep = "notification"
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                startRequested = false
                permissionProblem = null
                controller.startService()
            }
        }
    }
    LaunchedEffect(state.error, state.applyingSettings) {
        if (state.error != null && !state.applyingSettings) startRequested = false
    }

    fun openSystemSettings(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            permissionProblem = "此设备没有提供对应的设置页面，请在系统设置中手动调整 RTSP Camera 的权限或电池限制。"
        } catch (_: SecurityException) {
            permissionProblem = "系统不允许打开此设置页面，请在系统设置中手动调整。"
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            screensaverOpen && visible -> OledScreensaver(activity) { screensaverOpen = false }
            settingsOpen -> {
                BackHandler { settingsOpen = false }
                SettingsScreen(
                    config = state.config,
                    cameras = cameras,
                    applying = state.applyingSettings,
                    onBack = { settingsOpen = false },
                    onSave = { updated ->
                        if (updated == state.config) {
                            settingsOpen = false
                        } else if (state.serviceRunning) {
                            pendingConfig = updated
                        } else {
                            controller.clearError()
                            controller.applyConfig(updated)
                            settingsOpen = false
                        }
                    },
                )
            }
            else -> HomeScreen(
                state = state,
                controller = controller,
                cameras = cameras,
                previewVisible = previewRequested && visible && state.serviceRunning,
                startPending = startRequested || state.applyingSettings,
                permissionProblem = permissionProblem,
                onStartStop = {
                    controller.clearError()
                    if (state.serviceRunning) {
                        startRequested = false
                        previewRequested = false
                        controller.detachPreview()
                        controller.stopService()
                    } else {
                        startRequested = true
                        if (!state.config.server.authConfigured) authDialogOpen = true
                    }
                },
                onPreview = {
                    if (state.serviceRunning) {
                        previewRequested = !previewRequested
                        if (!previewRequested) controller.detachPreview()
                    }
                },
                onScreensaver = {
                    previewRequested = false
                    controller.detachPreview()
                    screensaverOpen = true
                },
                onSettings = {
                    previewRequested = false
                    controller.detachPreview()
                    settingsOpen = true
                },
                onPermissionSettings = {
                    openSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                },
                onBatterySettings = { openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            )
        }
    }
    if (authDialogOpen) {
        AuthSetupDialog(
            config = state.config,
            onDismiss = { authDialogOpen = false; startRequested = false },
            onConfirm = { updated ->
                controller.clearError()
                authDialogOpen = false
                controller.applyConfig(updated)
            },
        )
    }
    pendingConfig?.let { updated ->
        AlertDialog(
            onDismissRequest = { pendingConfig = null },
            title = { Text("应用新设置？") },
            text = { Text("保存设置会重新配置服务并中断当前视频连接，播放设备需要重新连接。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfig = null
                    controller.clearError()
                    controller.applyConfig(updated)
                    settingsOpen = false
                }) { Text("保存并重新配置") }
            },
            dismissButton = { TextButton(onClick = { pendingConfig = null }) { Text("继续编辑") } },
        )
    }
}
