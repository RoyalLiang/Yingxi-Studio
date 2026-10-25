# Android 播放格式与落地方案

核对日期：2026-10-25。对应版本：**0.2.0-alpha.55 / 57**，基于已发布的 [alpha.54](https://github.com/RoyalLiang/Yingxi-Studio/tree/b13f572524bef3b525715ae98ba61af0355f895a)。下表区分已经接通、依赖设备的能力，以及尚未实施的方案。正式 Release 的状态以发布记录为准。

目前最实用的组合是 MP4 / MKV 内的 H.264、HEVC、AV1、VP8/VP9，以及设备可解的音轨。普通影片先走 Android / Media3；HEVC 有明确的失败后软件回退。未加密的 Dolby Vision P5 在设备无法解码时，改走保留 RPU 的软件解码和 SDR 色彩映射。**这不是通用 MPV 模式，也不代表任意 4K P5 都已在真机流畅播放。**

## 先区分四层支持

| 层次 | 例子 | 本文怎样判断 |
| --- | --- | --- |
| 文件入口 | `.mkv`、`.wmv` 出现在片架里 | 扩展名可见不证明能播放。 |
| 封装 / 提取器 | MP4、Matroska、AVI、ASF | 必须能读出编码、轨道、时间戳和索引。 |
| 编码与输出 | HEVC Main10、AAC、Dolby Vision P5 | 解码器、profile、位深、分辨率、输出链都要匹配。 |
| 实测 | 原片、手机、帧率、拖动、长时间运行 | 库中存在或能力表声明不能代替出画、声音与同步证据。 |

下文的“已接通”只表示应用有可达路径；“系统条件”表示取决于设备解码器与输出能力；“未接通”包括库已包含但应用没有启用的能力。没有逐项实测的格式，不标为全设备验收通过。

## 当前应用怎样选择播放链

- [播放服务](../app/src/main/java/io/localplayer/app/PlaybackService.kt)使用 Media3 `1.11.1`、`DefaultMediaSourceFactory` 和[项目提取器](../app/src/main/java/io/localplayer/app/PlaybackExtractors.kt)，系统视频解码优先硬件。
- [HEVC 回退](../app/src/main/java/io/localplayer/app/HdrPlayback.kt)手工加入 NextLib `1.11.1-0.16.0` 的视频 renderer，但只放行未加密 HEVC，并在真实失败后的 `forceSoftware` 状态启用。H.264、VP9、AV1 等即使包含在 NextLib 中，当前也没有这条应用软件回退。
- alpha.55 的 `HdrRenderersFactory` 已在原生音频 renderer 后接入现有 NextLib `FfmpegAudioRenderer`，复用同一音频输出与播放时钟。随包 FFmpeg 编入 AC-3、E-AC-3、DTS、TrueHD、ALAC 等解码器；其中 DTS 双声道外挂已经实际解码、跳转与倍速验证，其余格式和多声道输出仍需对应原片验证。编译清单见[固定 NextLib 配方](https://github.com/anilbeesetti/nextlib/blob/v1.11.1-0.16.0/ffmpeg/setup.sh#L19)。
- [P5 路由](../app/src/main/java/io/localplayer/app/CompatibilityPlayer.kt)只在未加密 P5 不受支持或发生解码错误时进入 [MPV 后端](../app/src/main/java/io/localplayer/app/MpvPlayer.kt)。网络、权限、认证错误不能触发换解码器。MPV 使用 `hwdec=no`、`gpu-next`、BT.709 / sRGB SDR 目标；原片与解码分辨率保留。
- MPV 的具体来源与四 ABI 摘要固定在[运行时清单](../third_party/mpv-runtime.json)；NextLib 固定版本与摘要在[解码器清单](../third_party/decoder-sources.json)。当前最低系统为 Android 15 / API 35，目标 API 36。

## 封装与入口

| 封装 / 文件 | alpha.55 状态 | 缺口与最小落地方案 |
| --- | --- | --- |
| MP4 / M4V / 常见 MOV / 3GP，含 fMP4 | 已接通；内部编码仍须匹配 | 保留现有提取器；对 QuickTime 特有 sample entry 分别验收，不能将所有 MOV 等同 MP4。 |
| MKV / WebM | 已接通 | 保留索引与多轨；覆盖无 Cues、Cues 在文件尾、远程 seek 和不同编码组合。 |
| TS / M2TS、MPG / MPEG（PS） | 已接通提取器；具体载荷有条件 | 用真实 TS 时间戳、节目选择和随机跳转样本验证；无索引不能承诺与 MP4 相同的 seek 速度。 |
| AVI | **有提取器，有限制** | 固定 Media3 版本确有 AVI 路径，但 FourCC / 音频标记仅支持部分映射；按具体编码验证，不写“所有 AVI”。 |
| FLV | 已接通提取器；有跳转限制 | 若片源缺少索引，优先由片源侧无转码重新封装为 MP4 / MKV；保留原片。 |
| WMV / ASF | `.wmv` 在片架可见，**ASF 播放链未接通** | P2：补容器识别和明确错误；有需求时复用已隔离的 MPV 做受控兼容路由，并验证音轨、队列、历史和 seek。只改扩展名无效。 |
| RM / RMVB、OGV / Theora、MXF | 未接通通用入口与播放链 | P2：按确有片源的格式逐个补入口、提取和解码；先无转码重封装能保留的组合，不能重封装的再评估同一 MPV 后端。 |
| 裸 `.h264` / `.hevc` / `.m2v` | 未做通用文件播放入口 | P2：需要明确帧率/时间戳与索引方案；先支持合适封装，避免猜测时间轴。 |
| ISO、BDMV / MPLS、DVD 菜单 | 未实现 | P3：先实现选定主影片 / 播放列表及分段连续性；镜像挂载、菜单、多角度属于额外产品能力。 |
| HLS | 已引入模块，网盘自适应源有可达路径 | 不等于可以在片架打开任意 `.m3u8`；独立 URL 入口另行落地。 |
| DASH / SmoothStreaming / RTSP | 当前应用未接通相应模块和入口 | P2：仅在需要对应服务时加入官方模块和受控 URL 入口，补认证、重定向和直播恢复测试。 |

依据：[Media3 1.11.1 提取器](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/DefaultExtractorsFactory.java)、[AVI 编码标记映射](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/avi/StreamFormatChunk.java)、[Matroska 提取器](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java)。应用入口筛选见 [Entry.isVideo](../core/src/main/kotlin/io/localplayer/core/Remote.kt)；本机 SAF 还会接受文档提供者声明的 `video/*`。

## 视频编码与 HDR

| 编码 / profile | alpha.54 可达路径与边界 | 缺口的落地方案 |
| --- | --- | --- |
| H.264 / AVC，常见 8-bit 4:2:0 Baseline / Main / High | 系统条件支持，硬件优先 | P1：补 High10 / 4:2:2 / 4:4:4 样本探测；失败时可复用已有 FFmpeg 视频 renderer，但须显式放行该 MIME 并验证色彩、输出和恢复，当前未放行。 |
| HEVC / H.265 Main、Main10 | 系统优先；明确失败后可用现有 HEVC 软件回退 | P0：验证高码率、B 帧、10-bit 和热稳态。CPU 能解码不代表达到 4K 实时帧率；软件输出也不能直接等同原生 HDR。 |
| VP8 / VP9，含 VP9 Profile 2 | 系统条件支持 | P1：无合适系统解码器时，可复用随包 NextLib 的 `vp8` / `vp9`，新增 MIME 路由及 profile、HDR / 位深回归；当前未接通软件回退。 |
| AV1，常见 Main 8/10-bit | 系统条件支持；API 35/36 的系统能力仍须按实际 profile、尺寸和帧率核实 | P1：现有 NextLib 已编入 dav1d，但当前未放行软件视频路由；确有设备缺口时接通并测 10-bit、film grain、seek、峰值内存及长播。 |
| MPEG-2 Video / MPEG-1 Video | 有容器读取路径时依赖系统；未接通软件回退 | P1：NextLib 已编入 `mpeg2video` / `mpegvideo`，可复用同一 renderer 做受控回退；必须验证逐行/隔行、场序与音画同步。 |
| MPEG-4 Part 2（SP、DivX / Xvid 常见 ASP）、H.263 | 部分封装 / 系统条件可达；不是所有 profile 通用支持 | P2：先核对提取器映射，再为实际缺失的 decoder 重建现有依赖或使用受控 MPV；不能仅把四字符标记改成 H.264。 |
| VC-1 / WMV9、MJPEG | 部分容器能暴露编码，不代表设备或应用具备可用 decoder；WMV 的 ASF 又是独立缺口 | P2：分别补提取、解码路由与 seek 样本；先按真实片源决定，避免为少量编码增加另一套播放器。 |
| ProRes / DNxHD、DNxHR / FFV1 / Theora | 未接通通用播放；不在当前 NextLib 编译白名单内 | P2/P3：专业母版有明确需求时，再检查固定 MPV 运行时实际 decoder 清单并逐格式接通；验证高位深、4:2:2/4:4:4、色彩与内存，不按 FFmpeg 总能力表直接宣称支持。 |
| VVC / H.266、AVS2 / AVS3 等较新格式 | 未实现应用专用兼容路径，未验证 | P3：先取得合法测试样本并查询目标机实际 decoder；系统没有能力时需专用解码器构建、MIME/容器适配及独立性能验收，当前不承诺支持。 |
| HDR10、HLG | 原生系统解码 + SurfaceView 路径有条件支持 | P1：分别核实屏幕 HDR 类型、codec profile、输出 dataspace 和可见亮度；无 HDR 屏时需明确 SDR 映射方案，不用改色彩标签伪装。 |
| HDR10+ | 容器/系统可能提取并处理；未完成逐设备动态元数据验收 | P1：用带逐场景元数据的同一原片验证实际显示链；保留静态 HDR10 与动态 HDR10+ 的证据区别。 |
| Dolby Vision P5 | 已接通原生优先与正确解释 P5 的软件 SDR 兼容链；**不是 HDR/DV 透传** | P0：继续度量 4K Level 6、1× 长播掉帧和热状态；保留 RPU、原分辨率及 BT.709/sRGB 目标。将来 HDR 输出需要原生 EGL / dataspace 等输出适配与实屏验收。 |
| Dolby Vision P7 / P8 | 原生 DV 依赖设备；只有代码可读出兼容 Main10 SPS，且色彩满足约束时才尝试 HDR10 底层 | P1：分别验证具体 profile/兼容层；不是所有 P8 都有 HDR10 底层，也未实现双层 FEL 完整重建。 |
| Dolby Vision P4 / P9 等其他 profile、P7 FEL 完整增强层 | 没有应用专用兼容实现 / 验收 | P2：逐 profile 分析码流与输出需求，保留原生能力；不能套用 P5 SDR 或统一降格为 HEVC。 |

系统条件的依据见 [Android 媒体格式](https://developer.android.com/media/platform/supported-formats)与 [HDR 输出要求](https://developer.android.com/media/grow/hdr-playback)。项目 P7/P8 边界由 [dolbyVisionBaseFormat](../app/src/main/java/io/localplayer/app/PlaybackExtractors.kt)明确限制；P5 的 `needsDv5Sdr` 不接受加密内容。

## 音频编码与音效

以下指影片内的音轨。alpha.54 片架没有通用音乐库入口；“可读取音频封装”不等于已做音频文件产品。

| 音频编码 / 能力 | alpha.55 状态 | 缺口的最小方案 |
| --- | --- | --- |
| AAC-LC / HE-AAC / xHE-AAC、MP3 | 系统音频路径已接通，具体 profile、声道与输出看设备 | P1：覆盖低码率、5.1、倍速和路由切换；不必为设备正常支持的格式改播放器。 |
| FLAC / PCM、Vorbis / Opus、AMR | 有合适提取器时系统路径可达；高位深、多声道和采样率不能据名称保证 | P1：测真实音轨、重采样、声道顺序及耳机/蓝牙切换；只有实证缺口才加回退。 |
| ALAC | 容器可提取；系统优先，现已接入 NextLib 软件音频回退，ALAC 原片尚未验证 | P1：用 ALAC 原片验证已接通的 renderer、PCM 输出和音画时钟。 |
| AC-3 / E-AC-3 | 可提取；系统优先，已接入 NextLib PCM 软件回退；这不等于 Atmos 或位流直通 | P1：补 AC-3/E-AC-3 原片、5.1 下混与路由切换；不支持的设备使用现有软件 renderer。 |
| DTS / DTS-HD、MLP / TrueHD | 同上；普通影片现已接通 `dca` / `mlp` / `truehd` 软件路径；DTS 双声道实际播放通过，DTS-HD/TrueHD 原片待验证 | P1：补 DTS-HD/TrueHD 原片，核对 core/扩展层、声道布局和 PCM 格式；不把“有声音”当无损多声道直通。 |
| WMA / WMA Pro / WMA Lossless、APE / WavPack | 普通影片未接通这些格式的完整提取、解码及入口；ASF 与独立 APE/WV 也没有开放对应导入 | P2：先按实际样本补容器提取与可达入口，再复用现有 FFmpeg/兼容后端输出 PCM；逐一验证 seek、位深、声道和许可，不只按随包库名称宣布支持。 |
| AC-4、MPEG-H、IAMF、DSD 等 | 未做专用应用解码/输出适配；个别平台能力不等于通用支持 | P2/P3：按实际片源选择官方 Media3 扩展或系统路径；新增依赖之前先核对已有运行时与许可、内存和输出能力。 |
| Dolby Atmos（E-AC-3 JOC / TrueHD 承载）、DTS:X | **不能按音频 decoder 名称认定对象音效保留。** 当前无端到端对象渲染或外接接收器直通验收 | P2：按当前输出 route 查询编码/声道/direct-playback 支持，补系统空间音效或直通策略和外接设备验证；普通 PCM 解码/下混只能按实际结果标示。 |
| 独立配音音轨文件 | alpha.55 已接通本机导入、同目录发现和与主片合并；原生与 P5 共用主片时钟 | 已经使用 Media3 `MergingMediaSource` / MPV `audio-add`；同名音轨列入选项，在无内嵌音轨时由播放器自动选择。不同剪辑、起点或帧率需要显式偏移/校准，文件名不能推断对白同步。 |

[Android AudioFormat](https://developer.android.com/reference/android/media/AudioFormat)区分普通编码与 JOC、TrueHD、DTS 等输出编码；[AudioTrack direct-playback 查询](https://developer.android.com/reference/android/media/AudioTrack#getDirectPlaybackSupport(android.media.AudioFormat,%20android.media.AudioAttributes))针对当前设备与输出属性，不能用随包解码器清单替代。MPV P5 后端会解该 P5 文件内部的音轨，但当前仅凭该路径不能承诺所有普通影片都得到同等音频能力。

## 字幕：内嵌、外挂和搜寻分别验收

| 类型 | alpha.55 状态 | 缺口与落地方案 |
| --- | --- | --- |
| SRT / WebVTT | 手动外挂已接通；同目录/在线匹配可载入 | 已提前发现并使用主片时钟；长字幕跨 seek 已回归，铺满时使用独立安全字幕区域。 |
| ASS / SSA | 同目录/在线路径已接通；手动文件选择器只接受 SRT/VTT | 本轮补导入 MIME 与校验。Media3 SSA 支持不等于完整 libass 特效、字体附件或逐像素一致；复杂样本单独验收。 |
| TTML / SMPTE-TT | Media3 解析器已包含，可达性取决于容器；当前手动/目录外挂入口未接通 | P1：扩展已存在的 MIME/文件验证，不新增解析库；补 namespace、定位、字体和 seek 回归。 |
| TX3G / MP4 WebVTT、CEA-608 / 708 | 合适容器内系统链可处理；未做独立文件入口 | P2：用实际内嵌字幕样本验证轨道选择与显示；不要按 `.srt` 文本方式导入。 |
| PGS / DVB / MKV 内 VobSub | 固定 Media3 解析器包含这些格式；须由容器给出正确数据和初始化信息，未做逐格式实测 | P1/P2：补图片字幕样本、调色板、尺寸、压缩、seek 和铺满安全区测试。 |
| 独立 PGS `.sup`、VobSub `.idx + .sub` | 手动/同目录外挂未实现 | P2：实现二进制流读取和 VobSub 双文件配对、初始化信息；不能套用 4MB 文本读取/UTF-8 规范化。 |
| SAMI、MicroDVD 等旧文本格式 | 未接通应用入口 | P3：有片源需求时扩展受控转换/解析，并保留原文件；不把不同 `.sub` 格式按后缀混为一种。 |

字幕解析能力按 [Media3 1.11.1 的实际 factory](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/DefaultSubtitleParserFactory.java)判断，不按旧版本支持表猜测。当前入口与限制见 [SidecarSubtitles](../app/src/main/java/io/localplayer/app/SidecarSubtitles.kt)、[OnlineSubtitles](../app/src/main/java/io/localplayer/app/MediaEnrichment.kt)与 [readCaption](../app/src/main/java/io/localplayer/app/PlaybackActivity.kt)。

### 互联网搜寻不等于目录匹配

- 基线“在线字幕”是射手影片指纹匹配，库里没有该影片的指纹时可能确实没有结果；应给出网页搜寻与手动导入的后续入口，而不是把未命中当服务异常。
- 2026-10-25 用合成全零指纹向当前 `https://www.shooter.cn/api/subapi.php` 发起一次 POST，实测为 HTTP 200，`application/octet-stream`，**1 字节 `0xFF`**。这支持保留基线的二进制无命中处理；ASCII `-1`/`-2` 可以做兼容测试，但未取得官方完整契约，不能把假定的 ASCII 负数当此次失败已证实根因。
- [ASSRT 的官方 API](https://assrt.net/api/doc)提供字幕搜索/详情，文档要求 Token；本环境访问 API 时 TLS 连接失败，未验证实时服务可用性。本次核查没有取得提供通用配音音轨搜索、版本匹配与下载的无密钥正式服务。不能将字幕 API 用作音轨 API。
- 本轮已经接通明确标示的互联网网页搜索，加本机/信源外挂文件导入。使用 **Microsoft Bing 网页搜索**（`https://www.bing.com/search`）；仅发送解析后的片名/年份和字幕或配音关键词，不包含 NAS 地址、认证信息或整条媒体 URI；封存柜内容继续受私密策略约束。
- 将来接入音轨服务前，先明确服务商、鉴权/配额、文件格式、版本/剪辑匹配字段与下载/跳转契约；没有这些条件时不自动下载或声称已自动对齐对白。

## P5 卡顿先做不改变解码结果的优化

固定 [FFmpeg HEVC 解码器](https://github.com/FFmpeg/FFmpeg/blob/094a2f8a2a5e7fa64736e067de224ce28fdf5979/libavcodec/hevc/hevcdec.c)解析 RPU 并携带到输出帧；[mpv 帧元数据](https://github.com/mpv-player/mpv/blob/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6/video/mp_image.c)与 [libplacebo 色彩映射](https://github.com/haasn/libplacebo/blob/3330a515d62139259c26239014f286e233bd3a5c/src/shaders/colorspace.c)承接正确的 P5 解释。把 P5 改名为 HEVC 或仅切到 `mediacodec-copy`，不能证明该元数据链仍完整；现有固定 Android 运行时也不能仅设 Vulkan 参数就启用 [构建时关闭的 Vulkan](https://github.com/mpv-android/mpv-android/blob/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/buildscripts/scripts/libplacebo.sh#L17)。

本轮优先减少应用层负担：只在轨道/格式实际变化时重建 `Tracks`，缓存静态参数，合并或减少同步 JNI 查询，避免150ms轮询反复做相同工作。前后使用同一片源、分辨率、色彩参数、倍速、缓存状态及表面大小，度量主线程耗时、出帧、解码掉帧、VO 掉帧和 A/V 差值。该方向来自基线代码的重复工作分析，改善幅度须由 A/B 结果确认。

不要把整套 [mpv `fast` profile](https://github.com/mpv-player/mpv/blob/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6/etc/builtin.conf#L42)称为无损提速：它会更换缩放方式并关闭 dithering / 峰值检测。也不默认使用跳过 deblock、跳帧、减分辨率或 `low-latency` profile 掩盖 4K 解码不足。[固定版本选项说明](https://github.com/mpv-player/mpv/blob/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6/DOCS/man/options.rst)是参数边界；线程数、direct rendering 或缓存的实验都应一次只改一个变量，读回生效值后再判断。

还应让检修信息在流畅度异常但未报错时也可复制，至少区分原片帧率、解码器、解码掉帧、VO 掉帧、音画差、缓存和读取等待。当前公开 P5 样片是 1080p / Level 5；原 OnePlus PLY110 的 3840×2076 `dvhe.05.06`、1× 持续播放、色彩和热稳态仍需同原片真机验证。

## 按用户收益安排落地与验收

| 优先级 | 工作 | 复用与依赖 | 完成信号 |
| --- | --- | --- | --- |
| P0，本轮 | P5 应用层开销、可复制检修、铺满字幕安全区、目录附件同步、搜寻状态 | 现有 MPV/Media3、已认证读取、现有控件；不新建平行播放器 | 同片 A/B；字幕不出屏；暂停、拖动、倍速、换集、后台/返回后附件与主片共用进度；私密内容不外传。 |
| P1，补齐样本 | 常见音频软件回退的格式与输出验证 | 当前 NextLib 音频 renderer；无需再引入一套 FFmpeg | 无原生 AC-3/DTS/TrueHD/ALAC decoder 的受控环境真实出声；声道、时钟、seek、耳机切换与 R8 验证。 |
| P1，按缺口 | AV1/VP9/H.264 高位深等软件回退、TTML / 图片字幕回归 | 先复用既有 renderer / parser，只开放经过样本验证的 MIME/profile | 软件不能抢占正常硬件/HDR；输出色彩和实际帧率通过。 |
| P2 | ASF/RMVB 等容器、独立 PGS/VobSub、音效直通 | 先补可达性和格式识别，再考虑扩展；同一兼容后端优先 | 真样本读取、声画、跳转、生命周期和格式专属输出完整通过。 |
| P3 | 专业母版、光盘菜单、较新专用编码 | 明确片源和目标设备后再选解码器/库 | 包体、许可与原生 ABI 可追溯；性能和对应功能验收后才改“未实现”。 |

本报告未改动播放器、未启用新格式、未把研究方案当成发版事实；本轮实施结果由对应提交及交付记录更新。没有对全部列表做逐设备播放，也没有用公开小样替代原片真机结论。


## alpha.55 已实施与验证边界

- P5 保留软件 HEVC、RPU、原分辨率与 BT.709/sRGB SDR 路径，缓存 JNI 方法和静态元数据，减少重复 pause 写入，并为认证源 FD 增加有界预读。铺满时由 MPV 在屏幕内裁切，避免超出屏幕的 Surface。
- 同一 1080p P5 样片的 100 次主线程轮询，基线一次测量约 28.74 ms，早期候选约 12.92 ms；最终候选两次联跑约 52.09 ms 和 24.49 ms，负载与调度会影响结果，不能据此宣称稳定帧率提升。官方 4K/59.94fps P5 样片验证了可见 Surface 尺寸与出画面，模拟器掉帧没有稳定改善。OnePlus PLY110 原始 4K `dvhe.05.06` 的持续帧率、温升与色彩仍待同片实测。
- 本机 AVC + 外挂 DTS 的实际软件音频渲染、同目录 SRT、seek、1.5×、暂停和移除保留进度已验证；SFTP P5 + 独立字幕/音轨及释放租约也已验证。损坏 MKA 或 VTT 不应阻断主片；外挂触发源错误时会移除外挂重试并保留位置，解析器忽略无效字幕时则继续播放。
- 射手无命中时可继续通过 Bing 按片名找字幕；配音入口是明确的 Bing 网页搜索，找到文件后通过「本机音轨」导入。没有接入音轨目录 API、自动下载或跨剪辑对白校准。
- 预览提前准备元数据并复用读取器，拖动时保留原来的控件显隐状态；平台不能解码 P5 时，未增加第二条软件缩略图解码链。


## alpha.56：异步采样与上报边界

本版沿用上表的解码格式和正确色彩 SDR 光路。OnePlus 原片日志记录到一次 `sdrPollMs=122.56` ms，源码也确认旧版在界面线程同步读取 MPV 属性；这能证明界面阻塞风险，不能单独解释全部 VO 掉帧。现在由已有的单工作线程读取属性，每次仅保留一个在途采样，再回界面线程应用快照。停止、释放、替换影片及拖动前的旧样本均会被丢弃。

- `sdrPollMs`：后台属性读取耗时；不能再当作界面线程占用时间。
- `sdrPollMainMs`：回到界面线程后解析、应用这份快照的耗时。两项均为最近一次采样，不代表帧耗时分位数或解码帧率。
- 同一真实 P5 夹具的受控慢读取对照显示：旧版会阻塞主线程，候选仍能响应；迟到 EOF 不得撤销拖动后的播放意图也有修复前失败、修复后通过的回归。公开 4K/59.94 短样片在模拟器通过出画面和视口检查，仍有掉帧，不能据此宣称 OnePlus 3840×2076、23.976fps、1× 原片已持续流畅。

「上报播放问题」保持预览和离线复制，只有知情同意与主动提交后才发送到公开仓库。网页及 Pages 接收服务已部署；生产提交还依赖仓库 Issues 写入凭据与 Turnstile 配置，未配置时关闭提交，不显示成功。配置、字段白名单及安全验证见 [网页维护说明](../website/README.md)。本轮没有自动上传用户提供的原始日志。
