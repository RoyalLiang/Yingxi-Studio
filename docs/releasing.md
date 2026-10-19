# 版本与发布

当前推荐使用 GitHub Actions 构建和 GitHub Releases 分发 alpha。它能把源码版本、检查结果、签名和产物校验关联起来；目前无需增加自建发布服务器。未来进入 Google Play 时，再增加独立的商店上传步骤。

## 版本配置

`version.json` 是版本来源：

```json
{"versionName":"0.2.0-alpha.1","versionCode":3}
```

名称遵循 SemVer；可配置 alpha、beta、rc、自定义预发布标识或无后缀的正式版本。没有将小版本或 alpha 序号固定在工作流里。安装序号单独配置，范围为 1–2100000000，每次发布均需递增；它不必编码在名称里。Android 的区别见[官方版本说明](https://developer.android.com/studio/publish/versioning)，格式依据 [SemVer](https://semver.org/)。

本地可通过 Gradle 属性 `yingxiVersionName`、`yingxiVersionCode` 覆盖验证包版本，包名由实际 APK metadata 决定。发布时覆盖不得绕过配置/标签/APK 一致性校验。

alpha 示例标签为 `v0.2.0-alpha.1`。带预发布部分的版本自动标记为 GitHub prerelease，并且不会设为 Latest；正式版本按同一配置流程处理。不要重用已发布标签、code 或替换已发布资产。

## 两条流水线

`Android CI` 在 main push、PR 或手动运行时执行源码准入、版本工具测试、合成环回 SMB/SFTP JVM 测试、Android 主包/设备测试包编译和 lint。它不等于执行设备 instrumentation。只保留 Debug APK 和 SHA256，7 天后过期；不上传本地日志、测试数据或签名文件。

`Signed release` 由已有版本 tag 触发，也可手动选择已有 tag。检查同一 tag 的源码与测试，确认提交属于 main 历史，随后在 `release` environment 中签名。校验包名、版本、非 debuggable、签名、mac 条目和资产哈希后，先上传到 draft，资产齐全后发布。发布前读取全部已发布元数据，拒绝 code 倒退或签名身份改变；失败保留 draft，已发布版本不覆盖。所有版本共用一个发布并发锁。

Actions 固定完整提交 SHA，token 按任务配置权限。普通 CI 不取得发布密钥；签名步骤只在已审阅的发布流程中执行。安全约束参考 [GitHub 官方建议](https://docs.github.com/en/actions/reference/security/secure-use)。

## 首次配置

在 GitHub 的 `release` environment 配置以下四个 Secrets：

| 名称 | 内容 |
| --- | --- |
| `YINGXI_KEYSTORE_BASE64` | 维护者长期保存的 keystore 的 Base64 |
| `YINGXI_KEYSTORE_PASSWORD` | keystore 密码 |
| `YINGXI_KEY_ALIAS` | 签名别名 |
| `YINGXI_KEY_PASSWORD` | 私钥密码 |

可配置 environment variable `YINGXI_SIGNER_SHA256` 固定预期证书指纹。keystore 在 runner 临时目录解码并清理，不进入 checkout、cache、Release 或上传 artifact。维护者应另行保存长期密钥备份；不要通过聊天、issue 或源码提交密钥。

当前未自动创建密钥、设置 Secrets 或执行真实 GitHub 发布。缺少签名配置时流水线明确失败，不用临时调试密钥代替。建议为 main 和版本 tag 设置保护，并在 `release` environment 中设置所需的审批人；这些仓库设置由维护者控制。

签名包不能覆盖使用其他证书的旧调试安装。首次从调试版转签名版时，卸载会清除本机来源和记录；以后保持签名身份及递增 code 才能继续覆盖安装。

## 发布资产

仅发布版本化签名 APK、`SHA256SUMS`、`yingxi-release.json`、第三方声明和依赖清单。元数据记录源码 SHA、versionName/code、APK SHA256 和证书指纹，不包含账户、服务器地址或测试运行数据。

先更新版本并审阅代码，经授权提交/推送后，再创建并推送匹配的 tag。具体 Release 行为见 [GitHub 官方发布说明](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)。
