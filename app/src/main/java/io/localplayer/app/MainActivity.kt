package io.localplayer.app

import io.localplayer.core.mediaDisplayTitle

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
import androidx.compose.ui.semantics.selected
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
                    localFiles.add(uri){model.notifyRegistration()}
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
    val current=vm.openingSource ?: vm.source ?: return
    val selecting=vm.rootSelectionId==current.id
    var selectedEntry by remember(current.id,vm.path){mutableStateOf<String?>(null)}
    val back={if(vm.openingSource!=null)vm.closeSource()else vm.up()}
    BackHandler(enabled=vm.draft==null&&vm.access==null){back()}
    Column(Modifier.fillMaxSize().testTag("source-browser")){
        Row(Modifier.fillMaxWidth().padding(start=8.dp,end=12.dp,top=14.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,if(vm.path.isEmpty()||!selecting&&vm.path==current.initialPath)"返回避难所"else"上一级",tint=Berry)}
            Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                Text(if(selecting)"选择默认目录"else current.label,fontSize=23.sp,lineHeight=30.sp,letterSpacing=(-.5).sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(selecting)Text(current.label,Modifier.padding(top=5.dp),fontSize=13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(selecting)TextButton(onClick={vm.closeSource()}){Text("取消")}
            else SourceActions(vm,current,vm.currentDirectoryBookmark()?:SourceBookmark(current.id,current.initialPath,current.label))
        }
        vm.source?.takeIf{vm.openingSource==null}?.let{source->
            val root=""
            val relative=vm.path.removePrefix(root).trimStart('/')
            val parts=relative.split('/').filter{it.isNotEmpty()}
            val rootName=if(root.isNotEmpty())vm.startingFolderName(source)else source.root.trimEnd('/').substringAfterLast('/').ifEmpty{"根目录"}
            val crumbs=listOf(FileCrumb(rootName,"remote:$root"))+parts.mapIndexed{i,name->
                FileCrumb(if(source.protocol==Protocol.ALIYUN)AliyunCloud.folderName(source.id,name)else name,
                    "remote:"+listOf(root,parts.take(i+1).joinToString("/")).filter{it.isNotEmpty()}.joinToString("/"))
            }
            FileBreadcrumbs(crumbs){index->vm.browse(parts.take(index).joinToString("/"))}
            HorizontalDivider(color=Hairline)
        }
        if(selecting){
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("选择后，下次直接从这里进入。",Modifier.weight(1f).padding(end=8.dp),fontSize=13.sp,lineHeight=20.sp,color=Muted)
                FilledTonalButton(onClick=vm::useCurrentFolder,enabled=!vm.busy&&vm.openingSource==null&&vm.source!=null,shape=RoundedCornerShape(16.dp)){Text("使用此文件夹")}
            }
        }
        val shown=if(selecting)vm.entries.filter{it.directory}else vm.entries
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
            if(vm.busy||vm.openingSource!=null){
                CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                Text("正在读取…",Modifier.weight(1f).padding(start=12.dp),fontSize=13.sp,color=Muted,maxLines=1)
            }else Text("${shown.size} 个${if(selecting)"文件夹"else"项目"}",Modifier.weight(1f),fontSize=13.sp,color=Muted)
            TextButton(onClick={if(vm.busy||vm.openingSource!=null){selectedEntry=null;if(vm.openingSource!=null)vm.closeSource()else vm.cancelOperation()}else vm.browse(vm.path)}){
                Text(if(vm.busy||vm.openingSource!=null)"取消"else"刷新")
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=112.dp)){
            if(shown.isEmpty()&&!vm.busy&&vm.openingSource==null)item{Text(if(selecting)"此处没有子文件夹，可以使用当前目录。"else"这个目录没有项目",Modifier.padding(32.dp),color=Muted)}
            if(vm.openingSource==null)items(shown,key={it.path}){entry->MediaRow(if(entry.directory)entry.name else if(entry.isVideo)mediaDisplayTitle(entry.name)else entry.name,if(entry.directory)"文件夹"else"${formatSize(entry.size)}${if(entry.isVideo)" · 全屏播放"else" · 格式可能不支持"}",
                if(entry.directory)Icons.Rounded.Folder else Icons.Rounded.PlayCircle, selected=selectedEntry==entry.path,busy=vm.busy&&selectedEntry==entry.path){selectedEntry=entry.path;if(entry.directory)vm.browse(entry.path)else vm.play(entry)}}
        }
    }
}
@Composable private fun Section(s:String){Text(s,Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
@Composable internal fun MediaRow(title:String,subtitle:String,icon:ImageVector,click:()->Unit){
    MediaRow(title,subtitle,icon,selected=false,click=click)
}
@Composable internal fun MediaRow(title:String,subtitle:String,icon:ImageVector,selected:Boolean,busy:Boolean=false,click:()->Unit){
    Surface(onClick=click,enabled=!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp).semantics{this.selected=selected},color=if(selected)Berry.copy(alpha=.055f)else GroupedSurface,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)Berry.copy(alpha=.25f)else androidx.compose.ui.graphics.Color.Transparent)){
        Row(Modifier.heightIn(min=76.dp).padding(start=14.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(40.dp).background(Berry.copy(alpha=.07f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Berry,modifier=Modifier.size(22.dp))}
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                Text(title,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp));Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(busy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(if(selected)Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.KeyboardArrowRight,if(selected)"已选择文件"else null,tint=if(selected)Berry else Muted.copy(alpha=.6f),modifier=Modifier.size(20.dp))
        }
    }
}

@Composable fun Field(label:String,value:String,change:(String)->Unit){PlayerField(label,value,change=change)}
fun formatSize(n:Long)=when{n>=1L.shl(30)->"%.1f GB".format(n.toDouble()/(1L.shl(30)));n>=1L.shl(20)->"%.1f MB".format(n.toDouble()/(1L.shl(20)));else->"${n/1024} KB"}

@Composable fun SourceDialogs(vm:LibraryViewModel,requestNearby:()->Unit={}){
    vm.settingsSourceId?.let { id -> vm.sources.firstOrNull { it.id == id }?.let { SourceSettingsSheet(vm, it) } }
    ShareSelectionDialog(vm)
    PrivateSourceAccessPrompt(vm)
    vm.access?.let{access->AlertDialog(onDismissRequest={vm.dismissAccess()},title={Text("验证避难所")},text={Column{
        Text(access.source.label,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(4.dp))
        Text("${access.source.protocol} · ${access.source.host}",fontSize=13.sp,color=Muted)
        Spacer(Modifier.height(16.dp));PlayerField("密码",access.password,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),transformation=PasswordVisualTransformation()){vm.updateAccess(password=it)}
        Row(verticalAlignment=Alignment.CenterVertically){Text("在本机记住密码",Modifier.weight(1f),fontSize=14.sp);Switch(checked=access.remember,onCheckedChange={vm.updateAccess(remember=it)})}
        vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
    }},confirmButton={TextButton(onClick={vm.connectAccess()},enabled=!vm.busy){Text(if(vm.busy)"正在连接…"else"打开避难所")}},dismissButton={TextButton(onClick={vm.dismissAccess()}){Text("取消")}})}
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
