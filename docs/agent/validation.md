# 验证与交付

检查范围跟随改动，不把历史通过结果当成本次通过。项目凭据、工具链路径、模拟器端口、媒体与完整日志保存在本地运行目录，公开文档只保留可复现入口。

## 先做确定性检查

在项目根目录执行：

```sh
python3 -B scripts/repository_guard.py --git
python3 -B scripts/versioning.py
python3 -B scripts/release_notes.py
python3 -B scripts/worldbook.py
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

`repository_guard.py --git` 读取暂存区；验证未暂存内容可运行不带 `--git` 的同一脚本。提交前仍需检查实际暂存 diff，不能用工作树检查代替。

## 按改动补充验证

| 改动 | 检查入口 | 不能据此宣称 |
| --- | --- | --- |
| 文档和目录 | 本地相对链接、目录覆盖、命令与文件存在性；源码边界检查 | 已构建/安装新版应用 |
| core 协议/缓存 | `./gradlew :core:test`；协议 fixture 按 CI 使用临时账户与环回服务 | 真实 NAS 或公网提供方性能通过 |
| Android 源码 | `./gradlew :app:assembleDebug :app:lintDebug`；按设备条件运行对应 instrumentation | 所有 ABI、参考真机及格式通过 |
| 片匣/迁移/备份 | `FilmLibraryRegressionTest`、`LibraryParcelTest`、`LibraryPlaylistTest`、`MediaDataMigrationTest`、`MigrationProcessRecoveryTest` | 只看 JSON 相等就证明升级无损 |
| 播放/方向/封存 | `FirstOrientationEntryTest`、`PlaybackExtractorStartupTest`、`SealedMediaAliasTest` 与对应播放回归 | READY、时钟推进或无报错等于真实出画 |
| 世界数据 | 生成词典/统计后运行 `worldbook.py` 与脚本测试 | 内容已包含在旧 APK 或显示于真机 |
| 官网 | 同步内容后运行 `sync-world.py --check`、`node website/tools/check.mjs --atlas app/src/main/res/raw/world_atlas.json`，以及 `npm --prefix website run test:reports` | 已部署、线上回执或反滥用校验已验收 |

具体任务命令与当前工具链从 [CI 工作流](../../.github/workflows/ci.yml)读取；不把本机路径写进共享命令。播放器交互改动还需窄屏、大字号、暂停、返回、旋转和实际连续画面检查。

## 修改世界数据的顺序

先确认脚本参数和 JSON 结构，再执行：

```sh
python3 -B scripts/worldbook.py --write-glossary --write-statistics
python3 -B scripts/worldbook.py
python3 -B website/tools/sync-letters.py
python3 -B website/tools/sync-world.py
```

词条、短笺、人物和版本足迹使用稳定标识；旧条目不重新编号。世界书记录采用状态、关联与未带入应用的部分。新增章节、重大制度与能力真相仍需单独授权。

## 合并、清理与发布

合并前保存基线与脏状态，逐项确认功能承接；历史被整理过时保存等价映射与差异说明。清理 worktree 前保留其中的私有文档、运行证据及恢复快照。只删除明确纳入本次范围且已经承接的分支，不使用强推或旧版标签覆盖。

文档提交可以沿用当前 `version.json`，不会发布新 APK。需要应用新版本时，先读[世界书](../worldbook.md)与[发布规则](../releasing.md)，递增名称和安装序号、写中英信笺，再按签名发布流程核验四 ABI、源码附件及不可变提交。用户发布信笺不包含验证、限制、CI 或 SHA 段落。

交付分别报告本地检查、模拟器/真机、CI、正式资产与线上服务结果。扫描工作区和打包产物中的 mac 元数据；先核验 AppleDouble magic，遇到忙占用或权限错误记录路径，停止重复清理。
