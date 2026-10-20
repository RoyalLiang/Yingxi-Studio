# 网盘官方接入：开发者配置

开放平台申请、客户端标识、应用签名及权限由开发者配置，普通用户不填写 AppID 或密钥。本文区分平台登记、授权开发和实际可播放三个阶段。

## 当前版本边界

Android WebDAV 添加页显示阿里云盘、百度网盘图标，并检查、打开已安装的官方 App；未安装时提示并提供官网入口。当前尚未接入 OAuth、回跳、令牌管理和网盘文件读取。打开官方 App 不代表已授权，也不会自动添加来源。

已有 OpenList、NAS 或自建 WebDAV 服务可点“使用 WebDAV 地址”，继续认证、浏览和播放。iOS alpha 目前只提供手动 HTTPS WebDAV。

## 阿里云盘：个人开发者配置

从[官方开发者门户](https://www.alipan.com/developer/f)登录。按平台显示的条件完成实人及其他必要认证；当前官方页面注明，一个开发者账号只能添加一个应用，可修改、不可删除。可创建应用并不等于所有接口能力已获批或已通过运行验证。

新建应用填写：

| 字段 | 映汐播放器配置 |
|---|---|
| 应用名称 | 映汐播放器 |
| 应用说明 | 个人媒体播放器，通过官方授权供用户浏览、播放自己的视频；不共享账号、令牌或下载链接 |
| 获取用户授权 | 获取用户头像昵称、读取用户云盘所有文件 |
| 公开客户端 | 启用 |
| 移动端接入 | 启用 Android 与 iOS |
| Android 应用包名 | `io.localplayer.app` |
| Android 应用签名 MD5 | `31d2ad9e8be856bb532919fb2c047c41` |
| iOS Bundle ID | 当前 alpha 为 `io.localplayer.app`；自行签名改名后须同步修改平台配置 |
| 授权回调 URI | 原生 PKCE 路径留空，不编造 HTTPS 地址 |

签名 MD5 已从正式 alpha.6 APK 的证书读取，不能使用调试包证书或对 SHA 摘要文本再次取 MD5。[官方 Android 接入说明](https://www.yuque.com/aliyundrive/zpfszx/qkwg88uf4t483tdi)要求公开客户端、移动端接入及签名 MD5。

双平台官方原生 PKCE 实现使用 `redirect_uri=oob`，无需自有 HTTPS 回调域名：[Android 实现](https://github.com/alibaba/aliyunpan-android-sdk/blob/main/opensdk/src/main/java/com/alicloud/databox/opensdk/auth/AliyunpanPKCECredentials.kt#L40-L49)、[iOS 实现](https://github.com/alibaba/aliyunpan-ios-sdk/blob/main/Sources/AliyunpanSDK/AliyunpanCredentials/AliyunpanPKCECredentials.swift#L22-L32)。官方 SDK 支持唤起已安装的网盘 App；未安装时可回退 H5。当前播放器仍是未安装提示，后续接入时应明确产品选择，不能把启动 App 当成 SDK 授权完成。

新建应用默认为“普通应用”，不是“认证应用”。平台分级会影响接口配额、下载链接有效期和文件分段并发；升级条件及当前规则见[官方应用分级](https://www.yuque.com/aliyundrive/zpfszx/mqocg38hlxzc5vcd)。本次登记不代替后续接口能力及播放验证。

只登记播放所需的用户基本信息和文件读取范围，不申请文件写入、共享相册或分享管理。具体可用接口、额度与审核状态以平台后台和后续调用结果为准。官方总流程与权限说明见[接入流程](https://www.yuque.com/aliyundrive/zpfszx/btw0tw)、[权限列表](https://www.yuque.com/aliyundrive/zpfszx/dspik0)。

## 百度网盘：个人主体的限制

百度官方资料目前注明，正式开放平台仅限企业开发者注册调用；个人体验密钥不可用于正式环境。因此个人主体先保留入口，不能宣称已完成正式接入。[百度官方说明](https://github.com/baidu-netdisk/mcp#使用准备)、[企业申请入口](https://pan.baidu.com/union/apply)。

将来具备企业主体后，按注册、认证、创建应用和接入授权流程申请。不要承诺一定唤起百度网盘 App：目前核对的第一方示例是浏览器或二维码授权。[官方授权示例](https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/examples/auth/main.go)。官方 SDK 使用 `basic,netdisk`；“播放器仅调用读取接口”是产品实现约束，不等于平台提供了更细的只读授权范围。

若采用服务端授权码换令牌，需要在开发者后端保存 SecretKey，回调与平台登记一致；不能把它打入 APK/IPA。自建后端是该接入方案的架构选择，不是已经核实的百度全平台统一硬性要求。

## 保存开发者本地配置

将 `deploy/cloud-integrations.example.json` 复制为已忽略的 `deploy/cloud-integrations.local.json`。只记录公开 `clientId`；阿里云盘原生 PKCE 的 `redirectUri` 留空。若选择有真实服务的 HTTPS 回调方案，才填写对应 URI。禁止把 AppSecret、账号密码、授权码或令牌存入此文件、聊天、Git 或安装包。

只申请了阿里云盘时运行：

```sh
python3 scripts/cloud_config.py --config deploy/cloud-integrations.local.json --provider aliyunpan --require-registration
```

不加 `--provider` 时检查两家配置；加 `--require-registration` 要求所选平台已记录客户端标识。工具只输出字段是否齐备，不输出标识值。空回调原本即受支持，不需要为通过校验填假域名。

此校验不联系平台，不检查审核、SDK 回调或网盘读取，也不会启用尚未开发的授权功能。下一阶段仍需接入并核对固定版本的 SDK/PKCE、回跳、令牌保存与撤销、目录和播放地址读取，再验证真实授权与视频 Range 播放。

品牌资源来源见 `third_party/cloud-brand-assets.json`；Android 包可见性只查询两个官方包，不使用全量应用查询权限。
