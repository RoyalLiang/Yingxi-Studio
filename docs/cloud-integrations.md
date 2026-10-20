# 网盘官方接入：开发者配置

网盘开放平台的申请、AppID / AppKey、签名及回调配置由应用开发者完成。普通用户不填写这些参数；完成正式接入后，用户只需跳转官方 App 登录并授权。

## 当前版本边界

Android WebDAV 添加页默认展示阿里云盘、百度网盘图标。点击会检查并打开已安装的官方 App；未安装时显示提示并提供官网入口。停用或无法启动的 App 显示暂不可用。当前官方 OAuth 适配尚未启用，打开 App 只进入它的正常启动页面，不是授权动作，不会创建可播放网盘来源。没有使用未核实的授权/设置页深链。

已有 OpenList、NAS 或自建 WebDAV 地址可点 **使用 WebDAV 地址**，保留原有认证、浏览和播放。iOS 初版只提供手动 HTTPS WebDAV；网盘官方授权尚未接入。

## 开发者申请顺序

1. 阿里云盘从 [官方 Android SDK](https://github.com/alibaba/aliyunpan-android-sdk) 的接入说明进入申请流程；百度网盘从 [开放平台](https://yun.baidu.com/union/home) 申请应用。按平台实际要求选择 Android / iOS 等应用类型，完成资格和权限审核；不承诺自动获批。
2. Android 当前包名为 `io.localplayer.app`。正式签名 SHA-1 为 `4F:BB:64:F1:5E:19:03:D6:60:DD:23:63:0D:63:3E:B5:AD:06:C3:DE`，SHA-256 为 `3D:9A:4C:A9:72:7E:B3:38:9E:3F:A4:3E:43:88:8A:D0:75:75:D6:44:7B:19:EF:A7:D7:41:9E:08:E2:C8:1D:A8`。不要登记调试包签名。
3. iOS 默认 Bundle ID 为 `io.localplayer.app`；需在自己的 Apple 开发者团队登记可用标识，具体签名、URL 回调和平台 SDK 要求取决于实际申请结果。
4. 将 `deploy/cloud-integrations.example.json` 的纯文件内容复制到 gitignored 的 `deploy/cloud-integrations.local.json`。仅填写公开 `clientId`，以及平台要求并确认的 HTTPS `redirectUri`；尚未确定的回调地址留空。
5. 运行 `python3 scripts/cloud_config.py --config deploy/cloud-integrations.local.json --require-registration`。工具只输出字段是否齐备，不输出参数值。模板及校验器属于开发者准备工具，不进入普通用户配置流程。
6. 申请和权限确认后，再按实际官方 SDK / OAuth 契约接通 App 授权回跳、令牌安全存储/刷新、文件读取与播放，并重新构建和验收。当前模板不是已接通的运行时 OAuth 配置；填完公开参数不会自动启用尚未实现的适配器。

不要把 Secret Key、网盘密码、Cookie、Access Token 或 Refresh Token 放进该 JSON、聊天、Git 或 APK。若官方方案需要客户端秘密，使用服务端秘密配置。后续可提供公开 AppID、获批权限范围和回调要求，再推进对接。

阿里云盘官方 SDK 需要应用 `app_id`，提供 PKCE 与服务端令牌方式；百度网盘官方服务基于 OAuth。[阿里云盘 SDK](https://github.com/alibaba/aliyunpan-android-sdk)、[百度官方 OAuth 说明](https://github.com/baidu-netdisk/mcp)

## 资源与安装检测

图标来自两家官网公开引用资源，仅标识服务，商标归各自权利人。地址、处理方式和摘要见 `third_party/cloud-brand-assets.json`。Android 仅在 `<queries>` 中声明两家官方 App 的包名，不申请完整应用清单权限。[Android 包可见性文档](https://developer.android.com/training/package-visibility/declaring)
