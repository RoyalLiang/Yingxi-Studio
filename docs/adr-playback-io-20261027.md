# ADR：隔离 native 播放调用与外挂轨道更新

状态：已实现，设备回归与独立代码复核见[修复记录](code-audit-fixes-20261027.md)。

## 需要解决的问题

MPV 的同步命令、属性和输出绑定会等待 native 核心。原代码在主 Looper 调用这些接口；字幕和音轨的自动发现还会重建整个原生队列。只把 JNI 调用放到后台不能满足 Surface 的销毁契约，只允许 MediaItem 原地更新也不能让 Media3 发现新 TrackGroup。

## 播放控制使用同一条串行队列

MPV 的初始化、命令、属性读取、输出绑定、解绑和销毁统一进入现有 worker。UI 立即更新播放意图，采样继续用 generation/revision 丢弃过时结果。`MpvRuntime.call` 检查线程，防止以后重新引入主线程 JNI。

每个 Engine 单独记录存活状态和已绑定 Surface；销毁与解绑在同一队列完成。停止时，独立关闭输入描述符和传输，避免读取阻塞拖住退出。开始打开视频前等待真实输出，防止无窗口初始化使 MPV 关闭视频轨。

## 兼容后端接管 TextureView 的销毁

兼容后端使用 TextureView；原生 Media3 仍使用原来的 SurfaceView 输出。`onSurfaceTextureDestroyed` 在 native 尚未解绑时返回 false，保留 SurfaceTexture，完成串行解绑后再释放。每次绑定的 listener 携带 View 身份，旧 View 的回调不能影响新 View。

MediaSession 只能传递 Surface，不能转交 TextureView 的销毁所有权。因此本应用在同一进程通过 `CompatibilityVideoOutput` 绑定兼容输出，控制、队列、通知和播放状态继续走 MediaSession。该桥接只引用当前 PlaybackService 的播放器，并在释放时注销。

## 文本轨道固定，字幕数据可更新

媒体源首次准备时建立文本 TrackGroup。每条外挂字幕预留 MERGE/REPLACE 两种 cue 格式，按官方 SubtitleParser 的语义选择；界面仅展示已附加的轨道。配置最多容纳32条受支持外挂字幕，超限不通过原地更新检查。

更新字幕时先禁用文本，等实际 `onTracksChanged` 确认旧流已退出，再更新源并重新选择文本流。这样也能替换已经到达 EOS 的字幕流。主片音视频 Period 和缓冲继续保留。字幕异步解析限制为8 MiB，结果按源 epoch 投回播放 Looper，跨 seek 保留覆盖目标时刻的 cue。

手动选择内嵌字幕优先于外挂默认值；删除外挂字幕时清理对应覆盖选择。排队期间发生切片或队列变更，会核对 mediaId、URI、play-intent 和位置，防止把旧字幕写到新片。

## 自动音轨在媒体源准备前发现

同目录字幕和音轨的发现移到首次 Period 创建前，包含预备的下一集；发现有8秒截止时间，失败后继续主片。发现结果随 MediaItem 标记，用户手动删除音轨后不会立即被再次自动加入。

远程外挂 URI 由播放队列持有，以 mediaId/play-intent 标识所有者，跨原生与 MPV 切换保留，移除队列项或释放会话后回收。用户主动改变未知音轨仍需重建当前 Period，但保留其他队列项、位置、倍速和播放意图；不承诺该操作零回缓冲。

## 缩略图把 Provider 打开放在解码队列外

缓存查找和 native 解码仍串行执行，但本机文档 Provider 的打开单独在 IO 任务中完成。content URI 直接通过 ContentProviderClient.openAssetFile 传递 CancellationSignal，绕开 ContentResolver 的 typed */* 回退丢失信号的问题；仍保留 AssetFileDescriptor 的偏移语义，客户端随描述符一同关闭。取消会撤销任务、发送取消信号并关闭传输；过时结果不发布缓存。整体提取截止时间为20秒。

这样即使 Provider 迟迟不返回，也不会占住唯一解码通道。平台不能强制终止不合作的 Provider；它返回后仍会关闭迟到的描述符。

## 不采用的方案

- 不异步投递 SurfaceView 的销毁回调：这不能延长 Surface 的有效期。
- 不只修改 MergingMediaSource.canUpdateMediaItem：MediaPeriod 不会因此发现新的 TrackGroup。
- 不为了自动附件把所有视频切到软件解码，也不重建整条原生队列。

API 依据：[TextureView 销毁所有权](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener#onSurfaceTextureDestroyed(android.graphics.SurfaceTexture))、[Media3 1.11.1 MediaPeriod](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/MediaPeriod.java)、[TextRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/text/TextRenderer.java)。
