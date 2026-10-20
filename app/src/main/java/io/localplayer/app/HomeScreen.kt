package io.localplayer.app

import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// finesse · register=product · home=B-gallery · palette=mist+berry · motion=press+fade
@Composable internal fun HomeScreen(vm: LibraryViewModel, openLocal: () -> Unit, onSettings: () -> Unit, onHistory: () -> Unit) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){MediaPosters.retryMissing()}
    val continuing = vm.history.firstOrNull { !it.completed && it.positionMs > 0 && it.durationMs > 0 }
    val recent = vm.history.filterNot { it.media.key == continuing?.media?.key }.take(8)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when { maxWidth >= 700.dp && LocalDensity.current.fontScale < 1.5f -> 3; maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.4f -> 2; else -> 1 }
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 116.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("映汐", Modifier.weight(1f), fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp, maxLines = 1)
                    if (vm.history.isNotEmpty()) IconButton(onClick = openLocal) { Icon(Icons.Rounded.FolderOpen, "打开视频", tint = Ink) }
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Rounded.Settings, "设置", tint = Muted) }
                }
            }
            continuing?.let { entry ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("继续观看", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        ContinueCard(entry, vm.preparingKey == entry.media.key) { vm.resume(entry) }
                    }
                }
            }
            if (vm.history.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.PlayCircle, null, Modifier.size(54.dp), tint = Berry.copy(alpha = .65f))
                    Spacer(Modifier.height(24.dp))
                    Text("还没有播放记录", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text("打开一部影片，下次从这里继续。", fontSize = 14.sp, lineHeight = 22.sp, color = Muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = openLocal, modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = "打开视频" }, shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("打开视频", maxLines = 1)
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近播放", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onHistory) { Text("全部", maxLines = 1) }
                    }
                }
                items(recent, key = { it.media.key }) { entry ->
                    RecentFilm(entry, vm.preparingKey == entry.media.key, vm.selectedHistoryKey==entry.media.key, columns > 1, { vm.resume(entry) }, { vm.resume(entry, true) }, { vm.removeHistory(entry) })
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
                MediaThumbnail(entry.media, Modifier.width(if (largeText) 76.dp else 96.dp).aspectRatio(1.6f))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.media.title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(if (preparing) "正在准备…" else "${time(entry.positionMs)} / ${time(entry.durationMs)}", fontSize = 12.sp, lineHeight = 18.sp, color = Muted, maxLines = 2)
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(Berry.copy(alpha = .09f)).semantics { contentDescription = "继续播放" }, contentAlignment = Alignment.Center) {
                    if (preparing) CircularProgressIndicator(Modifier.size(22.dp).testTag("continue-loading"), color = Berry, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp), tint = Berry)
                }
            }
            LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Berry, trackColor = Berry.copy(alpha = .07f), drawStopIndicator = {})
        }
    }
}

@Composable internal fun RecentFilm(entry: HistoryEntry, preparing: Boolean, selected: Boolean, alignTitleRows: Boolean, play: () -> Unit, restart: () -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(onClick = play, enabled = !preparing,
        color = if (selected) Berry.copy(alpha = .07f) else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("home-film:${entry.media.key}")
            .semantics { this.selected = selected }
            .border(1.dp, if (selected) Berry.copy(alpha = .38f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
                MediaThumbnail(entry.media, Modifier.fillMaxSize())
                if (preparing) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = Berry, strokeWidth = 2.dp)
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).clip(CircleShape).background(GroupedSurface.copy(alpha=.9f))) {
                        Icon(Icons.Rounded.MoreHoriz, "记录操作 ${entry.media.title}", tint = Ink, modifier = Modifier.size(21.dp))
                    }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp), containerColor = GroupedSurface) {
                        DropdownMenuItem(text = { Text("从头播放") }, onClick = { menu = false; restart() })
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
            Text(entry.media.title, Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top = 10.dp), fontSize = 15.sp, lineHeight = 22.sp,
                fontWeight = FontWeight.Medium, minLines = if (alignTitleRows) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (entry.completed) "已看完" else "${time(entry.positionMs)} / ${time(entry.durationMs)}", Modifier.padding(start=8.dp,end=8.dp,top=3.dp,bottom=8.dp), fontSize = 12.sp, lineHeight = 18.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
