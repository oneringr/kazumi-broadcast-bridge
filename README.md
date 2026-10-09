<a id="readme-top"></a>

[![Release][release-shield]][release-url]
[![License][license-shield]][license-url]
[![Android][android-shield]][release-url]
[![Issues][issues-shield]][issues-url]

<div align="center">
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

Kazumi 投屏桥是使用 **libxposed API 102** 的 LSPosed 模块，目标是让 Kazumi 的视频来源以接收端兼容的 MP4 播放。模块仅作用于 `com.predidit.kazumi`，不修改 Kazumi APK。

投屏时，模块根据文件内容和音视频编码判断兼容性：兼容 MP4 可直接转发或先缓存；HLS 等来源重新封装，必要时转为 H.264 / AAC，再通过手机上的 HTTP 服务提供给眼镜。

- **两种投屏模式：** 默认完整转换后播放，可选边转换边投屏。
- **HLS 并发下载：** 有限视频的完整模式支持 1 / 2 / 4 / 8 / 16 / 32 路，保留分片顺序、加密信息与请求头。
- **可选缓存 MP4：** 默认关闭；开启后完整下载再投屏，减少来源网络波动造成的中断。
- **磁盘或内存缓存：** 内存默认阈值 512 MB，达到阈值停止并报错，停止或换源后释放。
- **自动显示进度：** Kazumi 在前台时，收到有效投屏地址后自动打开模块页面，显示下载、转换进度与错误。

**设备发现与设备选择仍使用 Kazumi 原来的远程投屏菜单。** 本版本尚未提供模块独立设备列表。

<a id="built-with"></a>

### 技术栈

| 组件 | 用途 |
| --- | --- |
| [libxposed API 102](https://github.com/libxposed/api) | LSPosed Hook 与框架配置通道 |
| [miuix](https://github.com/compose-miuix-ui/miuix) / Jetpack Compose | 模块设置与状态界面 |
| [ShadowHook](https://github.com/bytedance/android-inline-hook) | 原生连接 Hook |
| [FFmpegKit Full-GPL](https://github.com/sk3llo/ffmpeg_kit_flutter_android/tree/full-gpl) | 媒体探测、重新封装与转码 |
| Kotlin / C++ | Android 服务、媒体缓存与原生内存输出 |

组件版本和许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

<p align="right"><a href="#readme-top">返回顶部</a></p>

<a id="getting-started"></a>

## 开始使用

### 使用条件

- 已配置可使用 **API 102** 的 LSPosed 环境，模块作用域勾选 Kazumi。
- Android 10 或以上、arm64-v8a；实际调试环境为 **小米 15 Pro / Android 16 / 官方 LSPosed 2.2.1 / Kazumi 2.3.8**，其他组合尚未完整验证。
- DJI Goggles 2 开启无线串流，手机与眼镜连接同一局域网。

### 安装

1. 从 [Release][release-url] 下载并安装 **1.0.12 签名 APK**，可覆盖安装旧版本。
2. 在 LSPosed 中启用模块，将作用域设为 **Kazumi**。
3. 完全退出并重新打开 Kazumi，使模块 Hook 生效。
4. 打开模块，按需选择缓存与投屏模式，然后点击 **开启服务**；允许通知和后台运行。

升级前停止模块服务。覆盖安装后重新开启服务，并完全退出、重启 Kazumi；原生 Hook 不会热替换。

<p align="right"><a href="#readme-top">返回顶部</a></p>

<a id="usage"></a>

## 使用与设置

1. 让眼镜停留在无线串流页面。
2. 在 Kazumi 播放视频，点击 **远程投屏**，从原设备列表选择眼镜。
3. 模块收到有效投屏地址后自动显示进度。Kazumi 已在后台或手机锁屏时，可点击模块服务通知打开页面。
4. 需要缓存或转换时，完整模式等待处理完成后起播；兼容 MP4 且关闭缓存时可直接转发。结束时停止服务，按需清理磁盘缓存。

### 设置说明

设置变更在下一次投屏生效。

| 设置 | 默认值 | 行为 |
| --- | --- | --- |
| 投屏模式 | 完整转换后投屏 | 生成完整 MP4 并检查音视频轨道时长，提供 HEAD / Range；边转换模式生成分片 MP4，兼容性取决于接收端 |
| 缓存 MP4 | 关闭 | 关闭时兼容 MP4 直接转发；开启后完整下载并校验再投屏 |
| HLS 下载并发 | 4 路 | 可选 1 / 2 / 4 / 8 / 16 / 32 路，仅适用于完整模式的有限 HLS；来源限速时可降低并发 |
| 缓存位置 | 磁盘 | 可切换内存；内存媒体不自动回退磁盘 |
| 内存阈值 | 512 MB | 可选 64 / 128 / 256 / 512 / 1024 / 2048 MB；缓存分片、保存的清单与最终 MP4 共用预算，达到阈值停止报错 |

HLS 清单在任务内保存并复用，正确解析相对分片、子清单、KEY / MAP，并沿用播放器的 Referer / Cookie / User-Agent；也可在模块中手工覆盖请求头。日志不输出请求头值或签名媒体地址。

停止、换源或失败后释放任务内存。磁盘缓存清理需先停止服务。内存按需分配，设备实际可用内存仍会影响可处理的视频大小。

### 使用限制

- 对需要缓存或转换的有限视频，完整模式需先完成下载与封装，起播等待时间取决于片长、网络与是否需要转码。
- 直播、边转换模式及含 BYTERANGE 的 HLS 不启用上述并发预取。边转换模式生成期间不支持任意拖动。
- 缓存减少对来源网络的持续依赖；手机到眼镜之间仍需稳定的局域网连接。
- 过期链接需在 Kazumi 重新解析；403 不自动重试，网络错误、429 和 5xx 仅有限重试。失败分片不会被跳过，明显截断的视频不会被接受。
- 眼镜偶发无法出现在 Kazumi 设备列表中的问题仍存在；模块当前没有独立发现或手动 IP 入口。

停用模块并重启 Kazumi，可恢复原投屏流程。

<p align="right"><a href="#readme-top">返回顶部</a></p>

<a id="compatibility"></a>

## 兼容性与验证

当前发布版本为 **1.0.12 / versionCode 13**。发布包仅包含 arm64-v8a，原生库兼容 16 KiB 页面。

| 范围 | 已观察的结果 |
| --- | --- |
| 构建与检查 | 1.0.12 Release 构建、Lint 通过，签名正式包已覆盖安装 |
| 核心回归 | 缓存开发完成 64 项核心 / 媒体回归，随后新增的 HLS 子清单内存越限错误回归通过 |
| 真机缓存 | 原生内存读写、越限中止、HLS 内存转换、MP4 缓存开 / 关、磁盘缓存、HEAD / Range、停止后释放均通过 |
| 实际接收端 | 眼镜播放内存中的合成 H.264 / 双声道 AAC 视频，用户确认画面与声音正常 |
| 自动打开页面 | 真机调试入口验证立即跳转、同一请求不重复跳转、后台不抢占页面、取消后不重放 |
| 来源反馈 | 用户确认 Sorani 多线程下载可用；这不代表所有来源或整集播放均已验证 |

真机 HLS 缓存测试使用小型单分片视频与 32 路配置，实际 16 / 32 路峰值并发由电脑 HTTP 测试验证。自动跳转本轮未另做真实来源的整集投屏验证。HEVC 10 位整集重编码、锁屏长时间运行和边转换模式在眼镜上的播放仍需补测。

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

克隆仓库并切换到本次发布的源码版本：

```powershell
git clone https://github.com/oneringr/kazumi-broadcast-bridge.git
cd kazumi-broadcast-bridge
git checkout v1.0.12
```

配置 `JAVA_HOME` 指向 JDK 21，在本地 `local.properties` 中填写 Android SDK 与 CMake 路径，并接受所需 SDK 许可证。当前 Gradle 固定使用 `tools/runtime/android-ndk-r27c/`，需要自行将 NDK r27c 解压到该目录；本地工具目录不会随源码分发。

签名使用自己的 `sign-key.jks` 与 `config.txt`，放在仓库外，通过 `KAZUMI_SIGNING_DIR` 指定目录。配置文件内容为：

```properties
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

```powershell
$env:KAZUMI_SIGNING_DIR = "D:/android-signing"
.\gradlew.bat :core:test :app:assembleRelease :app:lintRelease
```

产物位于 `app/build/outputs/apk/release/`。只构建 APK 可执行 `:app:assembleRelease :app:lintRelease`，这不表示媒体测试通过。使用自己的签名构建时，无法直接覆盖本项目发布包。

| 路径 | 职责 |
| --- | --- |
| `app/` | API 102 入口、原生 Hook、Android 服务、媒体输出与 miuix 界面 |
| `core/` | HTTP / SOAP、媒体策略、任务状态、HLS 快照、缓存与电脑测试 |
| `app/src/debug/` | 真机测试入口，发布 APK 不包含这些入口 |

`docs/`、`tools/`、签名材料、来源媒体、调试日志、附件和构建产物均不提交；APK 通过 Release 分发。

<p align="right"><a href="#readme-top">返回顶部</a></p>

<a id="roadmap"></a>

## 路线图

- [x] 兼容 MP4 转发、HLS 封装与必要时转码。
- [x] HLS 清单复用、16 / 32 路并发与取消竞争修复。
- [x] MP4 可选缓存、磁盘 / 内存切换与内存阈值。
- [x] 收到有效投屏地址后自动显示模块进度页。
- [ ] 评估可靠的投屏按钮入口与模块独立设备页，补充设备发现和播放控制。
- [ ] 补测更多 HLS 来源、整集转码、断网 / 换集、锁屏长时间运行与边转换兼容性。

独立接管投屏的按钮 Hook 开发目前暂停，上述待办不属于已发布功能。问题与建议可通过 [Issues][issues-url] 提交。

<a id="contributing"></a>

## 参与贡献

欢迎提交问题或 Pull Request。报告问题时请提供手机型号、Android / LSPosed / Kazumi / 模块版本、缓存与并发设置、复现步骤，以及去除敏感信息的日志；请勿公开 Cookie、签名媒体链接或签名密钥。

开发贡献请从 Fork 的功能分支发起 Pull Request，说明行为变化和验证结果。缓存与协议改动应运行相关核心测试；Android 或原生媒体改动应说明真机验证范围。

<a id="license"></a>

## 许可

项目源码采用 **GPL-3.0**，见 [LICENSE](LICENSE)。APK 使用 FFmpegKit Full-GPL 后端；第三方组件保留各自许可，详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

<a id="acknowledgments"></a>

## 联系与致谢

项目维护与讨论通过 [GitHub 仓库](https://github.com/oneringr/kazumi-broadcast-bridge) 和 [Issues][issues-url] 进行。

感谢 [Kazumi](https://github.com/Predidit/Kazumi)、[libxposed](https://github.com/libxposed)、[miuix](https://github.com/compose-miuix-ui/miuix)、[ShadowHook](https://github.com/bytedance/android-inline-hook) 与 FFmpeg 生态提供的基础组件。本 README 的结构参考 [Best-README-Template](https://github.com/othneildrew/Best-README-Template)。

<p align="right"><a href="#readme-top">返回顶部</a></p>

[release-shield]: https://img.shields.io/github/v/release/oneringr/kazumi-broadcast-bridge?style=for-the-badge
[release-url]: https://github.com/oneringr/kazumi-broadcast-bridge/releases/latest
[license-shield]: https://img.shields.io/github/license/oneringr/kazumi-broadcast-bridge?style=for-the-badge
[license-url]: https://github.com/oneringr/kazumi-broadcast-bridge/blob/main/LICENSE
[android-shield]: https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white
[issues-shield]: https://img.shields.io/github/issues/oneringr/kazumi-broadcast-bridge?style=for-the-badge
[issues-url]: https://github.com/oneringr/kazumi-broadcast-bridge/issues
