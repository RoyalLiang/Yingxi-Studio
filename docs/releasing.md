# 版本与发布

每次完成改动并通过对应检查后，commit 并 push 本次任务文件。新版本推送到 `main` 后，GitHub Actions 自动完成检查、签名、标签创建和 GitHub Release 发布。用户已授权这套常规流程；遇到检查失败、缺少签名材料或仓库保护限制时，报告阻塞。

## 提交一个新版本

1. 在 `version.json` 设置新的 `versionName` 和 `versionCode`。
2. 创建 `releases/v<versionName>.md`，参考已有版本说明。文件标题必须为 `# 映汐 <versionName> / Yingxi <versionName>`，随后依次包含 `## 中文` 和 `## English`。
3. 中文部分包含 `### 更新内容`、`### 验证与限制`；英文部分包含 `### Changes`、`### Validation and limitations`。每节用实际内容描述改动和验证边界，不能留空或使用待填写标记。
4. 验证并 commit、push 到 `main`。不要将其他任务未完成的改动混入提交。

名称遵循 [SemVer](https://semver.org/)，支持 alpha、beta、rc、自定义预发布标识和正式版本。安装序号为 1–2100000000 的整数，每次发布必须高于所有已发布版本；名称和安装序号彼此独立。参见 [Android 版本规则](https://developer.android.com/studio/publish/versioning)。

本地可用 Gradle 属性 `yingxiVersionName`、`yingxiVersionCode` 覆盖验证包版本。正式发布要求版本配置、标签和 APK 一致。普通改动未更新 `version.json` 时只触发 CI。

## 自动检查与发布

`CI` 在 main push、PR 或手动运行时执行源码准入、版本及双语说明校验、Python 工具测试、合成环回 SMB/SFTP JVM 测试、Android 主包和设备测试包编译以及 Lint。它不执行设备 instrumentation。Debug APK 和 SHA256 作为 Actions artifact 保留 7 天。

`Signed release` 在推送到 main 且改动包含 `version.json` 时启动，也保留版本标签触发和手动选择已有标签的入口。它固定本次提交 SHA，检查提交属于 main 历史、版本与双语说明一致，再对同一 SHA 调用 CI。

检查通过后，在 `release` environment 构建签名 APK，创建缺失的匹配版本标签，并验证 APK 身份、版本、非 debuggable、证书、mac 元数据和资产校验和。标签已存在时必须指向同一提交。自动标签由当前 workflow 直接继续发布，无需依赖标签触发第二条 workflow；这是因为 [GITHUB_TOKEN 创建的事件通常不触发其他 workflow](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。

发布脚本读取全部已发布版本的元数据，拒绝安装序号倒退和签名身份改变。它先创建 draft，完整上传资产，再发布。页面正文来自本版本双语说明文件，并追加版本、安装序号和源码 SHA；恢复同一提交的 draft 时也会同步正文。失败可在 Actions 中重跑原任务；已发布版本、标签和资产不覆盖。

所有版本共用一个发布并发锁；Actions 固定完整提交 SHA。普通 CI 不取得签名密钥，发布 job 使用最小写权限。源码检查、构建或发布失败均不能记为已发版。

## 首次配置长期签名

在 GitHub 的 `release` environment 配置四个 Secrets：

| 名称 | 内容 |
| --- | --- |
| `YINGXI_KEYSTORE_BASE64` | 长期保存的 keystore 的 Base64 |
| `YINGXI_KEYSTORE_PASSWORD` | keystore 密码 |
| `YINGXI_KEY_ALIAS` | 签名别名 |
| `YINGXI_KEY_PASSWORD` | 私钥密码 |

可配置 variable `YINGXI_SIGNER_SHA256` 固定预期证书指纹。keystore 仅在 runner 临时目录解码，结束后清理，不进入源码、cache、Release 或 artifact。维护者应保留长期密钥备份，不能通过聊天或源码提交秘密。

本流程不会生成新密钥、写入 Secrets 或更换已有签名身份。缺少签名配置会明确失败；不能用临时 debug 密钥替代。仓库规则或 environment 审批仍然生效；自动发布不绕过维护者设置的保护。配置方法见 [GitHub Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

签名版不能覆盖使用不同证书的旧 debug 安装。首次切换可能需要卸载，卸载会清除本地来源、文件授权和历史；后续保留签名身份并递增安装序号才能覆盖升级。

## 发布资产与验证边界

Release 仅上传版本化签名 APK、`SHA256SUMS`、`yingxi-release.json`、第三方声明和依赖清单。元数据记录源码 SHA、版本、APK SHA256 和证书指纹；本地账户、服务器地址、测试数据和运行日志不上传。

本地构建、CI 成功、GitHub Release 发布和实际手机运行是不同证据。双语说明应明确目标真机、现场 NAS、无线网络和高码率媒体是否实际验证。发布成功不能代替这些验收。

发布检查：

```sh
python3 scripts/repository_guard.py --git
python3 scripts/versioning.py
python3 scripts/release_notes.py
python3 -m unittest discover -s scripts -p 'test_*.py'
```

发布后的真实状态见 [GitHub Actions](https://github.com/RoyalLiang/Yingxi-Studio/actions) 和 [GitHub Releases](https://github.com/RoyalLiang/Yingxi-Studio/releases)。
