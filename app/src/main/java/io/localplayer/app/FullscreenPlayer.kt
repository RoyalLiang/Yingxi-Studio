package io.localplayer.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

private val PlayerInk = Color(0xFFF7F7F6)
private val PlayerAccent = Color(0xFFFF9AB6)
private val PlayerPanel = Color(0xF5202326)

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun FullscreenPlayer(controller: MediaController?, title: String, connectionError: String?, back: () -> Unit,
    rotate: () -> Unit, lock: (Boolean) -> Unit, externalCaption: () -> Unit, compact: Boolean,
    smallWindow: (() -> Unit)?, background: () -> Unit, videoView: (PlayerView) -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var visible by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var index by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf(title) }
    var currentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var playRequested by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var buffering by remember { mutableStateOf(false) }
    var seekable by remember { mutableStateOf(false) }
    var videoAvailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var buffered by remember { mutableLongStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var target by remember { mutableLongStateOf(0) }
    var gesture by remember { mutableIntStateOf(0) } // 1 seek, 2 brightness, 3 volume
    var hud by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var hudRevision by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameLoading by remember { mutableStateOf(false) }
    var frameAt by remember { mutableLongStateOf(0) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var fill by rememberSaveable { mutableStateOf(false) }
    val previews = remember(controller) { PreviewFrames(context.applicationContext) }
    val originalBrightness = remember { activity.window.attributes.screenBrightness }
    val touchExploration = remember { context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled }
    DisposableEffect(previews) { onDispose { previews.close(); activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness } } }
    DisposableEffect(lifecycle,previews) {
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_STOP){dragging=false;previews.releaseReader()}
        }
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    DisposableEffect(controller) {
        fun refresh() {
            controller ?: return
            index = controller.currentMediaItemIndex
            currentTitle = controller.mediaMetadata.title?.toString() ?: title
            currentUri = controller.currentMediaItem?.localConfiguration?.uri
            truncated = controller.mediaMetadata.extras?.getBoolean("queue-truncated") == true
            speed = controller.playbackParameters.speed
            playing = controller.isPlaying
            playRequested = controller.playWhenReady && controller.playbackState != Player.STATE_ENDED
            buffering = controller.playbackState == Player.STATE_BUFFERING
            seekable = controller.isCurrentMediaItemSeekable
            videoAvailable = controller.videoSize.width > 0
            videoWidth = controller.videoSize.width; videoHeight = controller.videoSize.height
            duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            error = controller.playerError?.let { "播放失败（${it.errorCodeName}），请返回重试" }
        }
        fun timeline() { items = controller?.let { c -> (0 until c.mediaItemCount).map(c::getMediaItemAt) }.orEmpty() }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refresh()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) timeline()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) { dragging = false; frame = null }
            }
        }
        refresh(); timeline(); controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller, visible, dragging, compact, lifecycle) {
        if(controller==null||compact||(!visible&&!dragging))return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) {
                position=controller.currentPosition.coerceAtLeast(0)
                buffered=controller.bufferedPosition.coerceAtLeast(0)
                delay(250)
            }
        }
    }
    LaunchedEffect(compact) { if (compact) { settings = false; queue = false; speedOpen = false; dragging = false; previews.releaseReader() } else visible = true }
    LaunchedEffect(visible, playing, dragging, locked, settings, queue, speedOpen, interaction) {
        if (visible && playing && !dragging && !locked && !settings && !queue && !speedOpen && !touchExploration) { delay(3000); visible = false }
    }
    LaunchedEffect(hudRevision) { if (hud != null) { delay(1400); hud = null } }
    LaunchedEffect(currentUri) { frame = null; frameAt = 0;previews.releaseReader() }
    LaunchedEffect(currentUri, videoAvailable, buffering, compact) {
        val uri=currentUri ?: return@LaunchedEffect
        if(!videoAvailable||buffering||compact)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(500)
            try { withTimeoutOrNull(1500) { previews.get(uri,position) } }
            catch(e:CancellationException){throw e}
            catch(_:Exception){ /* A missing preview must never interrupt playback. */ }
        }
    }
    LaunchedEffect(dragging, currentUri, buffering) {
        if (!dragging || currentUri == null || buffering) { frameLoading = false; return@LaunchedEffect }
        val uri = currentUri!!
        previews.latest(uri)?.let { frame=it.bitmap;frameAt=it.timeMs }
        snapshotFlow { target.coerceAtLeast(0) / 5000 * 5000 }
            .distinctUntilChanged().conflate().collect { requested ->
                val cached=previews.cached(uri,requested)
                if(cached!=null){frame=cached.bitmap;frameAt=cached.timeMs}
                frameLoading = cached==null
                try {
                    val next = withTimeoutOrNull(1500) { previews.get(uri, requested) }
                    if (next != null) { frame = next; frameAt = requested }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Retain a valid frame and its original timestamp. */ }
                finally { frameLoading = false }
            }
    }
    BackHandler(enabled = queue || speedOpen || settings) { queue = false; speedOpen = false; settings = false }
    Box(Modifier.fillMaxSize().background(Color(0xFF101113)).pointerInput(Unit) {
        awaitPointerEventScope { while(true) { val event=awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial);if(event.changes.any{it.pressed&&!it.previousPressed})interaction++ } }
    }) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; setKeepContentOnPlayerReset(true) }.also(videoView) },
            update = { it.player = controller; it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        if (!compact) {
            Box(Modifier.fillMaxSize().testTag("playback-gesture-surface")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { visible = !visible }
                .pointerInput(controller, locked, duration, seekable) {
                    var start = Offset.Zero
                    var total = Offset.Zero
                    var startPosition = 0L
                    var startLight = 0f
                    var startVolume = 0
                    var allowed = false
                    val threshold = with(density) { 14.dp.toPx() }
                    val edge = with(density) { 24.dp.toPx() }
                    detectDragGestures(onDragStart = { point ->
                        allowed = !locked && point.x > edge && point.x < size.width - edge
                        start = point; total = Offset.Zero; gesture = 0; startPosition = controller?.currentPosition ?: 0
                        startLight = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                            ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }, onDrag = { change, delta ->
                        if (allowed) {
                            total += delta
                            if (gesture == 0 && maxOf(abs(total.x), abs(total.y)) >= threshold) {
                                gesture = if (abs(total.x) > abs(total.y)) 1 else if (start.x < size.width / 2) 2 else 3
                                if (gesture == 1 && (!seekable || duration <= 0)) { allowed = false; gesture = 0 }
                            }
                            if (allowed && gesture != 0) {
                                change.consume()
                                when (gesture) {
                                    1 -> { dragging = true; target = (startPosition + total.x / size.width * 180_000).toLong().coerceIn(0, duration); visible = true }
                                    2 -> { val value = (startLight - total.y / size.height).coerceIn(.03f, 1f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }; hud = "亮度" to (value * 100).roundToInt(); hudRevision++ }
                                    3 -> { val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1); val value = (startVolume - total.y / size.height * maximum).roundToInt().coerceIn(0, maximum); audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); hud = "音量" to (value * 100 / maximum); hudRevision++ }
                                }
                            }
                        }
                    }, onDragEnd = { if (gesture == 1 && dragging) { controller?.seekTo(target); position = target }; dragging = false; gesture = 0 },
                        onDragCancel = {
                            if (gesture == 2) activity.window.attributes = activity.window.attributes.apply { screenBrightness = startLight }
                            if (gesture == 3) audio.setStreamVolume(AudioManager.STREAM_MUSIC, startVolume, 0)
                            dragging = false; gesture = 0; hud = null
                        })
                })
        }
        if ((buffering || controller == null) && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(84.dp), color = PlayerInk)
        if (!compact && visible && !locked && !queue) {
            if (!dragging) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xB8101113), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
                    Text(currentTitle, Modifier.weight(1f).padding(start = 8.dp), color = PlayerInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            videoDefinition(videoWidth,videoHeight)?.let { definition ->
                Text(definition,Modifier.padding(horizontal=8.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha=.13f)).semantics { contentDescription="视频清晰度 $definition，${videoWidth}×${videoHeight}" }
                    .padding(horizontal=9.dp,vertical=5.dp).testTag("video-definition"),color=PlayerInk,fontSize=11.sp)
            }
                    PlayerIcon(Icons.Rounded.Headphones, "后台播放", background)
                    if (smallWindow != null) PlayerIcon(Icons.Rounded.PictureInPictureAlt, "画中画", smallWindow, videoAvailable)
                    PlayerIcon(Icons.Rounded.LockOpen, "锁定方向与控件", { locked = true; lock(true) })
                }
                if (!speedOpen) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (items.size > 1) PlayerIcon(Icons.Rounded.SkipPrevious, "上一集", { controller?.seekToPreviousMediaItem() }, index > 0)
                    PlayerIcon(Icons.Rounded.Replay10, "后退10秒", { controller?.seekTo((position - 10_000).coerceAtLeast(0)) }, seekable)
                    IconButton(onClick = { if (playRequested) controller?.pause() else controller?.play() }, modifier = Modifier.size(72.dp).background(Color(0x8C101113), CircleShape)) {
                        Icon(if (playRequested) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playRequested) "暂停" else "播放", tint = PlayerInk, modifier = Modifier.size(38.dp))
                    }
                    PlayerIcon(Icons.Rounded.Forward10, "前进10秒", { controller?.seekTo((position + 10_000).coerceAtMost(duration)) }, seekable)
                    if (items.size > 1) PlayerIcon(Icons.Rounded.SkipNext, "下一集", { controller?.seekToNextMediaItem() }, index < items.lastIndex)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6101113))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = 24.dp, vertical = 8.dp)) {
                val shown = if (dragging) target else position
                Slider(value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { dragging = true; target = it.toLong() },
                    onValueChangeFinished = { controller?.seekTo(target); position = target; dragging = false }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = seekable && duration > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("playback-progress"),
                    thumb = { Box(Modifier.size(if (dragging) 13.dp else 9.dp).background(PlayerInk, CircleShape)) },
                    track = { state -> Canvas(Modifier.fillMaxWidth().height(if (dragging) 5.dp else 3.dp)) {
                        val max = duration.coerceAtLeast(1).toFloat()
                        drawLine(Color(0x55FFFFFF), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
                        drawLine(Color(0x88FFFFFF), Offset(0f, size.height / 2), Offset(size.width * (buffered / max).coerceIn(0f, 1f), size.height / 2), size.height)
                        drawLine(PlayerAccent, Offset(0f, size.height / 2), Offset(size.width * (state.value / max).coerceIn(0f, 1f), size.height / 2), size.height)
                    } })
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${playbackTime(shown)} / ${playbackTime(duration)}", Modifier.width(124.dp).heightIn(min=48.dp).wrapContentHeight(), color = PlayerInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Box {
                        TextButton(onClick = { speedOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("playback-speed")) { Text(speedLabel(speed), color = PlayerInk, fontSize = 14.sp) }
                        MaterialTheme(colorScheme = darkColorScheme(surface = PlayerPanel, onSurface = PlayerInk)) {
                            DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                                Column(Modifier.width(260.dp).padding(12.dp)) {
                                    Text("播放倍速", color = PlayerInk, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
                                    listOf(.75f, 1f, 1.25f, 1.5f, 2f, 4f).chunked(3).forEach { row -> Row {
                                        row.forEach { value -> TextButton(onClick = { controller?.setPlaybackSpeed(value); PlaybackPreferences(context).speed = value; speedOpen = false },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).background(if (speed == value) PlayerAccent else Color.Transparent, RoundedCornerShape(10.dp))) {
                                            Text(speedLabel(value), modifier = Modifier.testTag("speed-option-$value"), color = if (speed == value) Color(0xFF302029) else PlayerInk)
                                        } }
                                    } }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { settings = true }, modifier=Modifier.semantics{contentDescription="播放设置"}) { Icon(Icons.Rounded.Subtitles, null, tint = PlayerInk, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("音字幕", color = PlayerInk) }
                    PlayerIcon(if (fill) Icons.Rounded.FitScreen else Icons.Rounded.AspectRatio, "画面比例", { fill = !fill })
                    TextButton(onClick = { queue = true }, enabled = items.isNotEmpty()) { Icon(Icons.Rounded.PlaylistPlay, null, tint = PlayerInk); Text("选集 ${index + 1}/${items.size}", color = PlayerInk, fontSize = 13.sp) }
                    PlayerIcon(Icons.Rounded.ScreenRotation, "旋转屏幕", rotate)
                }
            }
        }
        if (!compact && dragging) Column(Modifier.align(Alignment.Center).width(208.dp).clip(RoundedCornerShape(16.dp)).background(PlayerPanel), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(117.dp).background(Color.Black),contentAlignment=Alignment.Center) {
                Crossfade(frame,animationSpec=tween(100),label="seek-preview-frame") { bitmap ->
                    if(bitmap!=null)Image(bitmap.asImageBitmap(),"进度预览",Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
                }
                if(frameLoading)CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(10.dp).size(15.dp),color=PlayerInk,strokeWidth=1.5.dp)
                if(frame==null&&!frameLoading)Text("暂无预览",color=Color(0xFFC8CDD1),fontSize=12.sp)
            }
            Text(time(target), color = PlayerInk, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
            Text((if (target >= position) "+" else "−") + time(abs(target - position)), color = PlayerAccent, fontSize = 12.sp)
            Text(if(frame!=null)"约 ${time(frameAt)}${if(frameLoading)" · 更新中"else""}"else if(frameLoading)"正在生成预览…"else"拖动以调整进度",
                color=Color(0xFFC8CDD1),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp,bottom=9.dp))
        }
        if (!compact && !dragging) hud?.let { (name, value) -> Surface(Modifier.align(Alignment.Center).width(180.dp), shape = RoundedCornerShape(16.dp), color = PlayerPanel) {
            Column(Modifier.padding(18.dp)) { Text("$name  $value%", color = PlayerInk, fontSize = 16.sp); Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { value / 100f }, color = PlayerInk, trackColor = Color(0x55FFFFFF), modifier = Modifier.fillMaxWidth()) }
        } }
        if (!compact && locked) IconButton(onClick = { locked = false; lock(false); visible = true }, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).background(PlayerPanel, CircleShape)) { Icon(Icons.Rounded.Lock, "解锁方向与控件", tint = PlayerInk) }
        if (queue && controller != null) PlaybackQueueSheet(items, index, truncated, { queue = false }) { chosen -> controller.seekTo(chosen, 0); controller.play(); queue = false; visible = true }
        if (settings && controller != null) PlaybackOptions(controller, { settings = false }, externalCaption)
        (error ?: connectionError)?.let { message -> Column(Modifier.align(Alignment.Center).background(PlayerPanel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(message, color = PlayerInk); TextButton(onClick = back) { Text("返回重试", color = PlayerAccent) } } }
    }
}

@Composable private fun PlayerIcon(icon: ImageVector, label: String, action: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label, tint = if (enabled) PlayerInk else Color(0xFF777C81), modifier = Modifier.size(25.dp)) }
}
internal fun playbackTime(ms: Long): String {
    val sec = ms.coerceAtLeast(0) / 1000
    return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
}
