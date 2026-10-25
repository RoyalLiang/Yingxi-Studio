# alpha.57 解码接入与播放流畅度验证

用户目标是基于 `969c640` 的格式报告补齐接入，并优先改善一加原始 4K Dolby Vision P5。用户已说明目前不便提供原片或连接真机，因此本记录将代码与合成/公开样片证据和原片验收分开。

## 已复现的接入问题

1. `DecoderRoutingRegressionTest` 在基线中验证 AVC 软件回退返回 `FORMAT_UNSUPPORTED_TYPE`；候选版复用已有 NextLib renderer，系统选择优先、实际失败才切换。AVC/HEVC/VP8/VP9/MPEG-2 共用同一恢复逻辑与输出重绑，不新增解码库。
2. `MpvInputPlaybackLeaseTest` 在基线中出现对 `127.0.0.1:9` 的真实连接失败：MPV 的预览租约丢掉注册时的合成连接器。候选版复用 `freshTransport()`，同时继承正片预读和自适应状态。专项测试对实际 Android FUSE descriptor 做字节与大偏移读取，并验证关闭后原租约仍可读；不把这个合成 transport 证据写成现场 NAS 验收。
3. AV1 10-bit 在 NextLib、MPV 随包 dav1d 中触发原生 `SIGILL`；反汇编指向 SVE `ptrue`。ARM64 模拟器的 CPU 特征同时出现 SVE2 且未列 SVE。8-bit 在 Android 15 的单样片可成功，但本轮无法据此确立所有 AV1 的安全性，所以保持系统 AV1，关闭新软件路线，并限制兼容后端的 decoder 清单。

## 常见音轨的现有回退验证

`SoftwareAudioPlaybackTest` 在平台音频 decoder 不可用的选择条件下，验证 AC-3、E-AC-3、DTS、TrueHD 的 5.1 合成音轨与 24-bit 双声道 ALAC：实际 FFmpeg decoder、PCM buffer 推进、AudioTrack 输出活跃、seek 和 1.5× 均通过。没有测物理扬声器响度、蓝牙/耳机路由、Atmos 或无损位流直通。

复制报告的音频 decoder 名称改用与视频 decoder 相同的受控标识校验，保留实际引擎名；视频编码串仍使用独立的 profile 校验，URI 与路径不会因此放行。

## P5 优化与约束

- 属性查询从主线程移到现有串行 worker；每个实例只有一个在途快照，停止、换片、释放和 seek 后丢弃旧结果。外挂文件的打开使用独立队列，避免慢附件阻塞采样。
- P5 开启 `vd-queue-enable=yes`、`vd-queue-max-samples=2`、`vd-queue-max-bytes=64MiB`，让 CPU 解码和 GPU 映色之间有有界交接。字节阈值按 mpv 契约可能多出正在生成的一帧，不将 64 MiB 描述成进程内存硬上限。
- 保留 `hwdec=no`、HEVC RPU、原尺寸、`gpu-next`、BT.709/sRGB/100 nit 输出。没有用硬解丢弃 RPU，也没有套用会改缩放、色调映射与渲染质量的 fast profile。
- 参数依据为[固定 mpv 提交的线程队列与解码选项](https://github.com/mpv-player/mpv/blob/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6/DOCS/man/options.rst#L5016)。[固定 NextLib 的 MIME 映射](https://github.com/anilbeesetti/nextlib/blob/v1.11.1-0.16.0/media3ext/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/FfmpegLibrary.java#L111)用于接入范围；库名与能力表不能代替实播。

合并基线为 `51c99c0`，保留已发布 alpha.56 的后台采样、反馈与设置改动；本轮版本递增到 alpha.57/code59，不改写其 Release、标签与信笺。下述早期对照记录取自合并前的同逻辑候选，合并后的构建与专项会再次核对。

## 样片和可重放检查

软件视频使用 FFmpeg 合成的 320×180、25 fps、6 秒 testsrc2，H.263 使用 352×288；AVC High10、VP9 10-bit 明确指定 `yuv420p10le`。基线与候选使用同一组样片。`SoftwareVideoPlaybackTest` 注入平台 decoder 初始化失败，要求 advancing timestamps、非空纹理像素、seek 后继续出帧和 1.5×；`CompatibilityFormatsTest` 覆盖已开放的容器与编解码组合、传输与 DRM 边界。

P5 使用此前已取得的 Dolby 公开片段：`iOS_P5_GlassBlowing2_3840x2160@59.94fps_15200kbps.mp4` 的 4.004 秒、240 帧有界片段。来源见 [Dolby 样片](https://media.developer.dolby.com/DolbyVision_Atmos/mp4/iOS_P5_GlassBlowing2_3840x2160%4059.94fps_15200kbps.mp4)。本轮没有更改片段字节或降低分辨率。`P5DecodeBudgetTest` 将视频输出设为 null 测解码预算，另用 `Mpv4kViewportBenchmark` 验证可见 Surface 与播放时间；解码预算本身不证明色彩或屏幕出帧。

```sh
python3 -B scripts/worldbook.py
python3 -B scripts/versioning.py
python3 -B scripts/release_notes.py
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

合并远端已发布的 alpha.56 后，最终 alpha.57 重新完成四 ABI Debug/Release 构建和 lint。核心测试 130 项：125 通过、5 项环境跳过；Python 工具 72 项、官网回执 8 项通过。

Android 15 的视频、五类音频、旧封装、路由、诊断和世界书共 34 项通过；Android 16 的正片 FUSE 租约、两项慢 JNI/过期快照、4K P5 可见窗口和 renderer 共 12 项通过；P5 本地字幕/旋转/重播与真实回环 SFTP 生命周期另有 3 项通过。旧 P7 测试初跑缺少指定文件，补入合成 Main10 + P7 配置夹具后，4 项配置/提取器检查通过，2 项因模拟器未声明 HEVC Main10 能力跳过；该夹具没有真实 P7 增强层或 RPU，不作为增强层播放证据。以上合并后共 53 项 Android 检查通过、2 项能力跳过。

最终本地 R8 候选已通过设置/信笺入口、真实 SFTP P5 的画面变化、播放进度和 seek；使用调试证书用于本地验证，正式签名以 CI 为准。

公开 4K 片段的同一可见 2400×1080 Surface、1×、3 秒对照：基线位置约 2999 ms，候选约 3025 ms；两者 VO 掉帧和解码掉帧均为 0，A/V 差约 0.003 ms。此样片没有复现原片卡顿，因此不能计算原片帧率提升。运行时已读回候选的队列启用、两帧限制、`hwdec=no`、3840×2160 解码尺寸和 BT.709/sRGB 目标。

100 次紧密调用的主线程轮询入口：基线约 7.33 ms，候选约 0.115 ms。候选会合并在途查询，故这是 UI 调度开销对照，不是同量 JNI 工作或视频吞吐提升；后台查询耗时单独保留。基线去掉 GPU 的 4 秒片段解码预算在自动/4/8 线程下约为 1001/1046/961 ms，差值不足以据此给一加硬设线程数，生产保留自动线程。

保留已发布 alpha.56 的采样字段：`sdrPollMs` 是后台查询耗时，`sdrPollMainMs` 是主线程应用快照耗时。报告门户同时验证音频 decoder 名称能安全保留；原有同意、挑战和隐私字段清洗规则不变。

## 当前验证边界

不将原片 1× 持续流畅、热稳态、现场无线网络、真机色彩或所有格式 profile 计入通过。Android 15/16 ARM64 测试与四 ABI 构建也不能互相替代。正式签名与发布由既有 GitHub 流程完成，本地优化包仅用于 R8 验证。


本地文件系统的普通扩展属性清理未能持久移除 `com.apple.provenance`，停止继续尝试并保留逐文件内容摘要与属性备份。源码与 APK 字节内未见 `.DS_Store`、AppleDouble 或 `__MACOSX`；不把此结果表述为本地文件系统已无扩展属性。正式交付采用 Linux CI 的源码归档和 APK，并另查归档条目。
