# Kazumi 投屏桥

使用 **libxposed API 102** 和 **miuix** 的 LSPosed 模块，为 Kazumi 2.3.8 向 DJI Goggles 2 投屏提供兼容 MP4。仅作用于 `com.predidit.kazumi`，不修改 Kazumi APK。

已调试环境：小米 15 Pro / Android 16 / 官方 LSPosed 2.2.1；APK 仅支持 arm64-v8a，最低 Android 10。

## 安装和使用

1. 在 [Release](https://github.com/oneringr/kazumi-broadcast-bridge/releases/tag/v1.0.10) 下载并安装 1.0.10 APK，旧版可覆盖安装。
2. 在 LSPosed 中启用模块，作用域勾选 **Kazumi**，完全退出并重启 Kazumi。
3. 手机和眼镜连接同一局域网，眼镜开启无线串流 / DLNA。
4. 打开模块，选择模式并 **开启服务**；允许通知和后台运行。
5. 在 Kazumi 播放视频，使用原 **远程投屏** 菜单选择眼镜。模块页面显示转换进度与错误。
6. 完成后 **停止服务**，按需清理缓存。

升级前停止服务；覆盖安装后重新开启模块服务并重启 Kazumi。原生 Hook 不会热替换。

## 功能

- 根据实际容器和编码判断兼容性；兼容 MP4 默认直接转发，也可开启 **缓存 MP4**，完整下载并校验后投屏，减少来源网络波动造成的中断。该开关默认关闭。
- 默认 **完整转换后投屏**：生成前置索引的 MP4 并检查实际音视频轨道时长，HTTP 支持 HEAD / Range。
- 可切换 **边转换边投屏**：生成 fragmented MP4，依赖接收端兼容性；生成期间不支持任意拖动。
- HLS 清单在投屏任务内保存并复用，正确处理相对分片、子清单、KEY / MAP 与来源请求头。
- 完整模式的有限 HLS 提供 **1 / 2 / 4 / 8 / 16 / 32 路下载，默认 4 路**，设置在下次投屏生效；保留加密数据与清单顺序，由 FFmpeg 解密和封装。来源限速时可降低并发。
- 直播、边转换模式及含 BYTERANGE 的媒体清单沿用原读取路径。分片、临时文件与 MP4 共用缓存上限，支持取消和清理。
- 自动沿用播放器中的 Referer / Cookie / User-Agent，也可手工覆盖。日志不输出请求头值或签名媒体地址。
- 缓存位置可选 **磁盘 / 内存**，默认磁盘。内存阈值默认 **512 MB**，可选 64 / 128 / 256 / 512 / 1024 / 2048 MB；HLS 分片、保存的清单与最终 MP4 合计达到阈值即停止报错，不回退磁盘，停止或换源后释放。按需分配的原生内存仍受设备可用内存限制。
- 取消下载使用并发集合的安全快照，避免连接集合变化触发 `NoSuchElementException`。
- 收到有效投屏地址并开始处理时，自动打开模块页面显示下载 / 转换进度；由当前可见的 Kazumi 页面启动，使用一次性请求防止心跳重复跳转。锁屏或 Kazumi 已在后台时，可通过模块服务通知返回页面。

完整模式需要有限视频结束后起播。过期链接需在 Kazumi 重新解析；403 不自动重试，网络错误 / 429 / 5xx 有限重试。不跳过失败分片，不放宽时长检查来接受明显截断的视频。

## 当前验证

1.0.12 新增收到投屏地址后自动打开模块页面；沿用 LSPosed 配置通道，不依赖 Flutter 菜单按钮 Hook。真机前台切换验证与签名包见本地开发总结。

1.0.11 本地开发包已完成 Release 构建与 Lint、64 项核心回归及新增的子清单内存越限错误回归。真机验证覆盖原生 RAM 读写、越限报错、HLS 内存转换、MP4 缓存开 / 关、磁盘缓存、HEAD / Range 与缓存释放；眼镜实际播放内存中的合成 H.264 / 双声道 AAC 视频，用户确认画面和声音正常。正式公开 Release 当前仍为 1.0.10。

1.0.10 已完成 Release 构建、Lint 和 **30 项**核心协议 / 合成媒体测试；已签名并覆盖安装，API 102、原生连接 Hook 和播放器请求头 Hook 已实测。原生初始化错误 12 已在该设备上修正。

用户已自行确认 **Sorani 多线程下载可用**。此前兼容 MP4 直接投屏已确认提供完整音视频轨道；用户也确认过转码文件开头的画面和声音。HEVC 10 位整集重编码、STREAM 在眼镜上的播放、所有来源和锁屏长时间运行仍需验证，下载可用不等同于这些项目全部通过。

当前设备发现仍由 Kazumi 提供，存在偶发无法发现眼镜的现象。**模块独立接管投屏尚未发布**。

## 开发

需要 JDK 21 和 Android SDK。固定工具版本：Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.20、NDK r27c、CMake 3.22.1；minSdk 29、targetSdk 35、compileSdk 37。

构建前配置 `JAVA_HOME` 为 JDK 21，在本地 `local.properties` 中指定 Android SDK 与 CMake 目录，并将 NDK r27c 解压到 `tools/runtime/android-ndk-r27c/`（当前 Gradle 配置使用该路径）。接受所需 Android SDK 许可证；电脑合成媒体测试需要 PATH 中的 FFmpeg / FFprobe。

```powershell
.\gradlew.bat :core:test :app:assembleRelease :app:lintRelease
```

仅构建 APK 可执行 `.\gradlew.bat :app:assembleRelease :app:lintRelease`，不表示媒体测试通过。输出位于 `app/build/outputs/apk/release/`。

签名使用外部目录的 `sign-key.jks` 与 `config.txt`，通过 `KAZUMI_SIGNING_DIR` 指定；本地默认 `D:\存档\安卓开发`。`config.txt` 包含 `storePassword`、`keyAlias`、`keyPassword`，密钥与密码不要提交。

| 路径 | 内容 |
| --- | --- |
| `app/` | API 102 入口、Native Hook、Android 媒体 / HTTP 服务、miuix UI |
| `core/` | HTTP / SOAP、媒体策略、任务状态、HLS 快照与并行缓存、电脑测试 |

APK 通过 Release 分发；`docs/` 与 `tools/` 仅保留在本地。密钥、来源媒体、调试日志、上传附件、构建缓存和检查用上游副本不提交。

源码采用 GPL-3.0，Android FFmpegKit 使用 Full-GPL 后端；依赖信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。停用模块并重启 Kazumi 可恢复原投屏流程。
