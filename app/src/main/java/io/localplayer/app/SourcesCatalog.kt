package io.localplayer.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesCatalog(vm: LibraryViewModel,onSealed:()->Unit={}) {
    val revision = vm.organizationRevision
    val groups = remember(revision) { vm.organization.groups() }
    val bookmarks = remember(vm.sources, revision) {
        val saved = vm.organization.bookmarks()
        val roots = vm.sources.map { source ->
            saved.firstOrNull { it.sourceId == source.id && it.path == source.initialPath }?.copy(label = source.label)
                ?: SourceBookmark(source.id, source.initialPath, source.label)
        }
        roots + saved.filter { item -> roots.none { it.key == item.key } && vm.sources.any { it.id == item.sourceId } }
    }
    val shown = bookmarks.filter{item->vm.sources.firstOrNull{it.id==item.sourceId}?.let{io.localplayer.core.SourceRoot(it.initialPath).contains(item.path)&&SealedMedia.visible(it,item.path)}==true}.filter { vm.selectedGroup == "all" || vm.selectedGroup == "favorites" && it.favorite || it.groupId == vm.selectedGroup }
    var revealedId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<io.localplayer.core.Source?>(null) }
    var organize by remember { mutableStateOf<SourceBookmark?>(null) }
    var groupDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("source-list")) {
        FlowRow(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 20.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.widthIn(max=240.dp)) {
                Text("避难所", fontSize = 32.sp, letterSpacing = (-.7).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(if (vm.sources.isEmpty()) "把远处的影像接回片架。" else "已登记 ${vm.sources.size} 处 · 随时回来", fontSize = 14.sp, color = Muted)
            }
            IconButton(onClick=onSealed,modifier=Modifier.testTag("open-sealed-cabinet")){Icon(Icons.Rounded.Lock,"封存柜",tint=Berry)}

        }
        if (vm.sources.isNotEmpty() || groups.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceFilter("全部",vm.selectedGroup == "all") { vm.selectedGroup = "all" }
                    SourceFilter("路标",vm.selectedGroup == "favorites") { vm.selectedGroup = "favorites" }
                    groups.forEach { group -> SourceFilter(group.name,vm.selectedGroup == group.id) { vm.selectedGroup = group.id } }
                }
                IconButton(onClick = { groupDialog = true }) { Icon(Icons.Rounded.FolderCopy, "信源", tint = Muted) }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("source-bookmarks"), contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key="source-kind-entries"){
                Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Surface(onClick={vm.add()},color=GroupedSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().testTag("add-outpost")){
                        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text("幸存者据点",fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text("SMB / SFTP",fontSize=13.sp,color=Muted)}
                            Icon(Icons.Rounded.Add,"连接幸存者据点",tint=Berry)
                        }
                    }
                    Surface(onClick=vm::addExternal,color=GroupedSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().testTag("add-tower")){
                        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text("外部信号塔",fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text("网盘 / WebDAV",fontSize=13.sp,color=Muted)}
                            Icon(Icons.Rounded.Add,"接入外部信号塔",tint=Berry)
                        }
                    }
                }
            }

            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).background(Berry.copy(alpha = .07f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (vm.selectedGroup == "favorites") Icons.Rounded.StarOutline else Icons.Rounded.VideoLibrary, null, Modifier.size(34.dp), tint = Berry)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(if (vm.sources.isEmpty()) "你的片库，从这里开始" else if (vm.selectedGroup == "favorites") "还没有路标" else "这个分册还没有避难所", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(if (vm.sources.isEmpty()) "选择「幸存者据点」连接 SMB / SFTP，\n或从「外部信号塔」接入网盘 / WebDAV。" else "把常用连接或目录标记为路标，再按分册整理，回来时更好找。", fontSize = 14.sp, lineHeight = 22.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(shown, key = { it.key }) { item ->
                val source = vm.sources.first { it.id == item.sourceId }
                if (item.path == source.initialPath) SavedSourceRow(source, source.id in vm.pinnedIds, item.favorite, vm.startingFolderName(source),
                    revealed = revealedId == source.id, onReveal = { revealedId = source.id },
                    open = { vm.openLocation(item) }, pin = { vm.togglePin(source) }, remove = { deleting = source }, privateAccess = { vm.privateAccess(source) },
                    selected = vm.selectedSourceId == source.id,
                    actions = { collapse -> SourceActions(vm, source, item, collapse) })
                else Surface(onClick = { vm.openLocation(item) }, color = GroupedSurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                            Text("${source.label} · 文件夹", Modifier.padding(top = 5.dp), fontSize = 12.sp, color = Muted)
                        }
                        Box {
                            var expanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreHoriz, "管理 ${item.label}", tint = Muted) }
                            DropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(20.dp), containerColor = GroupedSurface) {
                                DropdownMenuItem(text = { Text(if (item.favorite) "取消路标标记" else "路标") }, onClick = { expanded = false; vm.toggleFavorite(item) })
                                DropdownMenuItem(text = { Text("信源") }, onClick = { expanded = false; organize = item })
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { source -> ConfirmSourceRemoval(vm, source) { deleting = null } }
    organize?.let { item -> BookmarkOrganizer(vm, item) { organize = null } }
    if (groupDialog) SourceGroupDialog(vm, groups) { groupDialog = false }
}

@Composable
fun BookmarkOrganizer(vm: LibraryViewModel, initial: SourceBookmark, dismiss: () -> Unit) {
    var item by remember(initial) { mutableStateOf(initial) }
    val groups = vm.organization.groups()
    AlertDialog(onDismissRequest = dismiss, title = { Text("信源") }, text = {
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(initial.label, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.favorite, onCheckedChange = { item = item.copy(favorite = it) }); Text("标记为路标") }
            Text("分册", Modifier.padding(vertical = 8.dp), color = Muted)
            SelectionRow("未归册", item.groupId == null) { item = item.copy(groupId = null) }
            groups.forEach { group -> SelectionRow(group.name, item.groupId == group.id) { item = item.copy(groupId = group.id) } }
            if (initial.path.isNotEmpty()) TextButton(onClick = { vm.removeBookmark(initial.key); dismiss() }) { Text("移除此路标", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { vm.sources.firstOrNull { it.id == item.sourceId }?.let(vm::showSourceSettings); dismiss() }) { Text(vm.sources.firstOrNull { it.id == item.sourceId }?.let { "${it.stationKind}设置" } ?: "连接设置") }
        }
    }, confirmButton = { TextButton(onClick = { vm.putBookmark(item); dismiss() }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun SourceGroupDialog(vm: LibraryViewModel, groups: List<SourceGroup>, dismiss: () -> Unit) {
    var selected by remember { mutableStateOf<SourceGroup?>(null) }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("信源") }, text = {
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { SourceFilter("新分册", selected == null) { selected = null; name = "" }; groups.forEach { group -> SourceFilter(group.name, selected?.id == group.id) { selected = group; name = group.name } } }
            PlayerField("分册名称", name) { name = it }
            selected?.let { group -> TextButton(onClick = { vm.deleteGroup(group.id); dismiss() }) { Text("删除分册", color = MaterialTheme.colorScheme.error, maxLines = 1) } }
        }
    }, confirmButton = { TextButton(onClick = { vm.saveGroup(name, selected?.id); if (vm.error == null) dismiss() }, enabled = name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
