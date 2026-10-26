<a id="readme-top"></a>

[![Release][release-shield]][release-url]
[![License][license-shield]][license-url]
[![Android][android-shield]][release-url]
[![Issues][issues-shield]][issues-url]

<div align="center">
  <img src="app/src/main/res/drawable/application_icon.jpg" alt="Kazumi 投屏桥图标" width="96" height="96" />
  <h1>Kazumi 投屏桥</h1>
  <p>为 Kazumi 向 DJI Goggles 2 投屏提供 MP4 转换、并发下载与可选缓存。</p>
  <p>
    <a href="https://github.com/oneringr/kazumi-broadcast-bridge/releases/latest"><strong>下载最新版本 »</strong></a>
    <br /><br />
    <a href="#getting-started">安装指南</a>
    &middot;
    <a href="https://github.com/oneringr/kazumi-broadcast-bridge/issues/new">报告问题</a>
    &middot;
    <a href="https://github.com/oneringr/kazumi-broadcast-bridge/issues/new">提出功能建议</a>
  </p>
</div>

<details>
<summary>目录</summary>

- [关于项目](#about-the-project)
- [技术栈](#built-with)
- [开始使用](#getting-started)
- [使用与设置](#usage)
- [兼容性与验证](#compatibility)
- [源码构建](#building)
- [路线图](#roadmap)
- [参与贡献](#contributing)
- [许可](#license)
- [联系与致谢](#acknowledgments)

</details>

<a id="about-the-project"></a>

## 关于项目

Kazumi 投屏桥是基于 **libxposed API 102** 的 LSPosed 模块，作用于 `com.predidit.kazumi`，无需修改 Kazumi APK。模块根据文件内容和音视频编码处理来源：兼容 MP4 直接转发或缓存，HLS 等来源重新封装，必要时转为 H.264 / AAC，再通过手机上的 HTTP 服务提供给眼镜。

支持完整转换后投屏、边转换边投屏、HLS 并发下载、磁盘或内存缓存，以及失败重试和后台保护。界面分为 **模块、投屏、设置、关于** 四页，分别提供连接状态、处理进度、配置和项目信息。

<a id="built-with"></a>

### 技术栈

| 组件 | 用途 |
| --- | --- |
| [libxposed API 102](https://github.com/libxposed/api) | LSPosed Hook 与框架配置 |
| [miuix](https://github.com/compose-miuix-ui/miuix) / Jetpack Compose | 设置与状态界面 |
| [ShadowHook](https://github.com/bytedance/android-inline-hook) | 原生连接 Hook |
| [FFmpegKit Full-GPL](https://github.com/sk3llo/ffmpeg_kit_flutter_android/tree/full-gpl) | 媒体探测、封装与转码 |
| Kotlin / C++ | Android 服务、缓存与原生内存输出 |

组件版本和许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

<a id="getting-started"></a>

## 开始使用

### 使用条件

- Android 10 或以上、arm64-v8a，以及支持 **API 102** 的 LSPosed 环境；已调试组合见[兼容性与验证](#compatibility)。
- DJI Goggles 2 开启无线串流，手机与眼镜连接同一局域网。

### 安装与升级

1. 从 [Release][release-url] 下载并安装 **1.1.0 签名 APK**，可覆盖安装旧版本。
2. 在 LSPosed 中启用模块，将作用域设为 **Kazumi**。
3. 完全退出并重新打开 Kazumi，使模块 Hook 生效。
4. 打开模块，在 **设置** 页选择缓存与投屏模式，在 **投屏** 页点击 **开启服务**；允许通知和后台运行。

升级前停止模块服务。覆盖安装后重新开启服务，并完全退出、重启 Kazumi，使新的原生 Hook 生效。

<a id="usage"></a>

## 使用与设置

1. 让眼镜停留在无线串流页面。
2. 在 Kazumi 播放视频，点击 **远程投屏**，从设备列表选择眼镜。
3. Kazumi 在前台时，模块收到有效投屏地址后自动打开 **投屏** 页显示进度；后台或锁屏时可点击服务通知查看。
4. 结束投屏后停止服务，按需清理磁盘缓存。

**关于** 页可手动检查更新和查看更新日志，点击时联网获取最新稳定版本；下载按钮在浏览器中打开 GitHub Release。

### 设置

设置变更在下一次投屏生效。

| 设置 | 默认值 | 行为 |
| --- | --- | --- |
| 投屏模式 | 完整转换后投屏 | 完整模式生成 MP4 并校验音视频轨道时长，支持 HEAD / Range；边转换模式生成分片 MP4，兼容性取决于接收端，生成期间不能任意拖动 |
| 缓存 MP4 | 关闭 | 关闭时兼容 MP4 直接转发；开启后完整下载并校验再投屏 |
| HLS 下载并发 | 4 路 | 可选 1 / 2 / 4 / 8 / 16 / 32 路，适用于完整模式的有限 HLS；直播、边转换模式和含 BYTERANGE 的 HLS 不启用并发预取 |
| 缓存位置 | 磁盘 | 可切换内存；内存媒体不自动回退磁盘 |
| 内存阈值 | 512 MB | 可选 64 / 128 / 256 / 512 / 1024 / 2048 MB；分片、清单和最终 MP4 共用预算，达到阈值停止并报错 |

完整模式处理需要缓存或转换的视频时，先完成下载与封装再起播，等待时间取决于片长、网络与转码需求。来源限速时可降低 HLS 并发；缓存播放仍需手机与眼镜之间有稳定的局域网连接。

HLS 清单在任务内复用，解析相对分片、子清单与 KEY / MAP，保留分片顺序及加密信息。请求沿用播放器的 Referer / Cookie / User-Agent，也可在设置中覆盖；日志不输出请求头值或签名媒体地址。

停止、换源或一般处理失败后释放任务内存；符合下述强制投屏条件的 MP4 会保留，重试时替换。内存按需分配，可处理的视频大小受设备可用内存限制。清理磁盘缓存前需停止服务。

### 故障处理与后台运行

| 情况 | 处理方式 |
| --- | --- |
| 处理失败 | 在投屏页点击 **重试处理**，使用当前配置与原来源请求头重新准备视频，完成后发送播放命令 |
| 来源过期或请求失败 | 过期链接需在 Kazumi 重新解析；403 不自动重试，网络错误、429 和 5xx 有限重试，失败分片不会被跳过 |
| MP4 时长偏短 | 默认拒绝投屏；仅当最终 MP4 可读取、全部必需音视频轨道时长有效，且短于来源预估时长时，可手动 **强制投屏**。按实际生成时长播放，可能缺少结尾；缺失轨道、文件不可读或缓存越限等错误不能强制忽略 |
| 找不到眼镜 | 设备发现由 Kazumi 提供，存在偶发无法列出眼镜的问题；模块暂无独立设备列表或手动 IP 入口 |

点击投屏页的视频来源可复制完整地址；Android 13 及以上将其标记为敏感剪贴板内容。地址可能包含鉴权参数，分享前请脱敏。

在设置页通过 **电池优化** 申请系统豁免，通过 **后台运行设置** 打开应用信息页并允许后台运行。活动投屏期间，CPU 保活锁采用 2 小时租期、每 30 分钟续期，停止或失败时释放；Android 10–13 额外使用 Wi-Fi 保活锁。系统和厂商的省电策略仍可能限制后台运行。

停用模块并重启 Kazumi，可恢复原投屏流程。

<p align="right"><a href="#readme-top">返回顶部</a></p>

<a id="compatibility"></a>

## 兼容性与验证

当前发布版本为 **1.1.0 / versionCode 14**，原生库兼容 16 KiB 页面。已调试环境为 **小米 15 Pro / Android 16 / 官方 LSPosed 2.2.1 / Kazumi 2.3.8 / DJI Goggles 2**；其他组合尚未完整验证。

以下为发布检查与既有测试记录：

| 范围 | 结果与测试条件 |
| --- | --- |
| 构建与回归 | 1.1.0 Release 构建、Lint 和 72 项核心 / 媒体回归通过，覆盖缓存、并发和必需轨道时长校验 |
| 真机缓存 | 原生内存读写、越限中止、HLS 内存转换、MP4 缓存开 / 关、磁盘缓存、HEAD / Range、停止后释放通过；HLS 使用小型单分片视频与 32 路配置 |
| 并发峰值 | 16 / 32 路实际峰值通过电脑 HTTP 测试验证 |
| 眼镜播放 | 内存中的合成 H.264 / 双声道 AAC 视频，用户确认画面与声音正常 |
| 自动打开页面 | 真机调试入口验证立即跳转、同一请求不重复跳转、后台不抢占页面、取消后不重放 |
| 来源反馈 | 用户确认 Sorani 多线程下载可用 |

待验证场景包括真实来源的整集投屏与自动跳转、HEVC 10 位整集重编码、锁屏长时间运行，以及边转换模式在眼镜上的播放。

<a id="building"></a>

## 源码构建

### 开发环境

| 项目 | 版本 |
| --- | --- |
| JDK | 21 |
| Gradle / AGP / Kotlin | 9.7.1 / 9.4.1 / 2.4.20 |
| Android SDK | compileSdk 37、targetSdk 35、minSdk 29 |
| NDK / CMake | r27c（27.2.12479018）/ 3.22.1 |
| 电脑媒体测试 | PATH 中可用的 FFmpeg / FFprobe |

```powershell
git clone https://github.com/oneringr/kazumi-broadcast-bridge.git
cd kazumi-broadcast-bridge
git checkout v1.1.0
```

配置 `JAVA_HOME` 指向 JDK 21，在 `local.properties` 中填写 Android SDK 与 CMake 路径，并接受所需 SDK 许可证。将 NDK r27c 解压到 Gradle 固定使用的 `tools/runtime/android-ndk-r27c/`，本地工具需自行准备。

将自己的 `sign-key.jks` 与 `config.txt` 放在仓库外，通过 `KAZUMI_SIGNING_DIR` 指定目录。`config.txt` 内容：

```properties
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

```powershell
$env:KAZUMI_SIGNING_DIR = "D:/android-signing"
.\gradlew.bat :core:test :app:assembleRelease :app:lintRelease
```

`:core:test` 运行核心与媒体测试；`:app:assembleRelease :app:lintRelease` 构建并检查 APK。产物位于 `app/build/outputs/apk/release/`。自己的签名与发布包不同，无法直接覆盖安装。

| 路径 | 职责 |
| --- | --- |
| `app/` | API 102 入口、原生 Hook、Android 服务、媒体输出与界面 |
| `core/` | HTTP / SOAP、媒体策略、任务状态、HLS 快照、缓存与电脑测试 |
| `app/src/debug/` | 真机测试入口，仅用于调试构建 |

`docs/`、`tools/`、签名材料、来源媒体、日志、附件和构建产物保留在本地；APK 通过 Release 分发。

<p align="right"><a href="#readme-top">返回顶部</a></p>

<a id="roadmap"></a>

## 路线图

- [ ] 评估独立投屏入口、设备页和播放控制；按钮 Hook 开发目前暂停。
- [ ] 扩展 HLS 来源、整集转码、断网 / 换集、锁屏运行与边转换兼容性测试。

<a id="contributing"></a>

## 参与贡献

通过 [Issues][issues-url] 报告问题时，请提供手机型号、Android / LSPosed / Kazumi / 模块版本、缓存与并发设置、复现步骤及脱敏日志。请勿公开 Cookie、签名媒体链接或签名密钥。

代码贡献请从 Fork 的功能分支发起 Pull Request，说明行为变化和验证结果。缓存与协议改动应运行相关核心测试；Android 或原生媒体改动应说明真机验证范围。

<a id="license"></a>

## 许可

源码采用 **GPL-3.0**，见 [LICENSE](LICENSE)。APK 使用 FFmpegKit Full-GPL 后端；第三方组件保留各自许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

<a id="acknowledgments"></a>

## 联系与致谢

项目维护与讨论通过 [GitHub 仓库](https://github.com/oneringr/kazumi-broadcast-bridge) 和 [Issues][issues-url] 进行。

感谢 [Kazumi](https://github.com/Predidit/Kazumi)、[libxposed](https://github.com/libxposed)、[miuix](https://github.com/compose-miuix-ui/miuix)、[ShadowHook](https://github.com/bytedance/android-inline-hook) 与 FFmpeg 生态。本 README 的结构参考 [Best-README-Template](https://github.com/othneildrew/Best-README-Template)。

<p align="right"><a href="#readme-top">返回顶部</a></p>

[release-shield]: https://img.shields.io/github/v/release/oneringr/kazumi-broadcast-bridge?style=for-the-badge
[release-url]: https://github.com/oneringr/kazumi-broadcast-bridge/releases/latest
[license-shield]: https://img.shields.io/github/license/oneringr/kazumi-broadcast-bridge?style=for-the-badge
[license-url]: https://github.com/oneringr/kazumi-broadcast-bridge/blob/main/LICENSE
[android-shield]: https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white
[issues-shield]: https://img.shields.io/github/issues/oneringr/kazumi-broadcast-bridge?style=for-the-badge
[issues-url]: https://github.com/oneringr/kazumi-broadcast-bridge/issues
