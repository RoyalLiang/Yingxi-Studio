package io.localplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.Protocol
import io.localplayer.core.Source

@Composable internal fun SourceActions(vm: LibraryViewModel, source: Source, item: SourceBookmark, beforeOpen: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var organize by remember { mutableStateOf(false) }
    val revision = vm.organizationRevision
    val current = remember(item, revision) { vm.organization.bookmarks().firstOrNull { it.key == item.key } ?: item }
    Box {
        IconButton(onClick = { beforeOpen(); expanded = true }, modifier = Modifier.testTag("source-menu:${source.id}")
            .background(if (expanded) Berry.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(15.dp))) {
            Icon(Icons.Rounded.MoreHoriz, "${source.stationKind}操作 ${source.label}", tint = if (expanded) Berry else Muted)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 240.dp, max = 300.dp),
            shape = RoundedCornerShape(22.dp), containerColor = GroupedSurface, tonalElevation = 0.dp) {
            Text(current.label, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = Hairline)
            DropdownMenuItem(text = { Text("${source.stationKind}设置") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) }, onClick = { expanded = false; vm.showSourceSettings(source) })
            DropdownMenuItem(text = { Text(if (current.favorite) "取消路标标记" else "路标") }, leadingIcon = { Icon(if (current.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline, null) }, onClick = { expanded = false; vm.toggleFavorite(current) })
            DropdownMenuItem(text = { Text("信源") }, leadingIcon = { Icon(Icons.Rounded.FolderCopy, null) }, onClick = { expanded = false; organize = true })
            DropdownMenuItem(text = { Text(if (source.id in vm.pinnedIds) "取消置顶" else "置顶") }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) }, onClick = { expanded = false; vm.togglePin(source) })
            DropdownMenuItem(text = { Text("封存目录") }, leadingIcon = { Icon(Icons.Rounded.Fingerprint, null) }, onClick = { expanded = false; vm.sealRemote(source,io.localplayer.core.Entry(current.label,current.path,true,0)) })
            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(text = { Text("移除登记", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; deleting = true })
        }
    }
    if (deleting) ConfirmSourceRemoval(vm, source) { deleting = false }
    if (organize) BookmarkOrganizer(vm, current) { organize = false }
}

@Composable internal fun ConfirmSourceRemoval(vm: LibraryViewModel, source: Source, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("移除登记？") }, text = { Text("移除 ${source.label} 及其路标，服务器上的文件不受影响。") },
        confirmButton = { TextButton(onClick = { vm.removeSource(source); dismiss() }) { Text("移除登记", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }, shape = RoundedCornerShape(28.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SourceSettingsSheet(vm: LibraryViewModel, source: Source) {
    var name by remember(source.id) { mutableStateOf(source.label) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = vm::dismissSourceSettings, sheetState = sheet, containerColor = Paper,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        SheetGlassEffect()
        Box(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("source-settings"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${source.stationKind}设置", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(if (source.protocol == Protocol.ALIYUN) "阿里云盘 · 只读授权" else "${source.protocol} · ${source.host}", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Muted)
                    }
                    IconButton(onClick = vm::dismissSourceSettings) { Icon(Icons.Rounded.Close, "关闭连接设置", tint = Muted) }
                }
                PlayerField("${source.stationKind}名称", name) { name = it }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("来源根目录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Surface(onClick = {
                        if (vm.updateSourcePresentation(source, name, announce = false)) vm.sources.firstOrNull { it.id == source.id }?.let(vm::beginRootSelection)
                    }, shape = RoundedCornerShape(20.dp), color = GroupedSurface) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = Berry)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(vm.startingFolderName(source), fontSize = 16.sp, lineHeight = 23.sp)
                                Text("浏览并选择文件夹", Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Muted)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
                        }
                    }
                    Text("目录导航以此处为起点，返回不会越过这里。", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                    if (source.initialPath.isNotEmpty()) TextButton(onClick = { vm.updateSourcePresentation(source, name, "", "连接根目录") }) { Text("恢复连接根目录") }
                }
                HorizontalDivider(color = Hairline)
                TextButton(onClick = { vm.dismissSourceSettings(); vm.edit(source) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(if (source.protocol == Protocol.ALIYUN) Icons.Rounded.Refresh else Icons.Rounded.ManageAccounts, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (source.protocol == Protocol.ALIYUN) "重新授权阿里云盘" else "连接与账户配置")
                }
                Button(onClick = { if (vm.updateSourcePresentation(source, name)) vm.dismissSourceSettings() }, enabled = name.isNotBlank() && name.trim().length <= 100,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("保存设置") }
            }
            SourceFeedback(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}
