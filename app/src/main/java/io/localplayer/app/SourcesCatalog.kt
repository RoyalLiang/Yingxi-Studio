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
fun SourcesCatalog(vm: LibraryViewModel, requestNearby: () -> Unit, openLocal: () -> Unit) {
    val context = LocalContext.current
    val revision = vm.organizationRevision
    val groups = remember(revision) { vm.organization.groups() }
    val saved = remember(revision) { vm.organization.bookmarks() }
    val roots = vm.sources.map { source -> saved.firstOrNull { it.sourceId == source.id && it.path == source.initialPath }
        ?.copy(label=source.label) ?: SourceBookmark(source.id, source.initialPath, source.label) }
    val bookmarks = roots + saved.filter { item -> roots.none { it.key == item.key } && vm.sources.any { it.id == item.sourceId } }
    val shown = bookmarks.filter { vm.selectedGroup == "all" || vm.selectedGroup == "favorites" && it.favorite || it.groupId == vm.selectedGroup }
    var deleting by remember { mutableStateOf<io.localplayer.core.Source?>(null) }
    var organize by remember { mutableStateOf<SourceBookmark?>(null) }
    var groupDialog by remember { mutableStateOf(false) }
    var probeConfirm by remember { mutableStateOf(false) }
    val granted=vm.nearbyPermissionGranted
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDiscoveryPermission() }
    Column(Modifier.fillMaxSize().testTag("source-list")) {
        vm.error?.let { message -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 13.sp); TextButton(onClick = { vm.error = null }) { Text("关闭") } } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("来源", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { groupDialog = true }) { Icon(Icons.Rounded.CreateNewFolder, "管理分组", tint = Berry) }
            TextButton(onClick = { vm.add() }, modifier=Modifier.semantics { contentDescription="添加来源" }) { Icon(Icons.Rounded.Add, null); Text("添加") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.selectedGroup == "all", onClick = { vm.selectedGroup = "all" }, label = { Text("全部") })
            FilterChip(selected = vm.selectedGroup == "favorites", onClick = { vm.selectedGroup = "favorites" }, label = { Text("收藏") })
            groups.forEach { group -> FilterChip(selected = vm.selectedGroup == group.id, onClick = { vm.selectedGroup = group.id }, label = { Text(group.name) }) }
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), shape = RoundedCornerShape(16.dp), color = GroupedSurface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WifiFind, null, tint = Berry, modifier = Modifier.size(24.dp))
                    Text(vm.discoveryMessage, Modifier.weight(1f).padding(start = 12.dp), fontSize = 12.sp, color = Muted)
                    TextButton(onClick = { if (!granted) requestNearby() else if (vm.scanning) vm.stopDiscovery() else vm.discover(true) }) { Text(if (!granted) "允许发现" else if (vm.scanning) "停止" else "重试") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("首页自动发现局域网设备", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = vm.autoDiscoveryEnabled, onCheckedChange = vm::setAutoDiscovery, modifier = Modifier.testTag("auto-discovery"))
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(bottom=96.dp).testTag("source-bookmarks"), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { MediaRow("打开本机视频", "本机文件与授权目录", Icons.Rounded.Folder, openLocal) }
            if (shown.isEmpty()) item { Text(if (vm.selectedGroup == "favorites") "还没有收藏的来源或文件夹" else "这个分组还没有来源", Modifier.padding(24.dp), color = Muted) }
            items(shown, key = { it.key }) { item ->
                val source = vm.sources.first { it.id == item.sourceId }
                if(item.path==source.initialPath) SavedSourceRow(source,source.id in vm.pinnedIds,
                    open={vm.openLocation(item)},edit={vm.edit(source)},pin={vm.togglePin(source)},remove={deleting=source},privateAccess={vm.privateAccess(source)},
                    trailing={BookmarkActions(vm,item){organize=item}})
                else Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.weight(1f)){MediaRow(item.label,"${source.protocol} · ${source.host} / ${item.path}",Icons.Rounded.Folder){vm.openLocation(item)}}
                    BookmarkActions(vm,item){organize=item}
                }
            }
            item { Text("附近设备 · 待验证", Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
            if (vm.candidates.isEmpty()) item { Text(if (vm.scanning) "正在寻找共享服务…" else "未广播的设备可输入地址或补充发现。", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Muted, fontSize = 13.sp) }
            items(vm.candidates, key = { it.id }) { candidate -> MediaRow(candidate.name, "${candidate.protocol} · ${candidate.host}:${candidate.port}\n${candidate.hint}", Icons.Rounded.Lan) { vm.add(candidate) } }
            item { TextButton(onClick = { probeConfirm = true }, modifier = Modifier.padding(start = 16.dp)) { Text("补充发现当前局域网") } }
        }
    }
    deleting?.let { source -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除来源？")},text={Text("移除 ${source.label} 及其快捷入口，服务器上的文件不受影响。")},confirmButton={TextButton(onClick={vm.removeSource(source);deleting=null}){Text("删除来源")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}}) }
    organize?.let { item -> BookmarkOrganizer(vm, item, { organize = null }) }
    if (groupDialog) SourceGroupDialog(vm, groups, { groupDialog = false })
    if (probeConfirm) AlertDialog(onDismissRequest = { probeConfirm = false }, title = { Text("补充发现") }, text = { Text("仅在当前 Wi-Fi 或以太网内，有限探测 22/445 端口，约 12 秒，可随时停止。候选仍需验证账户权限。") },
        confirmButton = { TextButton(onClick = { probeConfirm = false; if (granted) vm.probe() else requestNearby() }) { Text("开始") } }, dismissButton = { TextButton(onClick = { probeConfirm = false }) { Text("取消") } })
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
