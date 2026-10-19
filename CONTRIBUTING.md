# 工程与数据规则

- 每次完成改动并验证后，commit 并 push 本次任务涉及的文件；保留其他任务或用户尚未完成的工作。用户已授权常规提交、推送和新版本自动发布，无需每轮重新请求相同授权。
- 发布新版本时，同步更新 `version.json` 并提交 `releases/v<versionName>.md`，中英双语列出更新内容、验证范围和限制。推送到 `main` 后自动检查、签名并发布；具体步骤与签名配置见[版本与发布](docs/releasing.md)。

- 本项目为 Android 原生播放器，维持本机/SMB/SFTP 的只读访问、取消、随机定位与 SFTP 身份核验边界。
- 禁止生成、提交或打包 mac 专有元数据：`.DS_Store`、AppleDouble、`__MACOSX`、资源分叉和扩展属性。忽略规则不能代替最终检查。
- 本地环境、账户、口令、主机指纹、媒体样片、取屏、完整测试日志、工具链映射、签名材料及 agent 运行记录不得进入 Git 或 Release 资产。
- 允许提交可复现的测试代码和合成 fixture 生成器；测试凭据由运行器临时生成或从项目外配置传入，只连接显式启动的环回服务。不得用真实 NAS 账户跑 fixture。
- `scripts/repository_guard.py --git` 检查暂存内容，而非仅依赖 `.gitignore`。检测不能替代对首次公开文件清单的审阅。
- 版本由 `version.json` 配置。保持 SemVer 格式、正整数安装序号、发布标签与 APK 一致；每次对外发布的 code 必须高于所有已发布版本。
- 构建时可用 `-PyingxiVersionName=... -PyingxiVersionCode=...` 覆盖本地验证包版本；Release 必须与仓库配置一致。
- CI 检查与签名发布分离；禁止将签名密码放入 Gradle 参数、命令行记录或源码。固定 Action 提交，由 Dependabot 提议更新。

```sh
python3 scripts/repository_guard.py --git
python3 scripts/versioning.py
python3 -m unittest discover -s scripts -p 'test_*.py'
```

构建、协议与设备测试应注明实际执行范围；未运行的 GitHub workflow、目标手机或现场 NAS 不能记为通过。
