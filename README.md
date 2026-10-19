# 映汐 · Yingxi

![映汐](assets/brand/yingxi-icon-preview.png)

简易、好用的 Android 本地与局域网视频播放器。当前为 **alpha**，面向 Android 15 / 16 手机；以 IINA 的产品理念为参考，使用 Kotlin、Jetpack Compose 和 Media3 原生实现。

- 首页、来源、播放记录；断点续播与记录清理。
- 首页自动发现 SMB/SFTP 服务，SMB 认证后选择共享；来源与目录支持收藏、分组和本机加密密码。发现候选仍需连接验证。
- 本机文件夹经 Android 系统授权后，在应用内显示返回按钮和可点击面包屑；也可通过系统选择单个视频。
- 本机文件夹和 SMB/SFTP 同目录视频队列：上一集、下一集、选集与自动连续播放；按目录显示顺序，末项播放结束后停止。
- 默认横屏影院悬浮播放，六档 0.75–4× 倍速、右侧半透明选集、细进度条；亮度、音量和带真实关键帧预览的进度手势。支持可用音轨/字幕选择、关闭字幕、本机 SRT/VTT。
- 播放中回到桌面自动画中画，也可手动进入；“后台播放”入口保留声音，媒体通知提供播放/暂停、切集、拖动和返回当前播放。
- OpenList / WebDAV 作为长期来源，认证、目录浏览、Range 拖动播放；跨站重定向不传递挂载账号密码。
- 首页右上角设置：版本说明、检查更新、校验后下载并调用 Android 安装器。
- 凭据仅在本机加密保存，SFTP 需核验主机指纹。

## 安装与构建

验证包来自 Actions 的 Debug artifact；签名 alpha 包来自 GitHub Releases，二者用途与签名分开。正式发布密钥与此前调试签名不同，签名版不能直接覆盖旧调试安装；卸载会清除本机记录及来源配置。

使用 JDK 17、Android SDK 36 / Build Tools 36.0.0：

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/build.py :core:test :app:assembleDebug :app:lintDebug
```

脚本在项目外暂存构建，输出版本化 APK 和 SHA256。`JAVA_HOME`、`ANDROID_HOME` 可指定工具链；`PLAYER_BUILD_DIR` 可指定暂存目录。普通本地文件系统也可运行 `sh gradlew :app:assembleDebug`。

`version.json` 为版本配置来源，当前 `0.2.0-alpha.4` / code `6`。版本名称、Android 安装序号相互独立，不把 alpha 阶段或版本基数写死。详见[版本与发布](docs/releasing.md)。

## 验证边界

受控环回协议测试和 Android 模拟器功能结果不代表一加 Turbo 6V 或真实 NAS 的兼容性与流畅度。未广播服务、隔离网络和非默认端口可能需要手动添加或补充探测。

Home、锁屏或显式后台入口保留播放；播放器返回按钮、关闭画中画或从最近任务划掉应用会结束播放。画中画依赖系统支持和用户许可。当前不承诺 TV、投屏、媒体墙、SMB1、私钥登录、ASS/PGS 与完整 HDR 矩阵。外部字幕目前由用户选择，不自动匹配 NAS 配套字幕。

队列只保存在本机内存，最多包含点选视频附近的 200 项；超出时选集面板会说明。点选项恢复进度，后续项从头播放；系统单文件选择和本机历史重开仅播放该文件。进程结束后需从来源重新打开队列。

[播放操作与局域网来源](docs/player-controls.md) · [文件导航](docs/file-navigation.md) · [工程与数据规则](CONTRIBUTING.md) · [第三方许可资料](third_party)

项目许可证尚待维护者决定；第三方依赖保留各自许可与 NOTICE。IINA 用作设计参考，未复用其图标。

[网盘挂载与设置](docs/network-settings.md) · [iOS / iPadOS 方案](docs/apple-platform-plan.md)
