package io.localplayer.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
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
    val shown = bookmarks.filter{item->vm.sources.firstOrNull{it.id==item.sourceId}?.let{io.localplayer.core.SourceRoot(it.initialPath).contains(item.path)&&SealedMedia.visible(it,item.path)}==true}.filter { vm.selectedGroup == "all" || vm.selectedGroup == "favorites" && it.favorite || it.groupId == vm.selectedGroup }
    var revealedId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<io.localplayer.core.Source?>(null) }
    var organize by remember { mutableStateOf<SourceBookmark?>(null) }
    var groupDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("source-list")) {
        Column(Modifier.fillMaxWidth().padding(start=24.dp,end=20.dp,top=20.dp,bottom=20.dp)){
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
                Text("信源",Modifier.weight(1f).padding(end=8.dp).testTag("source-catalog-title"),fontSize=32.sp,lineHeight=40.sp,letterSpacing=(-.7).sp,fontWeight=FontWeight.Bold)
                IconButton(onClick={groupDialog=true},modifier=Modifier.size(48.dp).testTag("manage-source-groups")){Icon(Icons.Rounded.FolderCopy,"整理分册",tint=Muted)}
                FilledIconButton(onClick={vm.add()},modifier=Modifier.size(48.dp).testTag("add-source")){Icon(Icons.Rounded.Add,"登记信源")}
            }
            Spacer(Modifier.height(6.dp))
            Text(if(vm.sources.isEmpty())"把远处的影像接回片架。"else"已登记 ${vm.sources.size} 处 · 随时回来",fontSize=14.sp,lineHeight=21.sp,color=Muted)
        }
        if (vm.sources.isNotEmpty() || groups.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceFilter("全部",vm.selectedGroup == "all") { vm.selectedGroup = "all" }
                    SourceFilter("路标",vm.selectedGroup == "favorites") { vm.selectedGroup = "favorites" }
                    groups.forEach { group -> SourceFilter(group.name,vm.selectedGroup == group.id) { vm.selectedGroup = group.id } }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("source-bookmarks"), contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).background(Berry.copy(alpha = .07f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (vm.selectedGroup == "favorites") Icons.Rounded.StarOutline else Icons.Rounded.VideoLibrary, null, Modifier.size(34.dp), tint = Berry)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(if (vm.sources.isEmpty()) "你的片库，从这里开始" else if (vm.selectedGroup == "favorites") "还没有路标" else "这个分册还没有信源", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(if (vm.sources.isEmpty()) "点右上角 ＋，登记一处信源，\n把远处的影像接回片架。" else "把常用连接或目录标记为路标，再按分册整理，回来时更好找。", fontSize = 14.sp, lineHeight = 22.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
fun BookmarkOrganizer(vm:LibraryViewModel,initial:SourceBookmark,dismiss:()->Unit) {
    var item by remember(initial){mutableStateOf(initial)}
    val groups=vm.organization.groups()
    AlertDialog(onDismissRequest=dismiss,containerColor=Paper,shape=RoundedCornerShape(26.dp),
        modifier=Modifier.testTag("source-organizer-dialog"),title={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("归入分册",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
            Text(initial.label,fontSize=14.sp,lineHeight=21.sp,color=Muted,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(item.favorite,{item=item.copy(favorite=it)});Text("标记为路标")}
            SelectionRow("未归册",item.groupId==null){item=item.copy(groupId=null)}
            groups.forEach { group->SelectionRow(group.name,item.groupId==group.id){item=item.copy(groupId=group.id)} }
            if(initial.path.isNotEmpty())TextButton(onClick={vm.removeBookmark(initial.key);dismiss()}){Text("移除此路标",color=MaterialTheme.colorScheme.error)}
            TextButton(onClick={vm.sources.firstOrNull{it.id==item.sourceId}?.let(vm::showSourceSettings);dismiss()}){Text("连接设置")}
        }},confirmButton={TextButton(onClick={vm.putBookmark(item);dismiss()}){Text("保存")}},
        dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

// Component scope: inherit the station palette, glass backdrop and existing group actions.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceGroupDialog(vm:LibraryViewModel,groups:List<SourceGroup>,dismiss:()->Unit){
    var selected by remember{mutableStateOf<SourceGroup?>(null)}
    var name by remember{mutableStateOf("")}
    var editing by remember{mutableStateOf(groups.isEmpty())}
    var failure by remember{mutableStateOf<String?>(null)}
    androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        SheetGlassEffect()
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha=.12f))
                .clickable(interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()},indication=null,onClick=dismiss).testTag("source-group-scrim"))
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal=20.dp,vertical=24.dp),contentAlignment=Alignment.Center){
                val dialogMaxHeight=maxHeight
            GlassSurface(Modifier.widthIn(max=460.dp).fillMaxWidth().testTag("source-group-dialog")
                    .pointerInput(Unit){detectTapGestures(onTap={})},radius=30){
                    Column(Modifier.fillMaxWidth().heightIn(max=dialogMaxHeight).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Icon(Icons.Rounded.FolderCopy,null,tint=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(24.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text("放映站 · 片架编目",fontSize=12.sp,lineHeight=18.sp,color=Muted)
                                Text(if(editing)if(selected==null)"新建分册"else"修改分册"else"整理分册",fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold)
                            }
                            IconButton(onClick=dismiss,modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.Close,"关闭分册整理",tint=Muted)}
                        }
                        Text("把信源与目录归在一册。这里只整理站内索引，原片仍留在原处。",fontSize=14.sp,lineHeight=22.sp,color=Muted)
                        HorizontalDivider(color=Hairline)
                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
                            if(editing){
                                PlayerField("分册名称",name,Modifier.testTag("source-group-name")){name=it;failure=null}
                                failure?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.testTag("source-group-error"))}
                                selected?.let{group->TextButton(onClick={vm.deleteGroup(group.id);dismiss()},modifier=Modifier.heightIn(min=48.dp)){Text("删除分册",color=MaterialTheme.colorScheme.error)}}
                            }else{
                                groups.forEach{group->
                                    Surface(onClick={selected=group;name=group.name;editing=true;failure=null},color=GroupedSurface.copy(alpha=.58f),shape=RoundedCornerShape(16.dp),
                                        border=androidx.compose.foundation.BorderStroke(.7.dp,Hairline),modifier=Modifier.fillMaxWidth().testTag("edit-source-group:${group.id}")){
                                        Row(Modifier.heightIn(min=56.dp).padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                            Text(group.name,Modifier.weight(1f),fontSize=16.sp,lineHeight=23.sp)
                                            Icon(Icons.Rounded.Edit,"修改 ${group.name}",Modifier.size(20.dp),tint=Muted)
                                        }
                                    }
                                }
                                TextButton(onClick={selected=null;name="";editing=true;failure=null},modifier=Modifier.heightIn(min=48.dp).testTag("new-source-group")){
                                    Icon(Icons.Rounded.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("新建分册")
                                }
                            }
                        }
                        HorizontalDivider(color=Hairline)
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            TextButton(onClick={if(editing&&groups.isNotEmpty()){editing=false;failure=null}else dismiss()},modifier=Modifier.heightIn(min=48.dp)){Text(if(editing&&groups.isNotEmpty())"返回"else"取消")}
                            if(editing)Button(enabled=name.isNotBlank(),onClick={vm.saveGroup(name,selected?.id);if(vm.error==null)dismiss()else failure=vm.error},shape=RoundedCornerShape(16.dp),modifier=Modifier.heightIn(min=48.dp).testTag("save-source-group")){Text("保存分册")}
                            else Button(onClick=dismiss,shape=RoundedCornerShape(16.dp),modifier=Modifier.heightIn(min=48.dp)){Text("完成")}
                        }
                    }
                }
            }
        }
    }
}
