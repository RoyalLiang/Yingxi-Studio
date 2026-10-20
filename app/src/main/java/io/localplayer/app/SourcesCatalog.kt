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

@Composable
fun SourcesCatalog(vm: LibraryViewModel) {
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
    val shown = bookmarks.filter { vm.selectedGroup == "all" || vm.selectedGroup == "favorites" && it.favorite || it.groupId == vm.selectedGroup }
    var deleting by remember { mutableStateOf<io.localplayer.core.Source?>(null) }
    var organize by remember { mutableStateOf<SourceBookmark?>(null) }
    var groupDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("source-list")) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("来源", fontSize = 32.sp, letterSpacing = (-.7).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(if (vm.sources.isEmpty()) "连接你的私人片库。" else "${vm.sources.size} 个来源 · 你的私人片库", fontSize = 14.sp, color = Muted)
            }
            FilledTonalButton(onClick = { vm.add() }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "添加来源" }, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("新建", maxLines = 1)
            }
        }
        if (vm.sources.isNotEmpty() || groups.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.selectedGroup == "all", onClick = { vm.selectedGroup = "all" }, label = { Text("全部") })
                    FilterChip(selected = vm.selectedGroup == "favorites", onClick = { vm.selectedGroup = "favorites" }, label = { Text("收藏") })
                    groups.forEach { group -> FilterChip(selected = vm.selectedGroup == group.id, onClick = { vm.selectedGroup = group.id }, label = { Text(group.name) }) }
                }
                IconButton(onClick = { groupDialog = true }) { Icon(Icons.Rounded.FolderCopy, "管理分组", tint = Muted) }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("source-bookmarks"), contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).background(Berry.copy(alpha = .07f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (vm.selectedGroup == "favorites") Icons.Rounded.StarOutline else Icons.Rounded.VideoLibrary, null, Modifier.size(34.dp), tint = Berry)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(if (vm.sources.isEmpty()) "你的片库，从这里开始" else if (vm.selectedGroup == "favorites") "还没有收藏" else "这个分组还没有来源", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(if (vm.sources.isEmpty()) "点击右上角新建，连接 NAS、服务器或网盘。\n支持 SMB、SFTP 和 WebDAV。" else "收藏来源或把文件夹添加到分组，便能快速找到它。", fontSize = 14.sp, lineHeight = 22.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(shown, key = { it.key }) { item ->
                val source = vm.sources.first { it.id == item.sourceId }
                if (item.path == source.initialPath) SavedSourceRow(source, source.id in vm.pinnedIds,
                    open = { vm.openLocation(item) }, edit = { vm.edit(source) }, pin = { vm.togglePin(source) }, remove = { deleting = source }, privateAccess = { vm.privateAccess(source) }, trailing = { BookmarkActions(vm, item) { organize = item } })
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MediaRow(item.label, "${source.protocol} · ${source.host} / ${item.path}", Icons.Rounded.Folder) { vm.openLocation(item) } }
                    BookmarkActions(vm, item) { organize = item }
                }
            }
        }
    }
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除来源？") }, text = { Text("移除 ${source.label} 及其快捷入口，服务器上的文件不受影响。") }, confirmButton = { TextButton(onClick = { vm.removeSource(source); deleting = null }) { Text("删除来源") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    organize?.let { item -> BookmarkOrganizer(vm, item) { organize = null } }
    if (groupDialog) SourceGroupDialog(vm, groups) { groupDialog = false }
}

@Composable fun DirectoryBookmarkActions(vm: LibraryViewModel) {
    val source = vm.source ?: return
    val revision = vm.organizationRevision
    val item = remember(source.id, vm.path, revision) {
        vm.organization.bookmarks().firstOrNull { it.sourceId == source.id && it.path == vm.path }
            ?: SourceBookmark(source.id, vm.path, if (vm.path.isEmpty()) source.label else if(source.protocol==io.localplayer.core.Protocol.ALIYUN) AliyunCloud.folderName(source.id,vm.path.substringAfterLast('/')) else vm.path.substringAfterLast('/'))
    }
    var organize by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { vm.putBookmark(item.copy(favorite = !item.favorite)) }) { Icon(if (item.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(if (item.favorite) "已收藏" else "收藏文件夹") }
        TextButton(onClick = { organize = true }) { Icon(Icons.Rounded.FolderCopy, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("分组") }
    }
    if (organize) BookmarkOrganizer(vm, item) { organize = false }
}

@Composable
fun BookmarkOrganizer(vm: LibraryViewModel, initial: SourceBookmark, dismiss: () -> Unit) {
    var item by remember(initial) { mutableStateOf(initial) }
    val groups = vm.organization.groups()
    AlertDialog(onDismissRequest = dismiss, title = { Text("管理 ${initial.label}") }, text = {
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.favorite, onCheckedChange = { item = item.copy(favorite = it) }); Text("收藏此入口") }
            Text("分组", Modifier.padding(vertical = 8.dp), color = Muted)
            TextButton(onClick = { item = item.copy(groupId = null) }) { Text(if (item.groupId == null) "✓ 未分组" else "未分组") }
            groups.forEach { group -> TextButton(onClick = { item = item.copy(groupId = group.id) }) { Text((if (item.groupId == group.id) "✓ " else "") + group.name) } }
            if (initial.path.isNotEmpty()) TextButton(onClick = { vm.removeBookmark(initial.key); dismiss() }) { Text("移除此快捷入口", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { vm.sources.firstOrNull { it.id == item.sourceId }?.let(vm::edit); dismiss() }) { Text("编辑服务器账户") }
        }
    }, confirmButton = { TextButton(onClick = { vm.putBookmark(item); dismiss() }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun SourceGroupDialog(vm: LibraryViewModel, groups: List<SourceGroup>, dismiss: () -> Unit) {
    var selected by remember { mutableStateOf<SourceGroup?>(null) }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("管理分组") }, text = {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton(onClick = { selected = null; name = "" }) { Text("新分组") }; groups.forEach { group -> TextButton(onClick = { selected = group; name = group.name }) { Text(group.name) } } }
            PlayerField("分组名称", name) { name = it }
            selected?.let { group -> TextButton(onClick = { vm.deleteGroup(group.id); dismiss() }) { Text("删除分组，保留来源和收藏", color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(onClick = { vm.saveGroup(name, selected?.id); if (vm.error == null) dismiss() }, enabled = name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun BookmarkActions(vm:LibraryViewModel,item:SourceBookmark,organize:()->Unit){
    IconButton(onClick={vm.putBookmark(item.copy(favorite=!item.favorite))}){Icon(if(item.favorite)Icons.Rounded.Star else Icons.Rounded.StarOutline,if(item.favorite)"取消收藏 ${item.label}"else"收藏 ${item.label}",tint=Berry)}
    IconButton(onClick=organize){Icon(Icons.Rounded.MoreVert,"管理 ${item.label}",tint=Muted)}
}
