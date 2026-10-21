# iOS 27 alpha

当前已按用户要求暂停后续发布；仅保留手动构建，恢复需用户明确要求。

此工程为原生 SwiftUI / AVFoundation 应用，显示名 **映汐放映室**，默认 Bundle ID `io.localplayer.app`，iOS 最低版本为 **27.0**。它独立于 Android Kotlin/JVM 实现。

## 交付物

- `yingxi-ios-<version>-unsigned.ipa`：真实 iPhone 架构的未签名包，必须使用自己的有效 Apple 签名和描述文件重签后安装。不是可直接安装的签名 IPA，也没有上传 TestFlight。
- `yingxi-ios-<version>-simulator-<arch>.zip`：仅用于匹配 CPU 架构的 iOS 模拟器，不能安装到 iPhone。需要支持 iOS 27 模拟器的 Xcode。
- `yingxi-ios-release.json`、`ios-validation.json`、`IOS-SHA256SUMS`：版本、源码提交、SDK、架构、签名状态、构建/测试与文件摘要。

用户已选择先交付未签名 IPA 和模拟器包，后续自行签名。若 Bundle ID 已被其他开发者占用，重签时应使用自己团队名下可用的标识，并匹配描述文件及权限；不要复用 Android 签名证书。

## 当前功能

本机视频通过系统文件选择器复制到播放器的 Imported 目录；使用 iOS 原生播放器解码，保存观看位置。来源页支持手动添加 **HTTPS WebDAV**、验证目录、读取文件夹、按名称自然排序、远程视频随机读取。密码使用仅本设备、解锁后可访问的 Keychain 项保存。

目录查询最多读取 4 MiB，不解析外部实体；仅接受来源内的目录条目。跨站 HTTPS 媒体重定向不携带来源 Authorization 或 Cookie。每个媒体读取分块最多 1 MiB，取消时终止对应网络任务。远程服务器需要有效证书和 HTTP Range 支持；不关闭 TLS 验证，不支持明文 HTTP。

SMB、SFTP、额外视频容器/解码器，以及 Android 完整的自定义全屏手势尚未迁移。此版不能视为 Android 的功能对齐版本。

## 构建与验证

完整 Xcode 27 或更新版本及匹配的 iOS SDK 是前提。仅安装 Command Line Tools 不足以构建设备应用。项目提供 GitHub 官方 `xcode-27` runner 的构建流程，避免依赖本机未安装的 SDK。

```sh
python3 scripts/build_ios.py --output <临时输出目录>
```

构建在临时目录进行；不把 Xcode 用户状态、DerivedData、AppleDouble 或扩展属性带进源码和 ZIP。打包只写普通文件内容与必要的可执行权限，拒绝符号链接和禁止的元数据名称。

流程执行核心 Swift 测试，构建 unsigned iphoneos 和 simulator App，创建本次独占的 iOS 27 模拟器，生成受控 H.264 测试片，检查真实解码尺寸与播放时间，并在结束后只关闭和删除该模拟器。最终成功记录以发布附带的报告为准，不把源代码存在等同构建通过。

设备 IPA 仍需重签。Apple 签名分发要求见 [Apple beta 分发文档](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases)；SDK 要求见 [Xcode 系统要求](https://developer.apple.com/xcode/system-requirements)；云构建环境见 [GitHub Xcode 27 公告](https://github.com/actions/runner-images/issues/14404)。

阿里云盘接入使用公开客户端 PKCE、官方 AppLink 与原生回跳，令牌保存在 Keychain；目录与历史保存稳定文件标识，临时链接仅供当前播放。详细配置和真机验证边界见 [网盘接入](cloud-integrations.md)。自行签名时保持已登记 Bundle ID，否则应同步开放平台配置。
