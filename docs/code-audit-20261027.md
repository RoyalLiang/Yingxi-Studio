# 映汐底层综合代码审查 · 2026-10-27

这是一款面向单人手机使用的 Android 本地、局域网及云盘播放器，兼有 iOS 支线。此次按「一个播放会话、长片与高码率影片、连续浏览封面、拖动进度和切换前后台」审查关键链路。

优先处理主线程同步 libmpv 调用、自动外挂轨道导致的播放重建，以及缩略图取消。发现 **8 项：2 项已复现的可靠性问题、1 项静态明确的播放重建问题、5 项静态性能或资源风险**。没有发现足以判定为 P0 的证据；不能据此断言全库没有其他问题。

## 结论绑定远端 main，而不是旧工作区

- 主审源码：远端 `main` = `2b6d5b8c2e71951a799b2eb205877cda2df1a348`，`version.json` 为 `0.2.0-alpha.63` / 65。
- `v0.2.0-alpha.63` 标签 = `018559ad9b4ce04d47d5be6e484f8bdb60b3a426`。此次通过 `git ls-remote` 核实两条引用；标签和 main 的差异不当作未知发布缺陷。
- 原始工作区停在 `3d4d2d809d0e90e64cd7678f572d098a8e66c5f3` / alpha.32，含用户未提交的世界观与界面改动。没有切换、覆盖或暂存这些内容。
- 主审采用相同 Git 仓库中干净的 alpha.63 源码；报告及附带世界观增量放在独立工作树。未修复播放器产品逻辑、未安装应用、未更换签名、未部署服务。
- CodeGraph 因 `no such table: unresolved_refs` 无法使用，重试后改为直接源码及调用链核查。不是零结果。
- 隔离工作树没有 AppleDouble / DSStore 文件。宿主 `com.apple.provenance` 的逐项删除命令返回成功，但最终复查属性仍存在，原因未证实；已停止重复处理，没有更改系统权限。该属性不进入 Git blob，不能把 Git 内容干净写成本机扩展属性已全部消除。
- 本地原仓库有已存在的 AppleDouble pack 索引告警；已格式核验、备份并清理 258 个元数据文件，Git 伪索引告警消失。随后在 `dist/._yingxi-0.2.0-alpha.3-5-debug.apk.sha256` 遇到 EBUSY，按项目规则停止该目录清理，不重复尝试、不结束进程。隔离交付工作树另有 3 个随运行记录复制的 AppleDouble，已核验移除。原目录剩余元数据不是本轮产物；不计入下述产品问题。

## 1. P1 风险：兼容播放器仍在主线程执行同步 JNI 调用

**链路与位置：** `MpvPlayer.kt:27` 使用主 Looper；`:346–347` 直接调用 `command("seek", …)`，`:360–377` 在 Surface 创建、销毁、停止时同步设置 `vo`、窗口和输出。`MpvRuntime.kt:15–24` 直接反射进入 native 方法，没有异步边界。`:177` 的外挂更新也同步读取 `track-list`。

**触发与影响：** 兼容播放下拖动进度、旋转或退出，恰逢解码核心、GPU 输出重建或媒体读取繁忙。UI 线程会等待 native 返回，可能出现操作冻结或 ANR；此次没有把尚未测出的卡顿时长写成实测结果。轮询 getter 已移到 worker，不能覆盖这些控制路径。

**依据与反证：** 固定版本 [JNI command 实现](https://github.com/mpv-android/mpv-android/blob/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/app/src/main/jni/main.cpp#L94) 调用同步 `mpv_command`；[libmpv API 契约](https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h#L94) 明确同步调用等待播放核心就绪。销毁大部分已在 worker 执行，但在投递清理任务之前仍有同步 Surface 控制。

**最小处理与验证：** 将可异步的命令交给现有串行执行器或 libmpv 异步接口，并保持 Surface 销毁的正确时序，不能简单无序投递 attach/detach。用同一原始片源记录主线程栈、慢读取期间的拖动/旋转/退出延迟，再判断实际 ANR 风险。

**证据类型：** 同步主线程路径静态明确；设备上的延迟、ANR 发生率待测。不是已复现 ANR。

## 2. P2：自动字幕和外挂音轨仍会重建原生播放队列

**链路与位置：** `AutomaticSidecars.kt:63–65`、`MediaEnrichment.kt:296–304` 调用 `replaceMediaItem`；实际进入 `CompatibilityPlayer.kt:122–132`，原生分支仍执行 `native.setMediaItems(...)` 和 `prepare()`。

**触发与影响：** 已开始播放后自动发现字幕或音轨。播放位置虽然被保存，现有媒体源和缓冲仍被重建，增加远程重新打开、回缓冲及解码器重建机会。外挂先发现、在线字幕后匹配时，可先后发生两次。

**反证：** MPV 分支使用 `updateAttachments`，此次问题限于原生 Media3 分支。现有 `MediaEnrichmentTest.kt:215` 之后的测试验证队列、暂停位置和字幕显示，没有要求持续播放期间零 BUFFERING、零 source reopen，因而不足以排除此问题。

**最小处理与验证：** 优先让同目录发现结果在首轮 prepare 前进入条目，或只更新附件对应的媒体源；避免把整队列重新提交当作无损更新。对正在播放的远程片源统计附件添加前后 source open、BUFFERING 和 decoder release，同时保留位置与字幕断言。

**证据类型：** 静态明确的重建行为；黑屏和回缓冲的实际持续时间未测量。

## 3. P2：WebDAV 会把同长度的两个文件版本混在一次读取中

**位置：** `core/.../WebDav.kt:127–149,174–180`。首次与 seek 后响应只比较 `Content-Range` 总长度，没有保存、核对 `ETag` / `Last-Modified`，也未发 `If-Match` / `If-Range`。

**已复现：** 同一路径先返回 32 字节 A、ETag v1；seek 后返回同样总长度的 B、ETag v2。读取对象依次返回 A 和 B，且第二次请求不含版本条件头。真实媒体被覆盖或转码替换时，可能混入不兼容的索引/帧数据。

**最小处理与验证：** 在同一 RemoteFile 生命周期中固定可用的强版本标识，每次重新 Range 时核对；发现变化明确要求重新打开。保留无验证器服务器的兼容边界，不把「长度一致」称为「内容一致」。回归须覆盖同长度、不同 ETag 和不同长度替换。

**反证：** 大小变化已有防护；阿里云并行读取也有独立版本保护。此次结论针对 WebDAV，不能外推为全部传输协议都没有保护。

## 4. P2：清空云盘缓存后，在途分段仍会回写

**链路与位置：** `StorageUsage.kt:66` 调用 `SegmentCache.clear()`；`SegmentedRemoteFile.kt:133–151` 在读完分段后无条件 `cache.put`；`SegmentCache.kt:28–44` 没有清空代次或在途写入失效机制。

**已复现：** 将一个完整 1 MiB 分段停在 RemoteFile.close，执行 clear，缓存确实变为 0；放行旧任务后，目录重新出现 **1,048,608 字节**（内容加 32 字节校验）。没有发起后续播放或新读取。

**最小处理与验证：** clear 增加缓存代次；下载开始时捕获代次，完成后仅在代次一致时发布。复用当前缓存对象即可，无需停止播放或引入数据库。回归检查清空之前已开始的任务不能恢复缓存，清空之后新请求仍可正常缓存。

**反证：** 这不是不受限增长，既有容量和校验仍有效。持续播放中新请求建立缓存是合理行为；本条复现的是清理不能使先前已启动的写入失效。如果产品允许这种回写，应在回收提示中说明，不能把复现行为直接等同于隐私泄漏。

## 5. P2 风险：取消封面行不能立即取消其同步缩略图提取

**位置：** `MediaThumbnails.kt:42,63,82–91,103–128,145–155`。整个提取在单并行 dispatcher 上同步执行；协程取消没有及时关闭 cancellation 的回调，关闭主要在同步解码返回后的 finally，或 20 秒定时器触发。

**触发与影响：** 快速滚动离开正在从慢 NAS 取帧的条目，新可见条目的提取排在旧工作之后。任务已取消并不等于 socket/解码已停止。对本机 content URI，定时器也没有拥有 retriever 或文件描述符，无法据此承诺 20 秒能打断原生 setDataSource/getScaledFrame。

**最小处理与验证：** 对齐 `PreviewFrames` 已有的任务、资源取消模式，让取消先关闭在途读取，再释放 native decoder，并保留一个解码器的资源上限。加入阻塞读取夹具：取消 A 后，B 应及时开始，不能仅验证 A 最终未写缓存。

**证据类型：** 取消没有传递到同步工作的机制静态明确；具体阻塞时间和本机 provider 的行为待测。

## 6. P2 性能风险：一张缩略图完成会令其他可见海报重新读盘解码

**链路与位置：** `MediaThumbnails.kt:143,175–184` 向全局 revision 发通知，所有可见 `MediaThumbnail` 的 produceState 随之重启；它们首先调用 `MediaPosters.load(localOnly=true)`。后者在 `MediaEnrichment.kt:68–78`、`PosterStore.kt:16–19` 每次读取 JPEG 并 BitmapFactory 解码，没有对应的 Bitmap 内存复用。

**触发与影响：** 同屏既有持久海报又有尚在提取的影片。每完成一张缺失缩略图，已有海报再次分配 bitmap；刷新、滚动、切回页面叠加时产生重复 I/O、解码和 GC。后台 dispatcher 只能限制并发，不能消除重复工作。未测帧率，不给出掉帧百分比。

**最小处理与验证：** 让通知携带失效的媒体 key，或在图像缓存层复用相同版本的 Bitmap；保留编辑来源、删除历史及封存时的失效约束。用多条混合海报/缩略图夹具统计已命中海报的 decode 次数，并配合分配追踪。

## 7. P2 性能风险：冷启动在主线程逐条重建放映站目录

**链路与位置：** `LibraryViewModel.kt:103–107` 构造时对至多 100 条播放历史逐条 `inventory.observe(listOf(...))`。`StationExperience.kt:85–105` 每次 observe 又解析、建映射、排序至多 512 项；有变化时整份序列化并调用 SharedPreferences.apply。

**触发与影响：** 使用较久、历史与目录均接近上限时，冷启动在主线程重复解析相同目录。即使条目没有变化，重复解析仍发生；磁盘 apply 异步不代表之前的 JSON 和排序工作异步。

**最小处理与验证：** 用一个批次合并历史并保留各自 firstSeen，或在 worker 完成合并后一次发布。不能为了批量把历史时间统一覆盖。比较 100 条历史 / 512 项目录的冷启动 trace 和 JSON 解析次数；此次未量化启动耗时。

## 8. P2 性能风险：iOS 每个 1 MiB 分段重新创建并销毁 URLSession

**链路与位置：** `ios/Sources/Core/WebDAV.swift:163–165` 将每段限制为 1 MiB；`RemoteResourceLoader.swift:63–66` 每次 next 创建 ephemeral URLSession，`:85–93` 每块完成就 finishTasksAndInvalidate，再 next。

**触发与影响：** 长片、远端连接延迟较大或码率高时，不断重建 session 和 delegate queue，失去稳定复用同一 session 的机会，增加调度及连接管理成本。不能仅凭静态代码断言每块必然新建 TCP/TLS，也没有 iPhone 吞吐实测。

**最小处理与验证：** 让一次 RangeWork 复用一个 URLSession，仍使用顺序分段、现有 Range 校验和取消；结束或取消时统一 invalidate。验证长片连续播放、签名刷新和快速 seek 后的连接建立数及吞吐。

**范围：** iOS 支线；此处两份文件在旧工作区与 alpha.63 的 SHA-256 相同。没有把 Android 测试当作 iOS 通过。

## 验证、反证与覆盖

- 在隔离临时目录的 **alpha.63 core 源码副本**运行 7 个测试类共 **29 项检查，0 失败、0 跳过**：ReadAhead、WebDAV、SegmentedRemoteFile、SegmentCache、SFTP 延迟窗口、Aliyun 并行读取，加上 2 个缺陷行为复现。
- 两个复现用例是**断言缺陷行为确实发生**，因此运行成功表示复现成立，不能解读为这两个缺陷已修复。复现源码、XML 汇总与日志留在 `.agent-runtime/code-audit-20261027/`；没有向产品测试集加入「要求错误行为继续存在」的测试。
- 主审包括 Android 播放服务、原生/MPV 切换、Surface、远程输入、预览/缩略图/海报、SMB/SFTP/WebDAV/阿里云分段缓存、相关调用方及已有测试；iOS 只检查远程 loader、Range 状态与播放/持久化入口。
- 排除了 alpha.32 的「SFTP 仅单包串行」候选：alpha.63 已采用有界流水窗口，选定 SFTP 延迟测试通过。字幕候选继续追到包装层才确认，不能只看 replaceMediaItem 接口名。
- 未运行 Android instrumentation、iOS 实机、OnePlus 原始 4K P5 长时间播放、无线 NAS 压测、Perfetto/GPU/温升测量或完整依赖安全扫描。没有审计 native 视频库的全部 C/C++ 内部实现，也没有逐文件语义覆盖官网、发布脚本及全库全部模块。
- 所有耗时、吞吐与掉帧影响尚未测量；报告中的数量来自代码上限或注明条件的复现，不是现场性能承诺。

建议先对 1、2、5 复现并处理，再修复已复现的 3、4；6、7、8 以解码次数、启动 trace、连接数作为整改前后对照。此次交付为审查证据与最小修复方向，尚未修复上述产品问题。

代码定位约定：Android Kotlin 文件位于 `app/src/main/java/io/localplayer/app/`，`core/...` 位于 `core/src/main/kotlin/io/localplayer/core/`；所有行号绑定上面的主审 SHA。
