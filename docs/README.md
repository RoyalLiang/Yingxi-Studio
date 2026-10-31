# 文档入口

这是面向 agent 的渐进阅读目录。先确定任务，再读取对应一行；不要一次加载整个 `docs/` 或全部版本信笺。用户安装与产品介绍从[项目首页](../README.md)开始。

## L0：每次只读入口

1. 遵循仓库实际存在的 `AGENTS.md`、`.agents/boot.yaml` 和路由规则；这些本地指导不随公开源码分发。
2. 读[开始工作](agent/start.md)，确认本次目标、基线和证据边界。
3. 查询现状时读[当前版本](agent/current.md)，随后只加载下表所需专题。

## L1：按任务选一份摘要

| 任务 | 首读 | 需要细节时再读 |
| --- | --- | --- |
| 了解最新功能、判断是否已实现 | [能力总表](agent/capabilities.md) | 对应功能专题、源码与测试 |
| 定位模块、修改调用链或持久化 | [架构与代码地图](agent/architecture.md) | 表中列出的拥有者与调用方 |
| 构建、回归、排查发布失败 | [验证与交付](agent/validation.md) | [发布规则](releasing.md)、实际工作流 |
| 整理文档、处理来源冲突 | [维护文档](agent/documents.md) | [机器可读目录](catalog.json) |
| 查旧版修复、验收范围与版本演变 | [历史证据](history/README.md) | 精确版本的报告、信笺和 Git 提交 |
| 修改故事、章节、住民短笺或词条 | [世界书](worldbook.md)的相关小节 | 图谱源数据、[词典](world-glossary.md)、[统计](world-statistics.md) |
| 修改官网 | [官网说明](../website/README.md) | 同步脚本、网页检查与部署工作流 |
| 查看 iOS 留存实现 | [iOS 状态](ios-alpha.md) | `ios/Sources/`、`ios/Tests/`；发布仍暂停 |

## L2：专题与权威来源

| 专题 | 文档 | 用途 |
| --- | --- | --- |
| 播放交互 | [播放操作](player-controls.md) | 控件、方向、队列、缓冲和检修；含版本沿革 |
| 文件与授权 | [文件导航](file-navigation.md) | 系统授权、面包屑和根目录边界 |
| 来源接入 | [网络与设置](network-settings.md)、[云盘接入](cloud-integrations.md) | 候选发现、认证、WebDAV、官方云盘与配置 |
| 海报与轨道 | [媒体补充资料](media-enrichment.md) | 同目录匹配、网络搜寻与封存约束 |
| 解码能力 | [播放格式](playback-format-support.md) | 分开描述容器、解码、色彩与实际验收 |
| native 与 IO | [播放 IO 决策](adr-playback-io-20261027.md) | 串行队列、输出生命周期与轨道更新 |
| 世界设定 | [世界书](worldbook.md) | 作者采用、站内证据、未知与提案 |
| 派生文档 | [词典](world-glossary.md)、[统计](world-statistics.md) | 脚本生成，不能手工修改 |
| 版本与许可 | [发布](releasing.md)、[贡献](../CONTRIBUTING.md)、[许可](../LICENSE) | 签名、源码、资产与工程边界 |

`catalog.json` 为每份公开 Markdown 文档登记层级、性质和读取条件；版本信笺按目录规则登记。本地私稿、运行记录与凭据不会因本目录出现而获得发布授权。
