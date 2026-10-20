package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

data class FileCrumb(val label:String,val key:String)

@Composable fun FileBreadcrumbs(crumbs:List<FileCrumb>,onSelect:((Int)->Unit)?=null){
    val scroll=rememberScrollState()
    LaunchedEffect(crumbs,scroll.maxValue){scroll.animateScrollTo(scroll.maxValue)}
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
        crumbs.forEachIndexed{index,crumb->
            if(index>0)Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,Modifier.size(16.dp),tint=Muted)
            val text: @Composable () -> Unit = {
                Text(crumb.label,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=200.dp),fontSize=14.sp,
                    fontWeight=if(index==crumbs.lastIndex)FontWeight.SemiBold else FontWeight.Normal,color=if(index==crumbs.lastIndex)Ink else Muted)
            }
            if(onSelect!=null)TextButton(onClick={onSelect(index)},enabled=index!=crumbs.lastIndex,
                modifier=Modifier.heightIn(min=48.dp).testTag("breadcrumb:${crumb.key}")){text()}
            else Box(Modifier.heightIn(min=48.dp).padding(horizontal=12.dp).testTag("breadcrumb:${crumb.key}"),contentAlignment=Alignment.Center){text()}

        }
    }
}

@Composable fun LocalFilesScreen(files:LocalFilesViewModel,player:LibraryViewModel,onClose:()->Unit,onAddFolder:()->Unit,onPickFile:()->Unit){
    var selectedUri by rememberSaveable{mutableStateOf<String?>(null)}
    val back={if(!files.up()){files.cancel();onClose()}}
    BackHandler{back()}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){files.cancel()}
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing)){
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=16.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,if(files.active==null)"返回"else"上一级",tint=Berry)}
            Text("本机文件",Modifier.weight(1f).padding(start=8.dp).testTag("local-browser-title"),fontSize=24.sp,letterSpacing=(-.4).sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            IconButton(onClick=onAddFolder){Icon(Icons.Rounded.CreateNewFolder,"添加授权文件夹",tint=Berry)}
        }
        if(files.active!=null){
            FileBreadcrumbs(listOf(FileCrumb("本机文件","local"))+files.trail.mapIndexed{i,d->FileCrumb(d.name,"local:$i")}){index->if(index==0)files.root()else files.navigate(index-1)}
            HorizontalDivider(color=Hairline)
        }
        val error=files.error?:player.error
        if(error!=null)Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),color=MaterialTheme.colorScheme.error);TextButton(onClick={files.error=null;player.error=null}){Text("关闭")}}
        if(files.busy)Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("正在读取文件夹…",Modifier.weight(1f).padding(start=12.dp));TextButton(onClick={files.cancel()}){Text("取消")}}
        if(files.active==null){
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=24.dp)){
                item{Column(Modifier.padding(24.dp)){
                    Text("你的文件夹",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp));Text("由你选择可访问的文件夹，影片留在原处。",fontSize=14.sp,color=Muted)
                    Spacer(Modifier.height(20.dp));Button(onClick=onAddFolder){Icon(Icons.Rounded.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("授权一个文件夹",maxLines=1)}
                    TextButton(onClick=onPickFile){Text("选择单个视频",maxLines=1)}
                }}
                items(files.folders,key={it.tree.toString()}){folder->
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Box(Modifier.weight(1f)){MediaRow(folder.name,"已授权 · 仅按你的操作读取",Icons.Rounded.Folder){files.open(folder)}}
                        IconButton(onClick={files.remove(folder)}){Icon(Icons.Rounded.Close,"移除文件夹 ${folder.name}",tint=Muted)}
                    }
                }
            }
        }else{
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("${files.entries.size} 个项目",Modifier.weight(1f),fontSize=13.sp,color=Muted);TextButton(onClick={files.refresh()}){Text("刷新")}}
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=24.dp)){
                if(files.entries.isEmpty()&&!files.busy)item{Text("这个目录没有项目",Modifier.padding(24.dp),color=Muted)}
                items(files.entries,key={it.uri.toString()}){entry->
                    MediaRow(entry.name,if(entry.directory)"文件夹"else if(entry.video)formatSize(entry.size)else"非视频文件",if(entry.directory)Icons.Rounded.Folder else Icons.Rounded.PlayCircle,selected=selectedUri==entry.uri.toString(),busy=player.preparingKey==entry.uri.toString()){
                        selectedUri=entry.uri.toString()
                        if(entry.directory)files.enter(entry)else if(entry.video)player.playLocal(entry.uri,siblings=files.entries.toList())else files.error="请选择视频文件"
                    }
                }
            }
        }
    }
}
