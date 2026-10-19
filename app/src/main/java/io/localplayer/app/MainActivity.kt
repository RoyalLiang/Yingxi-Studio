package io.localplayer.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

val Paper=Color(0xFFF7F7F6)
val Ink=Color(0xFF262625)
val Muted=Color(0xFF5C5C59)
val Berry=Color(0xFFB91E4B)

@Composable fun PlayerTheme(content:@Composable ()->Unit){MaterialTheme(colorScheme=lightColorScheme(primary=Berry,onPrimary=Paper,primaryContainer=Color(0xFFF7DDE5),onPrimaryContainer=Color(0xFF68102B),background=Paper,surface=Paper,onSurface=Ink,onBackground=Ink,secondary=Muted,secondaryContainer=Color(0xFFECE7E8),onSecondaryContainer=Ink,surfaceVariant=Color(0xFFEFEFEE)),content=content)}

class MainActivity:ComponentActivity(){
    private val model:LibraryViewModel by viewModels()
    private var openingPlayer=false
    private val localFiles:LocalFilesViewModel by viewModels()
    override fun onStart(){super.onStart();openingPlayer=false}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();setContent{
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
            val nearby=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->if(granted)model.discover()else model.error="未授予附近设备访问权限，本机文件仍可打开"}
        LaunchedEffect(model){model.plays.collect{target ->
            if(openingPlayer||!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))PlaybackRegistry.remove(target.uri)
            else {openingPlayer=true;startActivity(PlaybackActivity.intent(this@MainActivity,target))}
        }}
            AppShell(model,localFiles,{openFile.launch(arrayOf("video/*"))},{openFolder.launch(null)},{nearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES)})
        }
    }}
}

@Composable fun LibraryScreen(vm:LibraryViewModel,openLocal:()->Unit,requestNearby:()->Unit){
    var probeConfirm by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)}
    BackHandler(enabled=vm.source!=null && vm.draft==null){vm.up()}
    Scaffold(containerColor=Paper,contentWindowInsets=WindowInsets.safeDrawing){padding ->
        Column(Modifier.fillMaxSize().padding(padding)){
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                if(vm.source!=null)TextButton(onClick={vm.up()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,null);Spacer(Modifier.width(4.dp));Text(if(vm.path.isEmpty())"返回来源"else"上一级")}
                Column(Modifier.weight(1f)){
                    Text(vm.source?.label ?: "来源",fontSize=32.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(if(vm.source==null)"你的影片，近在身边。" else "${vm.source!!.protocol} · 文件浏览",fontSize=14.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if(vm.source==null)IconButton(onClick={vm.add()}){Icon(Icons.Rounded.Add,"添加来源",tint=Berry)}
                Box{IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreHoriz,"更多")}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        DropdownMenuItem(text={Text("打开本机文件")},onClick={menu=false;openLocal()})
                            DropdownMenuItem(text={Text("查找未广播的设备")},onClick={menu=false;probeConfirm=true})
                            DropdownMenuItem(text={Text("附近设备访问权限")},onClick={menu=false;requestNearby()})
                    }
                }
            }
            vm.source?.let{source->
                val parts=vm.path.split('/').filter{it.isNotEmpty()}
                FileBreadcrumbs(listOf(FileCrumb("来源","sources"),FileCrumb(source.root.trimEnd('/').substringAfterLast('/').ifEmpty{"根目录"},"remote:"))+
                    parts.mapIndexed{i,name->FileCrumb(name,"remote:"+parts.take(i+1).joinToString("/"))}){index->
                    if(index==0)vm.closeSource()else vm.browse(parts.take(index-1).joinToString("/"))
                }
                HorizontalDivider()
            }
            vm.error?.let { message ->Surface(color=Color(0xFFFBE9E7),shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp)){
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f),color=Color(0xFF8C211B));TextButton(onClick={vm.error=null}){Text("关闭")}}
            }}
            if(vm.busy){Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text("正在连接与读取…",Modifier.weight(1f).padding(start=12.dp),color=Muted);TextButton(onClick={vm.cancelOperation()}){Text("取消")}}}
            if(vm.source==null){
                Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(vm.discoveryMessage,Modifier.weight(1f),fontSize=13.sp,color=Muted)
                    TextButton(onClick={if(vm.scanning)vm.stopDiscovery()else vm.discover()}){Text(if(vm.scanning)"停止" else "刷新")}
                }
                LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){
                    if(vm.sources.isNotEmpty()){
                        item{Section("已保存")}
                        items(vm.sources,key={it.id}){s ->MediaRow(s.label,"${s.protocol} · ${s.host}:${s.port}",Icons.Rounded.Dns){vm.edit(s)}}
                    }
                    item{Section("附近服务")}
                    if(vm.candidates.isEmpty())item{
                        Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){
                            Icon(Icons.Rounded.WifiFind,null,Modifier.size(40.dp),tint=Muted)
                            Spacer(Modifier.height(16.dp));Text(if(vm.scanning)"正在寻找附近服务" else "尚未发现广播服务",fontWeight=FontWeight.Medium)
                            Spacer(Modifier.height(8.dp));Text("服务器可能未广播服务。你仍可手动添加主机地址。",color=Muted,fontSize=14.sp)
                        }
                    }
                    items(vm.candidates,key={it.id}){c ->MediaRow(c.name,"${c.protocol} · ${c.host}:${c.port}\n${c.hint}",Icons.Rounded.Lan){vm.add(c)}}
                    item{TextButton(onClick={vm.add()},modifier=Modifier.padding(start=16.dp)){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text("手动添加地址")}}
                }
            }else{
                Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("${vm.entries.size} 个项目",Modifier.weight(1f),color=Muted,fontSize=13.sp);TextButton(onClick={vm.browse(vm.path)}){Text("刷新")}}
                LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){
                    if(vm.entries.isEmpty()&&!vm.busy)item{Text("这个目录没有项目",Modifier.padding(32.dp),color=Muted)}
                    items(vm.entries,key={it.path}){e ->MediaRow(e.name,if(e.directory)"文件夹"else "${formatSize(e.size)}${if(e.isVideo)" · 点击全屏播放" else " · 文件格式可能不支持"}",if(e.directory)Icons.Rounded.Folder else Icons.Rounded.PlayCircle){if(e.directory)vm.browse(e.path)else vm.play(e)}}
                }
            }
        }
    }
    if(probeConfirm)AlertDialog(onDismissRequest={probeConfirm=false},title={Text("补充发现局域网候选")},text={Text("仅探测当前 Wi-Fi/以太网内最多一个 /24 的 22 和 445 端口，12 秒内结束。不会尝试登录。端口开放不等于支持 SMB/SFTP，仍需后续验证。")},confirmButton={TextButton(onClick={probeConfirm=false;vm.probe()}){Text("开始补充发现")}},dismissButton={TextButton(onClick={probeConfirm=false}){Text("取消")}})
}

@Composable private fun Section(s:String){Text(s,Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
@Composable internal fun MediaRow(title:String,subtitle:String,icon:ImageVector,click:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=click).padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(48.dp).background(Color(0xFFEFEFEE),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Berry)}
        Column(Modifier.weight(1f).padding(horizontal=14.dp)){Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis);Spacer(Modifier.height(4.dp));Text(subtitle,fontSize=13.sp,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis)}
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ConnectionDialog(vm:LibraryViewModel,d:Draft){
    ModalBottomSheet(onDismissRequest={vm.dismissDraft()},containerColor=Paper){
        LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Text("连接来源",fontSize=26.sp,fontWeight=FontWeight.Bold)}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Protocol.entries.forEach{p ->FilterChip(selected=d.source.protocol==p,onClick={vm.draft=d.copy(source=d.source.copy(protocol=p,root=if(p==Protocol.SFTP)"/" else "",trustedKey=null,trustedAuthority=null),port=if(p==Protocol.SFTP)"22"else"445")},label={Text(p.name)})}}}
            item{Field("名称（可选）",d.source.label){vm.draft=d.copy(source=d.source.copy(label=it))}}
            item{Field("主机名或 IP",d.source.host){vm.draft=d.copy(source=d.source.copy(host=it,trustedKey=null,trustedAuthority=null))}}
            item{Field("端口",d.port){vm.draft=d.copy(port=it,source=d.source.copy(trustedKey=null,trustedAuthority=null))}}
            item{Field(if(d.source.protocol==Protocol.SMB)"共享名（例如 Movies）"else"根目录（例如 /media）",d.source.root){vm.draft=d.copy(source=d.source.copy(root=it))}}
            item{Field(if(d.source.protocol==Protocol.SMB)"用户名（空白表示匿名）"else"用户名",d.source.username){vm.draft=d.copy(source=d.source.copy(username=it))}}
            if(d.source.protocol==Protocol.SMB)item{Field("域（可选）",d.source.domain){vm.draft=d.copy(source=d.source.copy(domain=it))}}
            item{OutlinedTextField(value=d.password,onValueChange={vm.draft=d.copy(password=it)},label={Text("密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item{Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=d.remember,onCheckedChange={vm.draft=d.copy(remember=it)});Text("记住密码 · 仅本机加密",Modifier.padding(start=12.dp),fontSize=14.sp)}}
            vm.error?.let{message ->item{Text(message,color=MaterialTheme.colorScheme.error,fontSize=14.sp)}}
            item{Button(onClick={vm.connectDraft()},enabled=!vm.busy,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(18.dp)){Text(if(vm.busy)"正在连接…"else"连接并浏览",fontSize=16.sp)}}
            if(vm.busy)item{TextButton(onClick={vm.cancelOperation()}){Text("取消连接")}}
            if(vm.sources.any{it.id==d.source.id})item{TextButton(onClick={vm.deleteDraft()}){Text("移除此来源",color=MaterialTheme.colorScheme.error)}}
        }
    }
}
@Composable private fun Field(label:String,value:String,change:(String)->Unit){OutlinedTextField(value=value,onValueChange=change,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())}
internal fun formatSize(n:Long)=when{n>=1L.shl(30)->"%.1f GB".format(n.toDouble()/(1L.shl(30)));n>=1L.shl(20)->"%.1f MB".format(n.toDouble()/(1L.shl(20)));else->"${n/1024} KB"}

@Composable fun SourceDialogs(vm:LibraryViewModel){
    vm.draft?.let{d ->ConnectionDialog(vm,d)}
    vm.trust?.let{t ->AlertDialog(onDismissRequest={vm.rejectKey()},title={Text(if(t.previous==null)"核验 SFTP 服务器身份" else "服务器身份发生变化")},text={Column{
        Text("请通过服务器管理员或独立渠道核对指纹。未信任前不会发送账号认证信息。")
        Spacer(Modifier.height(12.dp));Text(t.source.authority,fontWeight=FontWeight.Bold)
        Text(t.fingerprint,fontSize=13.sp)
        t.previous?.let{Text("原指纹：$it",fontSize=12.sp,color=Muted)}
    }},confirmButton={TextButton(onClick={vm.trustKey()}){Text("已核验并信任")}},dismissButton={TextButton(onClick={vm.rejectKey()}){Text("拒绝")}})}
}
