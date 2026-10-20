# iOS 与 iPadOS 的实现路径

本文件记录平台边界与后续迁移方向。仓库现已加入独立的 SwiftUI / AVFoundation iOS 初版工程，实际构建、签名状态与验证范围见 [iOS alpha](ios-alpha.md) 及发布附带报告；不能将方案视为全功能迁移已完成。

## 建议保留原生播放器，逐步共享业务逻辑

建议 Android 继续使用 Compose／Media3，iOS 和 iPadOS 使用 SwiftUI 与 AVFoundation。先用 WebDAV 做苹果平台的能力验证，再决定是否引入 Kotlin Multiplatform 共享目录模型、队列策略、续播身份、来源排序和设置策略。

当前 `core` 是 Kotlin/JVM 模块，包含 Java、SMBJ、SSHJ 和 OkHttp 调用；改一个 Gradle target 就能编译成 iOS 的假设不成立。共享逻辑需先移除 JVM 依赖，网络、密钥与播放生命周期通过平台适配实现。KMP 支持共享业务逻辑与平台实现的区分，但不会自动替换这些现有依赖。[Kotlin Multiplatform 代码共享](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html)

| 路径 | 适用条件 | 主要代价 |
| --- | --- | --- |
| 原生双端，逐步引入 KMP（建议） | 优先保留播放性能与平台行为 | 两套界面；先建立可移植的业务逻辑 |
| Compose Multiplatform 共享界面 | 之后确认高比例共享 UI 是目标 | 仍需适配播放器、画中画、文件授权及系统栏 |
| Flutter 等整体改写 | 明确接受重写与重新验证 | 现有 Compose 页面和 Media3 会话代码不能直接复用 |

## 按实际变化划分接口

- **来源目录**：连接、列目录、获取可随机读取的文件；共享稳定来源与文件身份。Android 可继续使用现有协议实现，苹果端使用可用的原生协议实现。
- **媒体读取与播放**：平台负责读取范围、取消、鉴权、临时地址刷新和播放生命周期。苹果端可评估 URLSession 与 AVAssetResourceLoaderDelegate 的组合；该 delegate 支持资源请求、取消、续期和认证回调，具体吞吐仍需原型验证。[Apple 资源加载接口](https://developer.apple.com/documentation/avfoundation/avassetresourceloaderdelegate)
- **凭据与文件授权**：Android 使用 Keystore 和系统文件授权；苹果端使用 Keychain 和系统文档选择器。业务层不保存明文密码或平台文件句柄。
- **更新**：Android 侧载包沿用当前 APK 流程；标准 iOS／iPadOS 分发使用 App Store／TestFlight，不能直接复用应用内下载 APK 或自我安装代码的逻辑。[Apple 2.5.2 要求](https://developer.apple.com/app-store/review/guidelines/#software-requirements)

## 先验证苹果端最小播放链

首个验证目标建议限定为：添加 HTTPS WebDAV 来源、认证与目录浏览、H.264／HEVC 样片播放、远距离拖动、队列与续播、画中画和后台音频。先测实际容器、音轨、字幕与 HDR，不能把 Android 的格式支持直接承诺给苹果端。超出原生解码能力时，再评估额外播放器及其许可、体积和维护成本。

iPad 需要适配侧栏、分栏浏览、横竖屏、分屏与窗口变化，复用同一媒体会话；不把手机界面等比例放大。iOS／iPadOS 的构建签名、设备验证、隐私声明与分发需在选定此路线后另行开展。
