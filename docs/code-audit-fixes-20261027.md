# 2026-10-27 代码审查修复与验证

本轮针对[原审查报告](code-audit-20261027.md)的8项发现修改实现，并验证原触发条件。基线为本地提交 `e586d9f2a85d220f1535172572236bcc87dd11b3`，应用版本仍为 `0.2.0-alpha.63 / 65`，本轮没有发布新版本。

## 8项发现的修复

| 原发现 | 根本修复 | 主要证据 |
| --- | --- | --- |
| 1. 主线程同步 JNI | MPV 命令、属性和 Surface 生命周期统一串行 worker；TextureView 延后释放，等待真实输出再打开视频；JNI 层检查非主线程 | 慢 getter/command 注入下，控制和退出180 ms内返回；native 阻塞时 Texture 尚未释放，解绑后释放；DV5 实际像素及会话切换 |
| 2. 自动外挂重建队列 | 自动同目录轨道在 Period 创建前发现；后到字幕仅重选文本流；远程 URI 归队列所有 | 持续播放连续更新字幕，主片打开次数不增加、零 BUFFERING、零视频 decoder 释放；内嵌/外挂往返、删除、seek、快速更新和切片 |
| 3. WebDAV 混读版本 | 首次响应保存 ETag/Last-Modified；后续 Range 使用 If-Match 或 If-Unmodified-Since，并核对响应版本和总长；412明确报变更 | 同长度A/B版本测试先红后绿；Last-Modified降级与已有 Range/重定向/取消回归 |
| 4. 清缓存后旧下载复写 | 缓存 clear 增加 generation，每个分段捕获创建时代次，过时 put 拒绝发布 | 阻塞下载后清空，再完成旧下载不产生缓存；清空后的新读取仍可缓存；原字节/损坏校验回归 |
| 5. 缩略图取消阻塞队列 | 提取使用可取消任务；主动关闭租约/描述符；Provider 打开移出唯一解码通道，Provider 客户端显式传取消信号；超时和 generation 阻止旧发布 | 取消阻塞读取后下一任务500 ms内执行；真实 Provider 取消、下一任务及信号确认本轮测得15 ms；排队取消、本机实际画面、SFTP独立连接 |
| 6. 海报重复 JPEG 解码 | PosterStore 共用8 MiB Bitmap LRU，按实际唯一图像文件路径缓存，替换/删除/清空失效 | 跨 Store 实例返回同一 Bitmap；替换后返回新图，旧图不可读；持久化刷新/失败/取消回归 |
| 7. 启动目录重复解析 | 历史一次批量合并，只解析、排序、保存一次；保留每条历史时间、已有 firstSeen 和容量限制 | 512项已有目录＋100条历史，本轮一次恢复测得7 ms；只增加一次 revision；名称更新时间和去重不变 |
| 8. iOS 每分段重建 Session | 同一次 RangeWork 复用一个 URLSession 和串行 delegate queue，仅结束/取消时失效；调试断言检查 Session 身份 | Core＋RemoteResourceLoader 类型检查；现有17个核心测试方法用断言运行器执行；完整应用运行验证仍有环境缺口 |

播放与附件的设计取舍见[ADR](adr-playback-io-20261027.md)。联动回归还修正了坏可选音轨先触发软件后端、旧视图读取新模式、音轨手动更换后位置回零等问题。

## 实际执行的检查

- 核心 JVM：140项，135通过，5项 SMB 集成测试因没有本轮SMB夹具跳过。新增传输回归已先在原实现失败，再在修复后通过。
- 工具脚本：`python3 -m unittest discover -s scripts -p 'test_*.py'`，74项通过。
- Android：68项最终仪器回归全部通过，逐项状态code0，无跳过；debug应用及androidTest APK构建和lint通过。专用只读模拟器 `emulator-6018`，Android 36 / arm64；未操作原有模拟器。
- SFTP：独立回环测试服务，真实认证、独立缩略图连接、自动字幕音轨、拖动倍速和队列移除后的租约回收。
- iOS：完整Xcode/XCTest不可用。CLT存在重复SwiftBridging模块，用临时VFS overlay与MacOS 15.5 SDK完成类型检查，未改动主机工具链；断言运行器执行原有17个核心测试方法，不能等同于XCTest或iOS应用验收。
- 独立代码复核：修复了最后发现的内嵌字幕选择和Provider取消入口问题后，复核通过；复核代理未冒充设备测试执行者。

68项逐项状态、测量值、APK SHA和提交信息见本任务本地证据索引 `.agent-runtime/audit-fix-20261027/delivery.json`。测试输出保存在同目录，失败迭代的输出与最终通过输出分开保留。

## 仍需遵守的验证边界

- 没有OnePlus真机、原始4K片源trace、现场NAS或完整iOS应用构建/运行，不能宣称这些环境已通过，也不能把源码风险写成已复现ANR。
- WebDAV没有任何版本校验器时保留兼容读取，无法承诺跨请求强一致；只有Last-Modified时精度由服务器决定。
- 用户主动增加/移除未知音轨会重建当前媒体Period，可能回缓冲；自动发现和后到字幕已经从该路径分离。
- 未执行生产部署、签名发布或GitHub Actions。Git远端写入状态以交付索引为准。
