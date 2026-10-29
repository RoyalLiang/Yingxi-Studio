package io.localplayer.app

import io.localplayer.core.mediaDisplayTitle

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

internal val PlayerInk = Color(0xFFFAFAF8)
internal val PlayerAccent = Color(0xFFFFADBF)
internal val PlayerPanel = Color(0xFF25282B)

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun FullscreenPlayer(controller: MediaController?, title: String, connectionError: String?, back: () -> Unit,
    rotate: () -> Unit, lock: (Boolean) -> Unit, externalCaption: () -> Unit, compact: Boolean,
    smallWindow: (() -> Unit)?, background: () -> Unit, videoView: (PlayerView) -> Unit, externalAudio: () -> Unit = {}) {
    val context = LocalContext.current
    val momentScope = rememberCoroutineScope()
    val activity = context as Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var visible by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var qualityOpen by remember { mutableStateOf(false) }
    var trackType by remember { mutableIntStateOf(C.TRACK_TYPE_VIDEO) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var diagnosticsWait by remember { mutableStateOf<Long?>(null) }
    var queue by remember { mutableStateOf(false) }
    var pendingEpisode by remember(controller){mutableStateOf<Pair<String,Long>?>(null)}
    val renderedFrame by PlaybackFrames.last.collectAsState()
    LaunchedEffect(renderedFrame,pendingEpisode){
        pendingEpisode?.let{(key,after)->if(renderedFrame.key==key&&renderedFrame.sequence>after)pendingEpisode=null}
    }
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    val portrait=configuration.screenWidthDp<configuration.screenHeightDp
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(portrait) { moreOpen = false }
    var speedOpen by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var index by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf(mediaDisplayTitle(title)) }
    var currentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var previewScope by remember { mutableStateOf("") }
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
    var playerBounds by remember { mutableStateOf(Rect.Zero) }
    var thumbBounds by remember { mutableStateOf(Rect.Zero) }
    var controlsHeight by remember { mutableIntStateOf(0) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var bitrate by remember { mutableStateOf<String?>(null) }
    var softwareSdr by remember { mutableStateOf(false) }
    var fill by rememberSaveable(portrait) { mutableStateOf(!portrait) }
    val previews = remember(controller,previewScope) { PreviewFrames(context.applicationContext,previewScope,controller?.currentMediaItem?.mediaMetadata?.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}) }
    val originalBrightness = remember { activity.window.attributes.screenBrightness }
    val layoutDirection=androidx.compose.ui.platform.LocalLayoutDirection.current
    val safeInsets=WindowInsets.safeDrawing
    val safeLeft=if(compact)0 else safeInsets.getLeft(density,layoutDirection)
    val safeRight=if(compact)0 else safeInsets.getRight(density,layoutDirection)
    val safeBottom=if(compact)0 else safeInsets.getBottom(density)
    val subtitleGutter=with(density){16.dp.roundToPx()}
    val subtitleBottom=if(portrait&&(visible||dragging)&&!locked&&!compact&&!queue&&!settings)maxOf(safeBottom,controlsHeight)else safeBottom
    LaunchedEffect(controller,fill,softwareSdr,playerBounds,safeLeft,safeRight,subtitleBottom) {
        val height=playerBounds.height.coerceAtLeast(1f)
        val args=android.os.Bundle().apply {
            putBoolean("fill",fill)
            putInt("margin-x",maxOf(25,kotlin.math.ceil((maxOf(safeLeft,safeRight)+subtitleGutter)*720f/height).toInt()))
            putInt("margin-y",maxOf(22,kotlin.math.ceil((subtitleBottom+subtitleGutter)*720f/height).toInt()))
        }
        controller?.sendCustomCommand(CompatibilityPlayer.VIDEO_LAYOUT,args)
    }
    val touchExploration = remember { context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled }
    fun selectEpisode(chosen:Int){
        controller?:return
        if(chosen !in 0 until controller.mediaItemCount)return
        if(chosen!=controller.currentMediaItemIndex){
            val destination=controller.getMediaItemAt(chosen)
            pendingEpisode=PlaybackFrames.key(destination) to PlaybackFrames.last.value.sequence
            currentTitle=playbackItemTitle(destination,title)
            position=0;buffered=0;duration=0;dragging=false;frame=null;error=null
        }
        controller.seekTo(chosen,0)
        if(controller.playbackState==Player.STATE_IDLE)controller.prepare()
    }

    DisposableEffect(previews) { onDispose { previews.close() } }
    DisposableEffect(activity) { onDispose { activity.window.attributes=activity.window.attributes.apply{screenBrightness=originalBrightness} } }
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
            currentTitle = playbackItemTitle(controller.currentMediaItem,title)
            currentUri = controller.currentMediaItem?.localConfiguration?.uri
            previewScope=controller.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")?:controller.currentMediaItem?.mediaId.orEmpty()
            truncated = controller.mediaMetadata.extras?.getBoolean("queue-truncated") == true
            speed = controller.playbackParameters.speed
            playing = controller.isPlaying
            playRequested = controller.playWhenReady && controller.playbackState != Player.STATE_ENDED
            buffering = controller.playbackState == Player.STATE_BUFFERING
            seekable = controller.isCurrentMediaItemSeekable
            videoAvailable = controller.videoSize.width > 0
            videoWidth = controller.videoSize.width; videoHeight = controller.videoSize.height
            softwareSdr=controller.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")==true
            duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
                val selectedVideo=controller.currentTracks.groups.firstOrNull{it.type==C.TRACK_TYPE_VIDEO&&it.isSelected}
                val format=selectedVideo?.let{group->(0 until group.length).firstOrNull{group.isTrackSelected(it)}?.let(group::getTrackFormat)}
                bitrate=playbackBitrateLabel(format)
            error = controller.playerError?.let(::playbackFailure)
            if(error!=null)pendingEpisode=null
            if(error==null&&controller.playbackState==Player.STATE_READY){
                val video=controller.currentTracks.groups.filter{it.type==C.TRACK_TYPE_VIDEO}
                if(unsupportedVideo(controller.currentTracks)){
                    val format=video.first().getTrackFormat(0)
                    val codec=format.codecs?:format.sampleMimeType?:"未知编码"
                    error="此设备无法解码该视频（$codec），请更换兼容片源"
                }
            }
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
    LaunchedEffect(compact,previews) { if (compact) { settings = false; qualityOpen=false; queue = false; speedOpen = false; moreOpen = false; dragging = false; previews.releaseReader() } else visible = true }
    LaunchedEffect(visible, playing, dragging, locked, settings, queue, qualityOpen, speedOpen, moreOpen, interaction) {
        if (visible && !touchExploration && (locked || (playing && !dragging && !settings && !queue && !qualityOpen && !speedOpen && !moreOpen))) { delay(3000); visible = false }
    }
    LaunchedEffect(hudRevision) { if (hud != null) { delay(1400); hud = null } }
    LaunchedEffect(currentUri,previews) { frame = null;previews.releaseReader() }
    LaunchedEffect(currentUri,compact,buffering,previews) {
        val uri=currentUri
        if(uri!=null&&!compact&&!buffering){delay(300);previews.warm(uri)}
    }
    // Open the independent preview decoder only for an explicit seek gesture.
    LaunchedEffect(dragging, currentUri, buffering,previews) {
        if (!dragging || currentUri == null || buffering) return@LaunchedEffect
        val uri = currentUri!!
        frame=(previews.cached(uri,target)?:previews.nearest(uri,target))?.bitmap
        previews.frames(uri,snapshotFlow { target }.onEach{time->frame=(previews.cached(uri,time)?:previews.nearest(uri,time))?.bitmap}).collect { next ->
            if(next.timeMs==target.coerceAtLeast(0)/1000*1000)frame=next.bitmap
        }
    }
    BackHandler(enabled = queue || speedOpen || moreOpen || settings) { queue = false; speedOpen = false; moreOpen = false; settings = false }
    Box(Modifier.fillMaxSize().onGloballyPositioned { playerBounds=it.boundsInRoot() }.background(Color(0xFF101113)).pointerInput(Unit) {
        awaitPointerEventScope { while(true) { val event=awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial);if(event.changes.any{it.pressed&&!it.previousPressed})interaction++ } }
    }) {
        val compatibilityOutput=softwareSdr
        key(compatibilityOutput) { AndroidView(factory = { ctx -> (if(compatibilityOutput)android.view.LayoutInflater.from(ctx).inflate(R.layout.player_compatibility,null) as PlayerView else PlayerView(ctx)).apply { useController = false; setKeepContentOnPlayerReset(true);setEnableComposeSurfaceSyncWorkaround(true)
            subtitleView?.let { subtitles ->
                (subtitles.parent as? android.view.ViewGroup)?.removeView(subtitles)
                overlayFrameLayout?.addView(subtitles,android.widget.FrameLayout.LayoutParams(-1,-1))
            } }.also(videoView) },
            update = { if(compatibilityOutput){it.player=null;CompatibilityVideoOutput.attach(it.videoSurfaceView as android.view.TextureView)}else it.player=controller
            it.resizeMode = if(compatibilityOutput)AspectRatioFrameLayout.RESIZE_MODE_FILL else if(fill)AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.subtitleView?.setPadding(safeLeft+subtitleGutter,0,safeRight+subtitleGutter,subtitleBottom+subtitleGutter) },
            onRelease = { if(compatibilityOutput)CompatibilityVideoOutput.detach(it.videoSurfaceView as android.view.TextureView);it.player = null }, modifier = Modifier.fillMaxSize().testTag(if(fill) "video-scale-fill" else "video-scale-fit")) }
        if(pendingEpisode!=null)Box(Modifier.fillMaxSize().background(Color(0xFF101113)).testTag("episode-loading-cover"))
        if (!compact) {
            Box(Modifier.fillMaxSize().testTag("playback-gesture-surface")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { visible = !visible }
                .pointerInput(controller, locked, duration, seekable,portrait,items.size,touchExploration) {
                    var start = Offset.Zero
                    var total = Offset.Zero
                    var startPosition = 0L
                var startIndex = 0
                    var startLight = 0f
                    var startVolume = 0
                    var allowed = false
                    val threshold = with(density) { 14.dp.toPx() }
                    val edge = with(density) { 24.dp.toPx() }
                    detectDragGestures(onDragStart = { point ->
                        allowed = !locked && point.x > edge && point.x < size.width - edge
                        start = point; total = Offset.Zero; gesture = 0; startPosition = controller?.currentPosition ?: 0; startIndex=controller?.currentMediaItemIndex?:0
                        startLight = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                            ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }, onDrag = { change, delta ->
                        if (allowed) {
                            total += delta
                            if (gesture == 0 && maxOf(abs(total.x), abs(total.y)) >= threshold) {
                                gesture = if (abs(total.x) > abs(total.y)) 1 else if(portrait&&items.size>1&&!touchExploration&&start.x>size.width*.2f&&start.x<size.width*.8f)4 else if (start.x < size.width / 2) 2 else 3
                                if (gesture == 1 && (!seekable || duration <= 0)) { allowed = false; gesture = 0 }
                            }
                            if (allowed && gesture != 0) {
                                change.consume()
                                when (gesture) {
                                    1 -> { dragging = true; target = (startPosition + total.x / size.width * 180_000).toLong().coerceIn(0, duration) }
                                    2 -> { val value = (startLight - total.y / size.height).coerceIn(.03f, 1f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }; hud = "亮度" to (value * 100).roundToInt(); hudRevision++ }
                                    3 -> { val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1); val value = (startVolume - total.y / size.height * maximum).roundToInt().coerceIn(0, maximum); audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); hud = "音量" to (value * 100 / maximum); hudRevision++ }
                                }
                            }
                        }
                    }, onDragEnd = {
                    if(gesture==4&&abs(total.y)>maxOf(threshold*4,size.height*.12f))controller?.takeIf{it.currentMediaItemIndex==startIndex}?.let{player->
                        if(total.y<0&&startIndex<items.lastIndex)selectEpisode(startIndex+1)
                        else if(total.y>0&&startIndex>0)selectEpisode(startIndex-1)
                    }
                    if (gesture == 1 && dragging) { controller?.seekTo(target); position = target }; dragging = false; gesture = 0
                },
                        onDragCancel = {
                            if (gesture == 2) activity.window.attributes = activity.window.attributes.apply { screenBrightness = startLight }
                            if (gesture == 3) audio.setStreamVolume(AudioManager.STREAM_MUSIC, startVolume, 0)
                            dragging = false; gesture = 0; hud = null
                        })
                })
        }
        if(!compact&&(buffering||pendingEpisode!=null||controller==null)&&error==null&&connectionError==null&&!settings&&!queue&&!dragging)
            PlayerLoadingMotion(Modifier.align(Alignment.Center).size(88.dp).testTag("playback-buffering"))

        if (!compact && (visible || dragging) && !locked && !queue && !settings) {
            if (!dragging) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xB8101113), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
                    Text(currentTitle, Modifier.weight(1f).padding(start = 8.dp), color = PlayerInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box {
                TextButton(enabled=controller!=null,onClick={qualityOpen=true},modifier=Modifier.heightIn(min=48.dp).testTag("video-definition")
                    .semantics{contentDescription="画质，${bitrate?:videoDefinition(videoWidth,videoHeight)?:"原片"}"}){
                    Text(bitrate?:videoDefinition(videoWidth,videoHeight)?:"画质",color=PlayerInk,fontSize=12.sp,maxLines=1)
                }
                        if(controller!=null)PlaybackQualityMenu(controller,qualityOpen){qualityOpen=false}
                    }
PlayerIcon(Icons.Rounded.BookmarkAdd, if(androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language=="zh")"记住此刻"else"Remember this moment", {
                    val raw=controller?.currentMediaItem?.mediaMetadata?.extras?.getString("media-ref")
                    val position=controller?.currentPosition
                    if(raw!=null&&position!=null&&position>=0){
                        val ref=runCatching{MediaRef.parse(raw)}.getOrNull()
                        if(ref!=null)momentScope.launch{
                            val message=runCatching{withContext(Dispatchers.IO){FilmLibraryStore(context).use{it.addMoment(ref,position)}}}
                                .fold({if(context.resources.configuration.locales[0].language=="zh")"已记下 ${momentClock(position)}，可在片架补写札记"else"Saved ${momentClock(position)}. Add a note from your film shelf."},{if(it is kotlinx.coroutines.CancellationException)throw it;it.message?:"无法保存札记"})
                            withContext(Dispatchers.Main.immediate){android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()}
                        }
                    }
                }, enabled=controller!=null, modifier=Modifier.testTag("remember-film-moment"))

                    if(portrait)Box {
                        PlayerIcon(Icons.Rounded.MoreHoriz, "更多播放操作", { moreOpen = true })
                        MaterialTheme(colorScheme = darkColorScheme(surface = PlayerPanel, onSurface = PlayerInk)) {
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(text = { Text("上报播放问题") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { moreOpen = false; diagnosticsWait = null; diagnosticsOpen = true })
                            DropdownMenuItem(text={Text("画面比例")},leadingIcon={Icon(Icons.Rounded.AspectRatio,null)},onClick={moreOpen=false;fill=!fill})
                            DropdownMenuItem(text = { Text("后台播放") }, leadingIcon = { Icon(Icons.Rounded.Headphones, null) }, onClick = { moreOpen = false; background() })
                                if(smallWindow != null)DropdownMenuItem(text = { Text("画中画") }, leadingIcon = { Icon(Icons.Rounded.PictureInPictureAlt, null) }, enabled = videoAvailable, onClick = { moreOpen = false; smallWindow() })
                                DropdownMenuItem(text = { Text("锁定方向与控件") }, leadingIcon = { Icon(Icons.Rounded.LockOpen, null) }, onClick = { moreOpen = false; locked = true; visible = false; lock(true) })
                            }
                        }
                    }
                    if(!portrait)PlayerIcon(Icons.Rounded.Info, "上报播放问题", { diagnosticsWait = null; diagnosticsOpen = true })
                    if(!portrait)PlayerIcon(Icons.Rounded.Headphones, "后台播放", background)
                    if (!portrait && smallWindow != null) PlayerIcon(Icons.Rounded.PictureInPictureAlt, "画中画", smallWindow, videoAvailable)
                    if(!portrait)PlayerIcon(Icons.Rounded.LockOpen, "锁定方向与控件", { locked = true; visible = false; lock(true) })
                }
                if(portrait)Column(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(end=12.dp,bottom=with(density){controlsHeight.toDp()}+24.dp).testTag("portrait-track-actions"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                PlayerIcon(Icons.Rounded.Audiotrack,"音轨",{trackType=C.TRACK_TYPE_AUDIO;settings=true},controller!=null,Modifier.testTag("player-audio").background(Color(0x66101113),CircleShape))
                PlayerIcon(Icons.Rounded.Subtitles,"字幕",{trackType=C.TRACK_TYPE_TEXT;settings=true},controller!=null,Modifier.testTag("player-subtitles").background(Color(0x66101113),CircleShape))
            }
            if (!portrait && !speedOpen && !buffering && controller!=null) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!portrait && items.size > 1) PlayerIcon(Icons.Rounded.SkipPrevious, "上一集", { selectEpisode((controller?.currentMediaItemIndex?:index)-1) }, index > 0)
                    PlayerIcon(Icons.Rounded.Replay10, "后退10秒", { StationPreferences(context).seekClick(context);controller?.seekTo((position - 10_000).coerceAtLeast(0)) }, seekable)
                    IconButton(onClick = { if (playRequested) controller?.pause() else controller?.play() }, modifier = Modifier.size(if(portrait)64.dp else 72.dp).background(Color(0x8C101113), CircleShape)) {
                        Icon(if (playRequested) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playRequested) "暂停" else "播放", tint = PlayerInk, modifier = Modifier.size(38.dp))
                    }
                    PlayerIcon(Icons.Rounded.Forward10, "前进10秒", { StationPreferences(context).seekClick(context);controller?.seekTo((position + 10_000).coerceAtMost(duration)) }, seekable)
                    if (!portrait && items.size > 1) PlayerIcon(Icons.Rounded.SkipNext, "下一集", { selectEpisode((controller?.currentMediaItemIndex?:index)+1) }, index < items.lastIndex)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onGloballyPositioned{controlsHeight=it.size.height}
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6101113))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = if(portrait)16.dp else 24.dp, vertical = 8.dp)) {
                val shown = if (dragging) target else position
                PlayerTimeline(shown,duration,buffered,dragging,seekable,
                    change={dragging=true;target=it},finish={controller?.seekTo(target);position=target;dragging=false},thumbPosition={thumbBounds=it})
                if(portrait)PortraitPlaybackToolbar(
                    position=shown,duration=duration,speed=speed,index=index,count=items.size,playRequested=playRequested,enabled=controller!=null,onPlay={if(playRequested)controller?.pause()else controller?.play()},
                    onSpeed={speedOpen=true},onQueue={queue=true},onPrevious={selectEpisode((controller?.currentMediaItemIndex?:index)-1)},onNext={selectEpisode((controller?.currentMediaItemIndex?:index)+1)},
                    onRotate=rotate
                ) else Row(Modifier.fillMaxWidth().testTag("playback-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                    if(buffering&&controller!=null)PlayerIcon(if(playRequested)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playRequested)"暂停"else"播放",{if(playRequested)controller?.pause()else controller?.play()})
                    Text("${playbackTime(shown)} / ${playbackTime(duration)}", Modifier.width(116.dp).heightIn(min=48.dp).wrapContentHeight().testTag("playback-time"), color = PlayerInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if(!portrait)FlowRow(Modifier.weight(1f).testTag("playback-actions"), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
                        PlayerIcon(Icons.Rounded.Audiotrack,"音轨",{trackType=C.TRACK_TYPE_AUDIO;settings=true},modifier=Modifier.testTag("player-audio"))
                        PlayerIcon(Icons.Rounded.Subtitles,"字幕",{trackType=C.TRACK_TYPE_TEXT;settings=true},modifier=Modifier.testTag("player-subtitles"))
                        PlayerIcon(if (fill) Icons.Rounded.FitScreen else Icons.Rounded.AspectRatio, "画面比例", { fill = !fill; android.widget.Toast.makeText(context,if(fill)"铺满屏幕 · 等比裁切"else"完整画面 · 保留原比例",android.widget.Toast.LENGTH_SHORT).show() })
                        TextButton(onClick = { queue = true }, enabled = items.isNotEmpty()) { Icon(Icons.Rounded.PlaylistPlay, null, tint = PlayerInk); Text("选集 ${index + 1}/${items.size}", color = PlayerInk, fontSize = 13.sp) }
                        PlayerIcon(Icons.Rounded.ScreenRotation, "旋转屏幕", rotate)
                    }
                }
            }
        }
        if (!compact && dragging && thumbBounds!=Rect.Zero) frame?.let { bitmap ->
            val width=176.dp
            val height=99.dp
            val safeLeft=WindowInsets.safeDrawing.getLeft(density,androidx.compose.ui.unit.LayoutDirection.Ltr)
            val safeRight=WindowInsets.safeDrawing.getRight(density,androidx.compose.ui.unit.LayoutDirection.Ltr)
            val safeTop=WindowInsets.safeDrawing.getTop(density)
            Image(bitmap.asImageBitmap(),"进度预览",Modifier.offset {
                val gap=12.dp.toPx();val w=width.toPx();val h=height.toPx()
                val left=(thumbBounds.center.x-playerBounds.left-w/2).coerceIn(safeLeft+gap,(playerBounds.width-safeRight-gap-w).coerceAtLeast(safeLeft+gap))
                val top=(thumbBounds.top-playerBounds.top-gap-h).coerceAtLeast(safeTop+gap)
                IntOffset(left.roundToInt(),top.roundToInt())
            }.size(width,height).clip(RoundedCornerShape(12.dp)).background(Color.Black).testTag("seek-preview"),
                contentScale=androidx.compose.ui.layout.ContentScale.Fit)
        }
        if (!compact && !dragging) hud?.let { (name, value) ->
            PlayerAdjustmentHud(name, value, Modifier
                .align(if (name == "亮度") Alignment.CenterStart else Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp))
        }
        if (!compact && locked && (visible || touchExploration)) IconButton(onClick = { locked = false; lock(false); visible = true }, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) { Icon(Icons.Rounded.Lock, "解锁方向与控件", tint = PlayerInk) }
        if(portrait&&speedOpen)PlaybackSpeedPanel(speed,{speedOpen=false}){value->controller?.setPlaybackSpeed(value);PlaybackPreferences(context).speed=value;speedOpen=false}
        if (queue && controller != null) PlaybackQueueSheet(items, index, truncated, { queue = false }) { chosen ->
            selectEpisode(chosen);controller.play();queue=false;visible=true
        }
        if(diagnosticsOpen)PlaybackReportDialog(controller,diagnosticsWait){diagnosticsOpen=false}
        if (settings && controller != null) PlaybackOptions(controller, { settings = false }, externalCaption,externalAudio,trackType)
        (error ?: connectionError)?.let { message -> Column(Modifier.align(Alignment.Center).background(PlayerPanel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(message, color = PlayerInk); PlaybackIssueAction{diagnosticsWait=null;diagnosticsOpen=true}
                controller?.currentMediaItem?.takeIf{SidecarAudio.from(it).isNotEmpty()}?.let{item ->
                    TextButton(onClick={controller.replaceMediaItem(controller.currentMediaItemIndex,SidecarAudio.attach(item,emptyList()))}){Text("移除外挂音轨并重试",color=PlayerAccent)}
                }
                TextButton(onClick = back) { Text("返回重试", color = PlayerAccent) } } }
    }
}

@Composable internal fun PlayerIcon(icon: ImageVector, label: String, action: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(onClick = action, enabled = enabled, modifier = modifier.size(48.dp)) { Icon(icon, label, tint = if (enabled) PlayerInk else Color(0xFF777C81), modifier = Modifier.size(25.dp)) }
}
internal fun playbackTime(ms: Long): String {
    val sec = ms.coerceAtLeast(0) / 1000
    return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
}

/** The track and the painted thumb share one measured center line and a 48 dp touch target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerTimeline(position:Long,duration:Long,buffered:Long,dragging:Boolean,enabled:Boolean,
    change:(Long)->Unit,finish:()->Unit,thumbPosition:(Rect)->Unit={}) {
                Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { change(it.toLong()) },
                    onValueChangeFinished = finish, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = enabled && duration > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("playback-progress"),
                    thumb = { Canvas(Modifier.size(24.dp).onGloballyPositioned { thumbPosition(it.boundsInRoot()) }.testTag("progress-thumb")) {
                        drawCircle(PlayerInk,radius=(if(dragging)6.5.dp else 4.5.dp).toPx(),center=center)
                    } },
                    track = { state -> Canvas(Modifier.fillMaxWidth().height(24.dp).testTag("progress-track")) {
                        val stroke=(if(dragging)5.dp else 3.dp).toPx()
                        val max = duration.coerceAtLeast(1).toFloat()
                        drawLine(Color(0x55FFFFFF), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
                        drawLine(Color(0x88FFFFFF), Offset(0f, size.height / 2), Offset(size.width * (buffered / max).coerceIn(0f, 1f), size.height / 2), stroke)
                        drawLine(PlayerAccent, Offset(0f, size.height / 2), Offset(size.width * (state.value / max).coerceIn(0f, 1f), size.height / 2), stroke)
                    } })
}

/** Peripheral feedback keeps the picture and subtitles unobscured. */
@Composable
internal fun PlayerAdjustmentHud(name: String, value: Int, modifier: Modifier = Modifier) {
    val percent = value.coerceIn(0, 100)
    val brightness = name == "亮度"
    Surface(modifier.width(56.dp).testTag(if (brightness) "brightness-hud" else "volume-hud")
        .semantics { contentDescription = "${if(brightness) "头灯亮度（屏幕亮度）" else "通讯器音量（媒体音量）"} $percent%" },
        shape = RoundedCornerShape(24.dp), color = Color(0x99171C20), contentColor = PlayerInk) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (brightness) Icons.Rounded.BrightnessMedium else if (percent == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                contentDescription = null, modifier = Modifier.size(20.dp))
            Canvas(Modifier.width(8.dp).height(112.dp)) {
                val gap = 3.dp.toPx()
                val step = size.height / 16
                repeat(16) { index ->
                    val lit = percent > 0 && index < kotlin.math.ceil(percent / 100f * 16).toInt()
                    drawRoundRect(if (lit) PlayerInk.copy(alpha = .9f) else PlayerInk.copy(alpha = .18f),
                        topLeft = Offset(0f, size.height - (index + 1) * step),
                        size = androidx.compose.ui.geometry.Size(size.width, step - gap),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
            Text("$percent%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}
