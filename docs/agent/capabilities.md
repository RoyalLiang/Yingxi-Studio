# 当前能力总表

基线为 [Beta.2](current.md)。下表描述源码已实现的行为；对应测试是验证入口，存在测试文件不代表本轮已执行，更不代表真实设备或真实服务通过。

| 能力 | 当前行为与边界 | 继续读取 |
| --- | --- | --- |
| 本机文件 | 系统文件/目录授权、目录浏览与随机定位；移除目录授权不删除媒体 | [文件导航](../file-navigation.md)、`LocalFilesViewModel` |
| SMB/SFTP | 扫描候选、认证、浏览、根目录约束和随机读取；SSH 候选不等于 SFTP 可用，SFTP 保留主机身份核验 | [来源设置](../network-settings.md)、`AppConnectors`、`SmbConnector`、`SftpConnector` |
| WebDAV/OpenList | 使用服务端给定地址与账户，校验偏移、长度及重定向边界；性能取决于实际服务 | [来源设置](../network-settings.md)、`WebDavConnector` |
| 官方云盘 | 阿里云官方授权、账户与目录、原画/可用平台清晰度；百度入口不等于已实现官方账户和播放接入 | [云盘接入](../cloud-integrations.md) |
| 片匣总录 | 索引选定本地、SMB、SFTP 目录，跨来源按片名和信源查找；保留原片架入口 | `FilmLibraryScreen`、`FilmLibraryStore`、`CatalogIndex` |
| 放映单 | 将总录影片编排为有序列表并依次播放；不可读条目跳过并提示检查 | `LibraryPlaylistTest`、`FilmLibraryRegressionTest` |
| 影片档案 | 修改展示片名、保存札记与片刻标记；重新关联文件沿用影片记录；同尺寸不证明是同一版本 | `FilmLibraryStore`、`FilmMomentPlaybackTest`、`LibraryViewModel` |
| 行囊备份 | 导出/预览并按明确范围恢复总录、放映单、进度、札记和支持的设置；恢复前校验，失败保留原记录 | `LibraryParcelTest`、`MediaDataMigrationTest` |
| 备份隐私 | 明文备份不携带账户凭据、文件路径、目录授权与封存内容；未匹配位置需要手动关联 | Beta.1 信笺、`LibraryParcelTest` |
| 检修台 | 按真实读取检查可用性，保留确认结果，未知仍为未知；可回到来源或重新关联 | `FilmLibraryUiTest`、`FilmLibraryScreen` |
| 播放会话 | MediaSession 管理播放、续播、同目录队列、后台/通知与画中画；不再引入第二套会话 | [播放操作](../player-controls.md)、`PlaybackService` |
| 起播与方向 | 先用可取得的媒体信息判断方向，后续由流内信息校正；打开和缓冲期间保留暂停、返回、旋转操作 | `FirstOrientationEntryTest`、`PlaybackExtractorStartupTest` |
| 控件与速度 | 横竖屏控件、手势、轨道与画面模式；播放和保存均支持 `0.25–4×` | `PlaybackPreferences`、`CloudPlaybackSettingsTest` |
| 解码与色彩 | 硬件优先，并按实际格式、失败和兼容策略接管；封装可读、成功解码、正确色彩与流畅度分别判断 | [格式边界](../playback-format-support.md)、`CompatibilityPlayer`、`MpvPlayer` |
| 海报、字幕与音轨 | 同目录优先，匹配文件主名/版本/季集；在线搜寻和选择导入分开；后台自动结果不得覆盖手选 | [媒体补充资料](../media-enrichment.md) |
| 封存内容 | 普通总录、历史及公开检索排除封存记录与已确认本机别名；锁定时保持隔离，互联网搜寻关闭 | `SealedMediaAliasTest`、`SealedMediaRegressionTest` |
| 播放问题上报 | 应用内冻结快照、列出公开字段，主动同意后上报；受限 WebView 执行校验，真实仓库回执才算完成 | [官网回执实现](../../website/README.md#播放问题回执)、`PlaybackReportSubmission` |
| 应用更新 | 检查更新、下载与恢复、摘要/包名/版本/签名校验后进入系统安装流程 | [发布规则](../releasing.md)、`UpdateDownloadService` |
| 放映站档案 | 手动阅读两章、回看与分章位置、地图及三类离线练习；日常描写不自动开放新章节 | [世界书](../worldbook.md)、`StationWorld`、`StationChapters` |

## 叙事名称对应真实功能

片匣总录是媒体索引，放映单是播放列表，行囊是资料备份，检修台是读取与关联检查。词汇全表由[随包数据生成的名词表](../world-glossary.md)维护。不得从叙事推断联网来源、奖励、自动解锁或额外播放能力。

## 不扩大现有结论

不把发现候选写成认证成功，不把阿里云接入写成百度接入，不把本机软件解码写成全部 HDR/杜比文件色彩正确，不把迁移保留写成所有历史 APK 都已真机升级。探索页已收起的独立词典入口不因整理文档而恢复，生态画廊中的创作稿也不会自动变为正史。
