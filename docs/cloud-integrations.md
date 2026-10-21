# 网盘官方接入：开发者配置

开放平台申请、客户端标识、应用签名及权限由开发者配置，普通用户不填写 AppID 或密钥。本文区分平台登记、授权开发和实际可播放三个阶段。

## 当前接入实现与验证边界

Android 与 iOS 已加入阿里云盘公开客户端 PKCE 登录、原生回跳、账户安全存储、默认盘目录分页与视频分段读取。未安装官方 App 时提示安装。只有换码成功且读取到真实账户与默认盘标识后才添加来源。百度正式接入仍受主体条件限制，保留入口；已有 OpenList、NAS 或自建 WebDAV 继续通过地址连接。

公开 AppID 编译在 Kotlin `AliyunConfig` 与 Swift `AliyunConfiguration` 中；两端均不接受 AppSecret。修改 AppID 后需同步回调 scheme 和平台包名/签名登记，并重新构建。`cloud_config.py` 只核对开发者登记资料，不会动态修改已安装应用。

当前官方公开授权入口已用注册 AppID 核对：Android `source=app` 返回 `smartdrive` URI；iOS `source=appLink` 返回官方 HTTPS `/applink/authorize` Universal Link。完整的真实账号授权、目录与视频仍需在真机完成，不把接口可达或受控测试写成网盘联调通过。

Android 真机联调反馈：已在阿里云盘同意授权，回到播放器后 HTTP 400，范围缩小至换码或账户读取，尚未取得具体阶段及官方业务错误码。alpha.8 起错误框显示固定操作名称、允许显示的官方错误码和请求编号；不显示服务端原始消息、请求 URL、授权码或令牌。排查时只提供错误框和操作步骤，无需发送凭据。公开授权入口在主机与 Android 模拟器成功，不等于真实回跳、换码或账户读取已经通过。

Android alpha.8 的 S256 challenge 保留 Base64 末尾 `=`，与已核对版本的两端官方 SDK 一致。verifier 仍为 32 字节强随机值的 URL-safe 编码。先前使用无补位 challenge；本次协议兼容性调整不等于已经证实真机 400 根因，也未改变暂停发布的 iOS 安装包。

## 安全设计决策

沿用官方原生协议，自行实现小型适配层，避免直接继承 SDK 的令牌持久化和 PKCE 生成方式。固定研究版本为 Android `7a1f62c1d657372d52603eccf7a219aa9a947069`、iOS `66cdb2679e9cbacd7f682e43e784cf481f200a5a`。

- 每次授权生成 32 字节安全随机 verifier 与独立 state，以 S256 绑定授权码；会话 10 分钟超时、回调单次消费、取消与来源移除使旧请求失效。Android 官方原生回调允许只有 code/error extras，因此 state 返回时严格比较，未返回时仍必须由当前 PKCE verifier 成功换码。iOS 要求回传 state 匹配。
- Android 跳转仅指定官方包；iOS 只接受官方 AppLink 域名/路径或官方 scheme。公开回调的到达本身不代表授权成功。
- Android 使用现有 Keystore AES-GCM 凭据库；iOS 使用仅本机 Keychain。元数据与历史不保存令牌或签名下载 URL，只保存账户、盘、文件标识。移除账户清除本机令牌与来源；如需撤销平台授权，在阿里云盘官方授权管理中操作。
- Bearer 仅发送给固定 API 源，API 不自动重定向。CDN 播放请求不含 Bearer，验证 Range 与内容长度，链接 403 时只重新解析并重试一次。分页响应、条目数与内存读取有上限，取消会停止对应传输。
- refresh_token 可缺省；存在时尝试不携带 Secret 的刷新，失败或过期则重新授权。公开客户端的刷新能力仍需平台联调，不以 SDK 可选字段当作已获保证。

风险涉及账户访问和凭据生命周期，须独立审查；不会为此增加写文件、分享等权限。回退可移除本机云盘来源或重新安装上一版本；Android 安装序号不回退，不覆盖已发布标签。

## 阿里云盘：个人开发者配置

从[官方开发者门户](https://www.alipan.com/developer/f)登录。按平台显示的条件完成实人及其他必要认证；当前官方页面注明，一个开发者账号只能添加一个应用，可修改、不可删除。可创建应用并不等于所有接口能力已获批或已通过运行验证。

新建应用填写：

| 字段 | 映汐放映室配置 |
|---|---|
| 应用名称 | 映汐放映室 |
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

此校验不联系平台，不检查审核、回跳或网盘读取。真实授权与视频 Range 播放仍以真机联调证据为准。

品牌资源来源见 `third_party/cloud-brand-assets.json`；Android 包可见性只查询两个官方包，不使用全量应用查询权限。
