package io.localplayer.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem

@Composable
fun PlaybackQueueSheet(items: List<MediaItem>, current: Int, truncated: Boolean, dismiss: () -> Unit, select: (Int) -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView=androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
                androidx.core.view.WindowInsetsControllerCompat(window,dialogView).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag("queue-dismiss-backdrop").clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss))
            val drawerWidth = minOf(maxWidth-24.dp,if(maxWidth>maxHeight)380.dp else 336.dp).coerceAtLeast(1.dp)
            Surface(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(drawerWidth).height(maxHeight*.9f).testTag("playback-queue-drawer").pointerInput(Unit) { detectTapGestures(onTap = {}) },
                color = Color(0xED171C20), contentColor = Color(0xFFF7F7F6), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)).padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("播放队列", Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "关闭选集") }
                    }
                    Text(if (truncated) "已截取点选项附近内容 · ${items.size} 项" else "${items.size} 项 · 按顺序连续播放", fontSize = 12.sp, color = Color(0xFFC5CBD0))
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (items.size - 1).coerceAtLeast(0))),
                        modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                        itemsIndexed(items, key = { _, item -> item.mediaId }) { index, item ->
                            val selected = index == current
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (selected) Color(0x22FF9AB6) else Color.Transparent, RoundedCornerShape(12.dp))
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { select(index) }).heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selected) Icon(Icons.Rounded.GraphicEq, "正在播放", Modifier.width(24.dp), tint = Color(0xFFFF9AB6))
                                else Text("${index + 1}", Modifier.width(24.dp), color = Color(0xFFBCC4CB), fontSize = 13.sp)
                                Text(playbackItemTitle(item,"第 ${index+1} 集"), Modifier.weight(1f).padding(start = 10.dp), fontSize = 15.sp,
                                    color = if (selected) Color(0xFFFFB5C8) else Color(0xFFF7F7F6), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
