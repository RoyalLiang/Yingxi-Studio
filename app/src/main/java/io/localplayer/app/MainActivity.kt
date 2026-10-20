package io.localplayer.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.localplayer.core.Protocol


class MainActivity:ComponentActivity(){
    private val model:LibraryViewModel by viewModels()
    private var openingPlayer=false
    private val localFiles:LocalFilesViewModel by viewModels()
    override fun onStart(){super.onStart();openingPlayer=false}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        // The library uses a light palette even when Android is in dark mode.
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
        configureLaunchTransition()
        setContent{
        PlayerTheme{
            val openFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri -> if(uri!=null){
                runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                model.playLocal(uri)
            }}
            val openFolder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null){
                runCatching{
                    contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    localFiles.add(uri)
                }.onFailure{localFiles.error="无法保存文件夹授权，请重新选择"}
            }}
            val nearby=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->model.refreshDiscoveryPermission();if(granted){if(model.discoveryPanelOpen)model.discover()}else model.error="未授予附近设备访问权限，本机文件仍可打开"}
        LaunchedEffect(model){model.plays.collect{target ->
            if(openingPlayer||!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))target.release()
            else {openingPlayer=true;runCatching{startActivity(PlaybackActivity.intent(this@MainActivity,target),android.app.ActivityOptions.makeCustomAnimation(this@MainActivity,R.anim.player_enter,R.anim.player_exit).toBundle())}.onFailure{PlaybackRequests.remove(target.playId);openingPlayer=false;model.error="无法打开播放器"}}
        }}
            AppShell(model,localFiles,{openFile.launch(arrayOf("video/*"))},{openFolder.launch(null)},{nearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES)})
        }
    }}
}

@Composable fun LibraryScreen(vm:LibraryViewModel,openLocal:()->Unit,requestNearby:()->Unit){
    if(vm.source==null&&vm.openingSource==null){SourcesCatalog(vm);return}

    var menu by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf<io.localplayer.core.Source?>(null)}
    val current=vm.openingSource ?: vm.source
    BackHandler(enabled=current!=null&&vm.draft==null&&vm.access==null){if(vm.openingSource!=null)vm.closeSource()else vm.up()}
    Column(Modifier.fillMaxSize().testTag(if(current==null)"source-list"else"source-browser")){
        if(vm.source!=null)DirectoryBookmarkActions(vm)
        Row(Modifier.fillMaxWidth().padding(start=if(current==null)24.dp else 8.dp,end=12.dp,top=14.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            if(current!=null)TextButton(onClick={if(vm.openingSource!=null)vm.closeSource()else vm.up()}){
                Icon(Icons.AutoMirrored.Rounded.ArrowBack,null,Modifier.size(20.dp));Spacer(Modifier.width(4.dp));Text(if(vm.path.isEmpty())"返回来源"else"上一级",fontSize=13.sp)
            }
            Column(Modifier.weight(1f)){
                Text(current?.label ?: "来源",fontSize=if(current==null)32.sp else 23.sp,letterSpacing=(-.5).sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp));Text(if(current==null)"你的私人片库。"else"${current.protocol} · 文件浏览",fontSize=13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(current==null)IconButton(onClick={vm.add()}){Icon(Icons.Rounded.Add,"添加来源",tint=Berry)}
            else IconButton(onClick={vm.edit(current)}){Icon(Icons.Rounded.Tune,"修改配置 ${current.label}",tint=Muted)}
            Box{IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreHoriz,"更多")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text("打开本机文件")},onClick={menu=false;openLocal()})
                }
            }
        }
        vm.source?.takeIf{vm.openingSource==null}?.let{source->
            val parts=vm.path.split('/').filter{it.isNotEmpty()}
            FileBreadcrumbs(listOf(FileCrumb("来源","sources"),FileCrumb(source.root.trimEnd('/').substringAfterLast('/').ifEmpty{"根目录"},"remote:"))+
                parts.mapIndexed{i,name->FileCrumb(if(source.protocol==Protocol.ALIYUN)AliyunCloud.folderName(source.id,name)else name,"remote:"+parts.take(i+1).joinToString("/"))}){index->
                if(index==0)vm.closeSource()else vm.browse(parts.take(index-1).joinToString("/"))
            }
            HorizontalDivider(color=Hairline)
        }
        if(vm.busy||vm.openingSource!=null)Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
            CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("正在连接与读取…",Modifier.weight(1f).padding(start=12.dp),fontSize=14.sp,color=Muted);TextButton(onClick={vm.closeSource()}){Text("取消")}
        }
        if(current==null)LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=112.dp)){
            if(vm.sources.isEmpty())item{Column(Modifier.padding(32.dp)){
                Text("尚未保存来源",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp));Text("在首页发现设备，或点击右上角添加来源。",fontSize=14.sp,lineHeight=21.sp,color=Muted)
            }}else{
                item{Row(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,top=8.dp,bottom=10.dp)){
                    Text("已保存",Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Muted)
                    Text("${vm.pinnedIds.size}/3 置顶",fontSize=12.sp,color=Muted)
                }}
                items(vm.sources,key={it.id}){source->SavedSourceRow(source,source.id in vm.pinnedIds,
                    open={vm.open(source)},edit={vm.edit(source)},pin={vm.togglePin(source)},remove={deleting=source},privateAccess={vm.privateAccess(source)})}
                item{Text("左滑置顶或删除，右滑私密访问",Modifier.padding(horizontal=24.dp,vertical=14.dp),fontSize=12.sp,color=Muted)}
            }
        }else{
            if(vm.openingSource==null)Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
                Text("${vm.entries.size} 个项目",Modifier.weight(1f),fontSize=13.sp,color=Muted);TextButton(onClick={vm.browse(vm.path)}){Text("刷新")}
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=112.dp)){
                if(vm.entries.isEmpty()&&!vm.busy&&vm.openingSource==null)item{Text("这个目录没有项目",Modifier.padding(32.dp),color=Muted)}
                if(vm.openingSource==null)items(vm.entries,key={it.path}){entry->MediaRow(entry.name,if(entry.directory)"文件夹"else"${formatSize(entry.size)}${if(entry.isVideo)" · 全屏播放"else" · 格式可能不支持"}",
                    if(entry.directory)Icons.Rounded.Folder else Icons.Rounded.PlayCircle){if(entry.directory)vm.browse(entry.path)else vm.play(entry)}}
            }
        }
    }
    deleting?.let{source->AlertDialog(onDismissRequest={deleting=null},title={Text("删除来源？")},
        text={Text("将移除“${source.label}”和本机保存的密码。服务器上的文件会保留。")},
        confirmButton={TextButton(onClick={vm.removeSource(source);deleting=null}){Text("删除来源",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

@Composable private fun Section(s:String){Text(s,Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
@Composable internal fun MediaRow(title:String,subtitle:String,icon:ImageVector,click:()->Unit){
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=3.dp),color=GroupedSurface,shape=RoundedCornerShape(18.dp)){
        Row(Modifier.heightIn(min=76.dp).padding(start=14.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(40.dp).background(Berry.copy(alpha=.07f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Berry,modifier=Modifier.size(22.dp))}
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                Text(title,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp));Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted.copy(alpha=.6f),modifier=Modifier.size(18.dp))
        }
    }
}

@Composable fun Field(label:String,value:String,change:(String)->Unit){PlayerField(label,value,change=change)}
fun formatSize(n:Long)=when{n>=1L.shl(30)->"%.1f GB".format(n.toDouble()/(1L.shl(30)));n>=1L.shl(20)->"%.1f MB".format(n.toDouble()/(1L.shl(20)));else->"${n/1024} KB"}

@Composable fun SourceDialogs(vm:LibraryViewModel,requestNearby:()->Unit={}){
    ShareSelectionDialog(vm)
    PrivateSourceAccessPrompt(vm)
    vm.access?.let{access->AlertDialog(onDismissRequest={vm.dismissAccess()},title={Text("验证来源")},text={Column{
        Text(access.source.label,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(4.dp))
        Text("${access.source.protocol} · ${access.source.host}",fontSize=13.sp,color=Muted)
        Spacer(Modifier.height(16.dp));PlayerField("密码",access.password,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),transformation=PasswordVisualTransformation()){vm.updateAccess(password=it)}
        Row(verticalAlignment=Alignment.CenterVertically){Text("在本机记住密码",Modifier.weight(1f),fontSize=14.sp);Switch(checked=access.remember,onCheckedChange={vm.updateAccess(remember=it)})}
        vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
    }},confirmButton={TextButton(onClick={vm.connectAccess()},enabled=!vm.busy){Text(if(vm.busy)"正在连接…"else"打开来源")}},dismissButton={TextButton(onClick={vm.dismissAccess()}){Text("取消")}})}
    vm.draft?.let{d ->ConnectionDialog(vm,d,requestNearby)}
    vm.trust?.let{t ->AlertDialog(onDismissRequest={vm.rejectKey()},title={Text(if(t.previous==null)"核验 SFTP 服务器身份" else "服务器身份发生变化")},text={Column{
        Text("请通过服务器管理员或独立渠道核对指纹。未信任前不会发送账号认证信息。")
        Spacer(Modifier.height(12.dp));Text(t.source.authority,fontWeight=FontWeight.Bold)
        Text(t.fingerprint,fontSize=13.sp)
        t.previous?.let{Text("原指纹：$it",fontSize=12.sp,color=Muted)}
    }},confirmButton={TextButton(onClick={vm.trustKey()}){Text("已核验并信任")}},dismissButton={TextButton(onClick={vm.rejectKey()}){Text("拒绝")}})}
}

@Composable internal fun ShareSelectionDialog(vm:LibraryViewModel){
    vm.shareChoices?.let{choices->AlertDialog(onDismissRequest={vm.closeShares()},title={Text("选择共享文件夹")},text={LazyColumn{items(choices){name->TextButton(onClick={vm.chooseShare(name)},modifier=Modifier.fillMaxWidth()){Text(name)}}}},confirmButton={TextButton(onClick={vm.closeShares()}){Text("返回")}})}
}
