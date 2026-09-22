package com.cekavis.rtspcamera.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cekavis.rtspcamera.model.AppController
import com.cekavis.rtspcamera.model.AppState
import com.cekavis.rtspcamera.model.CameraOption
import com.cekavis.rtspcamera.model.StreamPhase
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun HomeScreen(
    state: AppState,
    controller: AppController,
    cameras: List<CameraOption>,
    previewVisible: Boolean,
    startPending: Boolean,
    permissionProblem: String?,
    onStartStop: () -> Unit,
    onPreview: () -> Unit,
    onScreensaver: () -> Unit,
    onSettings: () -> Unit,
    onPermissionSettings: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Status, service action, address and preview controls precede the preview; notices are optional.
    val previewItemIndex = 4 + (if (permissionProblem != null) 1 else 0) + (if (state.error != null) 1 else 0)
    LaunchedEffect(previewVisible) {
        if (previewVisible) listState.animateScrollToItem(previewItemIndex)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("RTSP Camera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("局域网相机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (previewVisible) {
                    IconButton(onClick = onPreview) {
                        Icon(Icons.Outlined.Close, contentDescription = "结束本机预览")
                    }
                }
                IconButton(onClick = onSettings, enabled = !state.applyingSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置")
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    StatusCard(state, cameras)
                }
                item {
                    Button(
                        onClick = onStartStop,
                        enabled = !state.applyingSettings && (!startPending || state.serviceRunning),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = if (state.serviceRunning) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) else ButtonDefaults.buttonColors(),
                    ) {
                        Icon(if (state.serviceRunning) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state.applyingSettings) "正在应用设置…" else if (state.serviceRunning) "停止服务" else if (startPending) "正在准备…" else "启动服务",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (permissionProblem != null) {
                    item { NoticeCard("需要授权", permissionProblem, "应用设置", onPermissionSettings, isError = true) }
                }
                state.error?.let { error ->
                    item {
                        NoticeCard("需要处理", error, "关闭提示", controller::clearError, isError = true)
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("连接地址", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Icon(
                                    if (state.config.server.authEnabled) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                    contentDescription = if (state.config.server.authEnabled) "已启用访问密码" else "未启用认证",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.addresses.isEmpty()) {
                                Text(
                                    if (state.serviceRunning) "等待可用的局域网地址" else "启动服务后，在同一局域网的播放器中打开此地址。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                state.addresses.forEach { rawAddress ->
                                    val address = publicAddress(rawAddress)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(address, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(address))
                                            scope.launch { snackbar.showSnackbar("连接地址已复制") }
                                        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制连接地址") }
                                    }
                                }
                            }
                            Text(
                                if (state.config.server.authEnabled) "播放时使用已设置的用户名和密码。" else if (state.config.server.authConfigured) "未启用认证，同一网络中的设备可连接。" else "首次启动时选择访问保护方式。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onPreview, enabled = state.serviceRunning && !state.applyingSettings, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                            Icon(if (previewVisible) Icons.Outlined.Close else Icons.Outlined.CameraAlt, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (previewVisible) "结束预览" else "本机预览")
                        }
                        OutlinedButton(onClick = onScreensaver, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.NightsStay, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("屏幕保护")
                        }
                    }
                }
                if (previewVisible) {
                    item(key = "camera-preview") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CameraPreview(
                                controller,
                                Modifier.fillMaxWidth().aspectRatio(state.config.video.outputWidth.toFloat() / state.config.video.outputHeight)
                                    .clip(RoundedCornerShape(20.dp)).background(Color.Black),
                            )
                            Text("本机预览会打开相机。结束预览且无播放客户端时，相机自动关闭。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!state.batteryOptimizationExempt) {
                    item { NoticeCard("保持后台连接", "系统省电可能限制长时间后台连接，可在系统设置中为 RTSP Camera 关闭电池优化。", "电池优化设置", onBatterySettings) }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            buildString {
                                append(if (state.batteryPercent >= 0) "电量 ${state.batteryPercent}%" else "电量 —")
                                if (state.batteryTemperature > 0f) append(" · ${String.format(Locale.ROOT, "%.1f", state.batteryTemperature)} °C")
                                if (state.serviceRunning) append(" · 已运行 ${uptime(state.uptimeSeconds)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text("屏幕保护使用微光随机移动，并定时全黑休息。轻触屏幕即可退出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: AppState, cameras: List<CameraOption>) {
    val status = when (state.phase) {
        StreamPhase.STOPPED -> "准备就绪"
        StreamPhase.IDLE -> if (state.cameraActive) "本机预览中" else "等待连接"
        StreamPhase.STARTING -> "正在准备视频"
        StreamPhase.STREAMING -> if (state.playingClients > 0) "正在直播" else "本机预览中"
        StreamPhase.ERROR -> "服务需要处理"
    }
    val accent = if (state.phase == StreamPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f)),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.serviceRunning) accent else MaterialTheme.colorScheme.onSurfaceVariant))
                Text(if (state.serviceRunning) "服务运行中" else "服务未启动", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !state.serviceRunning -> "启动后即可从局域网访问相机。"
                        state.cameraActive -> "相机已开启，视频由设备本地处理。"
                        else -> "相机已关闭，有播放需求时自动唤醒。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("播放客户端", state.playingClients.toString(), Modifier.weight(1f))
                Metric("实际帧率", String.format(Locale.ROOT, "%.1f", state.actualFps), Modifier.weight(1f))
                Metric("码率 · Mbps", String.format(Locale.ROOT, "%.1f", state.actualBitrate / 1_000_000.0), Modifier.weight(1f))
            }
            Text(
                buildString {
                    append(cameras.firstOrNull { it.id == state.config.video.cameraId }?.label ?: "相机 ${state.config.video.cameraId}")
                    append(" · ${state.config.video.outputWidth} × ${state.config.video.outputHeight}")
                    append(" · ${state.config.video.fpsLabel} · ${state.config.video.codec.label}")
                    if (state.connectedClients > state.playingClients) append("\n已连接 ${state.connectedClients} 台设备")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoticeCard(title: String, text: String, action: String, onAction: () -> Unit, isError: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

private fun publicAddress(address: String): String {
    val uri = Uri.parse(address)
    val authority = uri.encodedAuthority ?: return address
    return uri.buildUpon().encodedAuthority(authority.substringAfterLast('@')).build().toString()
}

private fun uptime(seconds: Long): String {
    val hours = seconds.coerceAtLeast(0) / 3600
    val minutes = seconds.coerceAtLeast(0) % 3600 / 60
    return if (hours > 0) "${hours}小时 ${minutes}分" else "${minutes}分"
}
