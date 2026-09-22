package com.cekavis.rtspcamera.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cekavis.rtspcamera.model.AppConfig
import com.cekavis.rtspcamera.model.CameraOption
import com.cekavis.rtspcamera.model.CaptureMode
import com.cekavis.rtspcamera.model.VideoCodec
import com.cekavis.rtspcamera.model.VideoConfig
import kotlin.math.abs

@Composable
internal fun SettingsScreen(
    config: AppConfig,
    cameras: List<CameraOption>,
    applying: Boolean,
    onBack: () -> Unit,
    onSave: (AppConfig) -> Unit,
) {
    var video by remember(config) { mutableStateOf(config.video) }
    var port by remember(config) { mutableStateOf(config.server.port.toString()) }
    var bitrateKbps by remember(config) { mutableStateOf((config.video.bitrate / 1000).toString()) }
    var authEnabled by remember(config) { mutableStateOf(config.server.authEnabled) }
    var authChoiceMade by remember(config) { mutableStateOf(false) }
    var username by remember(config) { mutableStateOf(config.server.username) }
    // Password drafts deliberately stay out of saved-instance state and UI text summaries.
    var newPassword by remember(config) { mutableStateOf("") }
    var keepScreenOn by remember(config) { mutableStateOf(config.keepScreenOn) }
    var modeAdjustment by remember { mutableStateOf<String?>(null) }
    val supportedCameras = cameras.filter { it.modes.isNotEmpty() }
    val selectedCamera = cameras.firstOrNull { it.id == video.cameraId }
    val modes = selectedCamera?.modes.orEmpty()
    val sizes = modes.map { it.width to it.height }.distinct()
    val frameRates = modes.filter { it.width == video.width && it.height == video.height }
        .distinctBy { it.fpsMin to it.fps }.sortedWith(compareBy<CaptureMode> { it.fps }.thenBy { it.fpsMin })
    val codecs = modes.filter {
        it.width == video.width && it.height == video.height && it.fpsMin == video.fpsMin && it.fps == video.fps
    }.map { it.codec }.distinct()
    val parsedPort = port.toIntOrNull()?.takeIf { it in 1024..65535 }
    val parsedBitrate = bitrateKbps.toLongOrNull()?.takeIf { it in 1..(Int.MAX_VALUE / 1000).toLong() }?.times(1000)?.toInt()
    val usernameValid = username.isNotBlank() && username.length <= 128 && username.none { it == '\r' || it == '\n' || it == '"' || it == ':' }
    val effectivePassword = newPassword.ifEmpty { config.server.password }
    val passwordValid = effectivePassword.isNotEmpty() && effectivePassword.length <= 1024 && effectivePassword.none { it == '\r' || it == '\n' }
    val modeValid = cameras.isEmpty() || modes.any {
        it.width == video.width && it.height == video.height && it.fpsMin == video.fpsMin &&
            it.fps == video.fps && it.codec == video.codec
    }
    val valid = parsedPort != null && parsedBitrate != null && modeValid && (!authEnabled || (usernameValid && passwordValid))

    fun selectMode(requested: VideoConfig, camera: CameraOption) {
        val supported = fitVideoToCamera(requested, camera)
        modeAdjustment = if (supported != requested) {
            "所选组合不受此相机支持，已显示原生参数：${supported.width} × ${supported.height}、${supported.fpsLabel}、${supported.codec.label}。请确认后保存。"
        } else null
        video = supported
    }

    fun updatedConfig(): AppConfig = config.copy(
        video = video.copy(bitrate = parsedBitrate ?: config.video.bitrate),
        server = config.server.copy(
            port = parsedPort ?: config.server.port,
            authEnabled = authEnabled,
            username = username.trim(),
            password = effectivePassword,
            authConfigured = config.server.authConfigured || authChoiceMade,
        ),
        keepScreenOn = keepScreenOn,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") }
                Text("设置", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onSave(updatedConfig()) }, enabled = valid && !applying) {
                    Text(if (applying) "保存中…" else "保存")
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    SettingsSection("视频", "尺寸和 AE 帧率范围来自 Camera2，并按硬件编码器能力筛选。") {
                        if (cameras.isEmpty()) {
                            Text("尚未读取到相机能力。授权相机后再选择视频规格。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!modeValid) {
                            Text("保存的相机组合不在原生能力列表中，请重新选择。原视频设置与访问认证均未更改。", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        modeAdjustment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        ChoiceField(
                            label = "相机", value = video.cameraId,
                            options = supportedCameras.map { it.id to it.label },
                            fallback = "相机 ${video.cameraId}",
                            onChange = { id -> cameras.firstOrNull { it.id == id }?.let { selectMode(video.copy(cameraId = id), it) } },
                        )
                        ChoiceField(
                            label = "分辨率", value = video.width to video.height,
                            options = sizes.map { it to "${it.first} × ${it.second}" },
                            fallback = "${video.width} × ${video.height}",
                            onChange = { size -> selectedCamera?.let { selectMode(video.copy(width = size.first, height = size.second), it) } },
                        )
                        ChoiceField(
                            label = "帧率", value = video.fpsMin to video.fps,
                            options = frameRates.map { (it.fpsMin to it.fps) to it.fpsLabel },
                            fallback = video.fpsLabel,
                            onChange = { range -> selectedCamera?.let { selectMode(video.copy(fpsMin = range.first, fps = range.second), it) } },
                        )
                        Text("范围按相机原样显示；自动曝光可在范围内调整实际帧率。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ChoiceField(
                            label = "视频编码", value = video.codec,
                            options = codecs.map { it to it.label }, fallback = video.codec.label,
                            onChange = { video = video.copy(codec = it) },
                        )
                        Text("HEVC 仅在相机规格与硬件编码器均支持时可选。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = bitrateKbps,
                            onValueChange = { if (it.length <= 7 && it.all(Char::isDigit)) bitrateKbps = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("目标码率 · kbps") },
                            supportingText = { Text(if (parsedBitrate == null) "请输入有效的正整数" else "例如 4000 = 4 Mbps，实际码率随画面变化。") },
                            isError = parsedBitrate == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                }
                item {
                    SettingsSection("画面", "调整会应用到传输视频与本机预览。") {
                        ChoiceField(
                            label = "顺时针旋转", value = video.rotation,
                            options = listOf(0, 90, 180, 270).map { it to "$it°" },
                            fallback = "${video.rotation}°", onChange = { video = video.copy(rotation = it) },
                        )
                        Text("输出画面 ${video.outputWidth} × ${video.outputHeight}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SwitchField("水平镜像", "左右翻转画面", video.mirror) { video = video.copy(mirror = it) }
                        SwitchField("叠加时间", "显示设备本地日期与时间", video.showTimestamp) { video = video.copy(showTimestamp = it) }
                        SwitchField("叠加电量", "在视频中显示当前电池百分比", video.showBattery) { video = video.copy(showBattery = it) }
                    }
                }
                item {
                    SettingsSection("RTSP 服务", "播放设备需与手机处于可互访的网络。") {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) port = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("监听端口") },
                            supportingText = { Text(if (parsedPort == null) "端口范围为 1024–65535" else "默认 8554") },
                            isError = parsedPort == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        SwitchField("访问认证", "连接时校验用户名与密码", authEnabled) {
                            authEnabled = it
                            authChoiceMade = true
                        }
                        if (authEnabled) {
                            CredentialFields(
                                username = username, onUsername = { username = it },
                                password = newPassword, onPassword = { newPassword = it },
                                hasSavedPassword = config.server.password.isNotEmpty(),
                                usernameError = !usernameValid, passwordError = !passwordValid,
                            )
                        } else {
                            Text("未启用认证时，同一网络中的设备可直接连接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    SettingsSection("屏幕与运行") {
                        SwitchField("保持屏幕亮起", "仅在应用或屏保可见时生效；仍可手动锁屏。", keepScreenOn) { keepScreenOn = it }
                        Text("屏保以 5% 屏幕亮度显示稀疏微光，每 5 分钟全黑休息 15 秒。轻触或返回即可退出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("无播放客户端且未开启本机预览时，相机会关闭，RTSP 服务保持等待。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("外观跟随系统深浅主题，Android 12 及以上支持动态配色。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
}

@Composable
private fun <T> ChoiceField(label: String, value: T, options: List<Pair<T, String>>, fallback: String, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
            OutlinedButton(
                onClick = { expanded = true }, enabled = options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) {
                Text(options.firstOrNull { it.first == value }?.second ?: fallback, Modifier.weight(1f).padding(vertical = 5.dp), color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "选择$label", modifier = Modifier.size(22.dp))
            }
            DropdownMenu(
                expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.width(with(density) { width.toDp() }).heightIn(max = 360.dp),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.second, fontWeight = if (option.first == value) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { expanded = false; onChange(option.first) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchField(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun AuthSetupDialog(config: AppConfig, onDismiss: () -> Unit, onConfirm: (AppConfig) -> Unit) {
    var authEnabled by remember { mutableStateOf<Boolean?>(null) }
    var username by remember { mutableStateOf(config.server.username) }
    var password by remember { mutableStateOf("") }
    val usernameValid = username.isNotBlank() && username.length <= 128 && username.none { it == '\r' || it == '\n' || it == '"' || it == ':' }
    val passwordValid = password.isNotEmpty() && password.length <= 1024 && password.none { it == '\r' || it == '\n' }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择访问保护") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("启动服务前，请选择局域网设备连接相机的方式。")
                AuthChoice("设置访问密码", "播放时输入用户名和密码", authEnabled == true) { authEnabled = true }
                AuthChoice("无需认证", "同一网络中的设备可直接连接", authEnabled == false) { authEnabled = false }
                if (authEnabled == true) {
                    CredentialFields(
                        username, { username = it }, password, { password = it },
                        hasSavedPassword = false,
                        usernameError = username.isNotEmpty() && !usernameValid,
                        passwordError = password.isNotEmpty() && !passwordValid,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = authEnabled != null && (authEnabled == false || (usernameValid && passwordValid)),
                onClick = {
                    onConfirm(config.copy(server = config.server.copy(
                        authEnabled = authEnabled == true,
                        authConfigured = true,
                        username = username.trim(),
                        password = if (authEnabled == true) password else config.server.password,
                    )))
                },
            ) { Text("保存并继续") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AuthChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CredentialFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    hasSavedPassword: Boolean,
    usernameError: Boolean,
    passwordError: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = username, onValueChange = onUsername, modifier = Modifier.fillMaxWidth(),
            label = { Text("用户名") }, singleLine = true, isError = usernameError,
            supportingText = if (usernameError) ({ Text("用户名需为 1–128 个字符，不能包含冒号、换行或引号。") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        OutlinedTextField(
            value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (hasSavedPassword) "新密码" else "密码") },
            singleLine = true, isError = passwordError,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                Text(when {
                    passwordError -> "密码需为 1–1024 个字符，不能包含换行。"
                    hasSavedPassword -> "留空保留现有密码。"
                    else -> "建议使用至少 8 位的独立密码。"
                })
            },
        )
    }
}

internal fun fitVideoToCamera(video: VideoConfig, camera: CameraOption): VideoConfig {
    val mode = camera.modes.minWithOrNull(
        compareBy<CaptureMode> { if (it.width == video.width && it.height == video.height) 0 else 1 }
            .thenBy { abs(it.width.toLong() * it.height - video.width.toLong() * video.height) }
            .thenBy { abs(it.fps - video.fps) }
            .thenBy { abs(it.fpsMin - video.fpsMin) }
            .thenBy { if (it.codec == video.codec) 0 else if (it.codec == VideoCodec.H264) 1 else 2 },
    ) ?: return video
    return video.copy(cameraId = camera.id, width = mode.width, height = mode.height, fpsMin = mode.fpsMin, fps = mode.fps, codec = mode.codec)
}
