package io.localplayer.app

import io.localplayer.core.mediaDisplayTitle

import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// finesse · register=product · home=B-gallery · palette=mist+berry · motion=press+fade
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HomeScreen(vm: LibraryViewModel, openLocal: () -> Unit, onSettings: () -> Unit, onHistory: () -> Unit, onArchives: () -> Unit = {}, onSealed: () -> Unit = {}) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){MediaPosters.retryMissing()}
    val context = androidx.compose.ui.platform.LocalContext.current
    val station = remember(context) { StationPreferences(context) }
    var diary by remember(station){mutableStateOf(station.diary())}
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){diary=station.diary()}
    remember { station.claimGreeting() }
    val inventoryRevision = StationInventory.revision.intValue
    val found = remember(inventoryRevision) { StationInventory(context).today().filter{SealedMedia.visible(it.media)}.take(8) }
    val continuing = vm.history.firstOrNull { !it.completed && it.positionMs > 0 && it.durationMs > 0 }
    val recent = vm.history.filterNot { it.media.key == continuing?.media?.key }.take(8)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when { maxWidth >= 700.dp && LocalDensity.current.fontScale < 1.5f -> 3; maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.4f -> 2; else -> 1 }
        val brandSize = if(maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f) 28.sp else 32.sp
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 116.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.padding(top=4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    StationCabinetName("映汐",Ink,onSealed,Modifier.alignByBaseline(),brandSize)
                        Text("放映站", modifier = Modifier.alignByBaseline().testTag("home-brand-caption"), fontSize = 13.sp, color = Muted, letterSpacing = 3.sp, maxLines = 1)
                    }
                    Row {
                        IconButton(onClick = openLocal) { Icon(Icons.Rounded.FolderOpen, "打开视频", tint = Ink) }
                        IconButton(onClick = onSettings, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Rounded.Settings, "站务", tint = Muted) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                StationNotice("映汐放映站 · 值班札记", "拾荒者日记 · 相伴第 ${station.day} 天", diary.text.inLanguage(androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language),
                    modifier=Modifier.testTag("station-diary"),onArchives=onArchives,person=station.diaryAuthor(diary),onRefresh={diary=station.diary(force=true)})
            }
            continuing?.let { entry ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("继续放映", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        ContinueCard(entry, vm.preparingKey == entry.media.key) { vm.resume(entry) }
                    }
                }
            }
            if (vm.history.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.PlayCircle, null, Modifier.size(54.dp), tint = Berry.copy(alpha = .65f))
                    Spacer(Modifier.height(24.dp))
                    Text("还没有拾荒记录", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text("打开一部影片，下次从这里继续。", fontSize = 14.sp, lineHeight = 22.sp, color = Muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Text("点右上角文件夹，开始第一场放映。",fontSize=14.sp,color=Muted)
                }
            }
            if(found.isNotEmpty()) {
                item(span={GridItemSpan(maxLineSpan)}) { Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("今日拾获",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    Text("今天新发现 ${StationInventory(context).today().size} 部 · 来自已浏览的目录",fontSize=12.sp,color=Muted)
                } }
                items(found,key={"found:${it.media.key}"}) { finding ->
                    FindingCard(finding) { vm.resume(HistoryEntry(finding.media,0,0,finding.firstSeen,false)) }
                }
            }
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近拾荒", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onHistory) { Text("全部", maxLines = 1) }
                    }
                }
                items(recent, key = { it.media.key }) { entry ->
                    RecentFilm(entry, vm.preparingKey == entry.media.key, vm.selectedHistoryKey==entry.media.key, columns > 1, { vm.resume(entry) }, { vm.resume(entry, true) }, { vm.removeHistory(entry) },{vm.sealMedia(entry.media)})
                }
            }
        }
    }
}

@Composable internal fun ContinueCard(entry: HistoryEntry, preparing: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = !preparing, modifier = Modifier.fillMaxWidth().testTag("continue-player"),
        shape = RoundedCornerShape(20.dp), color = GroupedSurface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val largeText = LocalDensity.current.fontScale > 1.4f
                MediaPoster(entry.media, Modifier.width(if (largeText) 76.dp else 96.dp).aspectRatio(if(largeText)1f else 1.6f))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(mediaDisplayTitle(entry.media.title), fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(if (preparing) "正在准备…" else "${time(entry.positionMs)} / ${time(entry.durationMs)}", fontSize = 12.sp, lineHeight = 18.sp, color = Muted, maxLines = 2)
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(Berry.copy(alpha = .09f)).semantics { contentDescription = "继续放映" }, contentAlignment = Alignment.Center) {
                    if (preparing) CircularProgressIndicator(Modifier.size(22.dp).testTag("continue-loading"), color = Berry, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp), tint = Berry)
                }
            }
            LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Berry, trackColor = Berry.copy(alpha = .07f), drawStopIndicator = {})
        }
    }
}

@Composable internal fun RecentFilm(entry: HistoryEntry, preparing: Boolean, selected: Boolean, alignTitleRows: Boolean, play: () -> Unit, restart: () -> Unit, remove: () -> Unit, seal: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("home-film:${entry.media.key}")
            .clickable(enabled=!preparing,interactionSource=remember { MutableInteractionSource() },indication=null,onClick=play)
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(16.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(16.dp))
                .border(2.dp,if(selected)Berry else Color.Transparent,RoundedCornerShape(16.dp)).testTag("film-poster:${entry.media.key}")) {
                MediaThumbnail(entry.media, Modifier.fillMaxSize())
                PosterPlaybackStatus(entry,Modifier.align(Alignment.BottomCenter))
                if (preparing) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = Berry, strokeWidth = 2.dp)

                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, "记录操作 ${mediaDisplayTitle(entry.media.title)}", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(19.dp).offset(x=6.dp,y=(-10).dp))
                    }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp), containerColor = GroupedSurface) {
                        DropdownMenuItem(text = { Text("从头播放") }, onClick = { menu = false; restart() })
                        if(seal!=null)DropdownMenuItem(text={Text(sealActionLabel(false))},onClick={menu=false;seal()})
                        MediaPosters.credit(context, entry.media)?.takeIf { it.second.startsWith("https://") }?.let { credit ->
                            DropdownMenuItem(text = { Text("海报来源 · "+credit.first) }, onClick = {
                                menu = false
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, credit.second.toUri())) }
                            })
                        }
                        DropdownMenuItem(text = { Text("移除此记录") }, onClick = { menu = false; remove() })
                    }
                }
            }
            Text(mediaDisplayTitle(entry.media.title), Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top = 10.dp), fontSize = 15.sp, lineHeight = 22.sp,
                fontWeight = FontWeight.Medium, minLines = if (alignTitleRows) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A dark scrim stays behind white status text even over a completely white poster. */
@Composable internal fun PosterPlaybackStatus(entry:HistoryEntry,modifier:Modifier=Modifier) {
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(
        0f to Color.Transparent,.4f to Color.Black.copy(alpha=.76f),1f to Color.Black.copy(alpha=.88f)))
        .padding(start=10.dp,end=10.dp,top=20.dp,bottom=9.dp).testTag("film-status:${entry.media.key}")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(entry.completed)"已看完" else "${time(entry.positionMs)} / ${time(entry.durationMs)}",
                modifier=Modifier.weight(1f),color=Color.White,fontSize=11.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            PosterFormatText(entry.media)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress={entry.progress},modifier=Modifier.fillMaxWidth().height(3.dp),
            color=Color(0xFFFFB5C8),trackColor=Color.White.copy(alpha=.35f),drawStopIndicator={})
    }
}

@Composable internal fun FindingCard(finding:StationFinding,play:()->Unit) {
    Surface(onClick=play,color=Color.Transparent,modifier=Modifier.fillMaxWidth().testTag("found:${finding.media.key}")) {
        Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
            MediaPoster(finding.media,Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(16.dp)))
            Text(mediaDisplayTitle(finding.media.title),fontSize=15.sp,lineHeight=22.sp,minLines=2,maxLines=2,
                overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().testTag("found-title:${finding.media.key}"))
        }
    }
}
