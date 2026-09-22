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

## 自动发布

推送 `v*` 标签会触发 [Release 工作流](.github/workflows/release.yml)：运行现有单元测试和 Release Lint，构建经过压缩、正式签名的 APK，验证签名后创建 GitHub Release，附带 APK、SHA-256 校验文件和自动生成的更新说明。带后缀的标签（如 `v0.1.2-rc.1`）发布为预发行版。

仓库需在 **Settings → Secrets and variables → Actions** 配置以下 Secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 签名 keystore 文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

没有签名密钥时，可使用 JDK 自带的 `keytool` 生成。以下命令会交互式询问密码和证书信息；请把 keystore 保存在仓库之外：

```sh
keytool -genkeypair -keystore /path/outside/repo/rtsp-camera-release.keystore -storetype PKCS12 -alias rtsp-camera -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000
```

PKCS12 使用相同的 keystore 密码和密钥密码。请长期安全备份 keystore、密码及别名，后续更新沿用同一密钥。之前安装的 Debug APK 签名不同，首次切换正式版需卸载后重新安装，应用设置会丢失。

发布步骤：

1. 更新 `app/build.gradle.kts` 的 `versionName` 并递增 `versionCode`，提交并推送代码及工作流。
2. 创建与 `versionName` 完全一致、带 `v` 前缀的标签。例如当前 `versionName = "0.1.1"`：

   ```sh
   git tag v0.1.1
   git push origin v0.1.1
   ```

3. 等待 Actions 成功，在 Releases 下载 `rtsp-camera-v0.1.1.apk`。标签与 APK 版本不一致、缺少签名配置或检查失败时，工作流会终止发布。

每个版本使用新标签；工作流不会覆盖已有 Release。预发行版的 `versionName` 也需包含对应后缀。

## 验证

最低支持 Android 8；已在 Nothing A142 / Android 16 调试。本次音频变更通过 56 项 JVM 测试、6 项真机配置迁移与采集生命周期测试，以及 H.264 / HEVC + AAC 的 TCP / UDP 解码验证。已验证系统默认麦克风及指定内置麦克风；外接 USB、有线和蓝牙麦克风尚未实机验证。连续 24 小时稳定性尚未通过验证。

设备回归工具位于 [`tools/`](tools/)，部分脚本会修改测试设备配置。设备日志、截图和构建产物不纳入版本控制。

第三方组件及许可证见 [`third_party/`](third_party/)。
