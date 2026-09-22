# RTSP Camera

将 Android 手机用作局域网 RTSP 相机。使用 Kotlin、Jetpack Compose 和 Material 3 构建。

## 功能

- H.264 / HEVC 硬件编码，支持 TCP、UDP 和最多 4 个播放客户端。
- 前台服务持续监听，仅在播放、有效协商或手动预览时开启相机。
- 选择镜头、原生分辨率与帧率范围，调整旋转、镜像和码率。
- 音频可关闭，或选择系统默认麦克风及可用输入设备，通过 AAC 随视频传输。
- 可选时间、电量叠加、Digest 密码认证。
- OLED 低亮度动态屏保，可保持屏幕开启。

## 使用

1. 安装 APK，打开应用，选择访问保护方式并授予所需权限。
2. 点击「启动服务」，在同一网络的播放器中打开 `rtsp://<手机 IP>:8554/live`。
3. 根据需要调整设置、打开本机预览或进入屏保。修改运行中的音视频参数会断流，需重新连接。
4. 在「设置 → 音频」选择「关闭」或「麦克风」，开启后选择麦克风来源并授予录音权限。仅在客户端播放音频时采集，本机预览不会开启麦克风；指定设备断开后需重新连接或选择其他来源。

默认：后置主摄、1080p、固定 30 FPS、H.264、4 Mbps、音频关闭。音频使用 AAC-LC、48 kHz 单声道。不含录像或公网穿透。

长时间使用建议插电并解除本应用的电池优化限制；重启或强制停止后需手动启动。RTSP 音视频未加密，远程访问请使用可信 VPN。屏保可降低烧屏风险，不能保证永久零烧屏。

## 构建

需要 JDK 17、Android SDK 37。用 Android Studio 打开工程，或配置 `local.properties` 中的 `sdk.dir` 后执行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 验证

最低支持 Android 8；已在 Nothing A142 / Android 16 调试。本次音频变更通过 56 项 JVM 测试、6 项真机配置迁移与采集生命周期测试，以及 H.264 / HEVC + AAC 的 TCP / UDP 解码验证。已验证系统默认麦克风及指定内置麦克风；外接 USB、有线和蓝牙麦克风尚未实机验证。连续 24 小时稳定性尚未通过验证。

设备回归工具位于 [`tools/`](tools/)，部分脚本会修改测试设备配置。设备日志、截图和构建产物不纳入版本控制。

第三方组件及许可证见 [`third_party/`](third_party/)。
