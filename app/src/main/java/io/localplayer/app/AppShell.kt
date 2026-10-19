package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppTab(val label:String,val icon:ImageVector){Home("首页",Icons.Rounded.Home),Sources("来源",Icons.Rounded.Dns),History("播放记录",Icons.Rounded.History)}

@Composable fun AppShell(vm:LibraryViewModel,localFiles:LocalFilesViewModel,pickLocalFile:()->Unit,addLocalFolder:()->Unit,requestNearby:()->Unit){
    var localOpen by rememberSaveable{mutableStateOf(false)}
    val openLocal={vm.stopDiscovery();vm.cancelOperation();localFiles.refresh();localOpen=true}
    var tab by rememberSaveable{mutableStateOf(AppTab.Home)}
    val states=rememberSaveableStateHolder()
    if(localOpen){LocalFilesScreen(localFiles,vm,{localOpen=false},addLocalFolder,pickLocalFile);return}
    LifecycleEventEffect(Lifecycle.Event.ON_START){vm.refreshHistory();if(vm.source==null)vm.discover()}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){vm.stopDiscovery();vm.cancelOperation()}
    LaunchedEffect(tab){if(tab==AppTab.Sources&&vm.source==null&&vm.candidates.isEmpty()&&!vm.scanning)vm.discover()}
    BackHandler(tab!=AppTab.Home&&(tab!=AppTab.Sources||vm.source==null)&&vm.draft==null){tab=AppTab.Home}
    Scaffold(containerColor=Paper,contentWindowInsets=WindowInsets(0,0,0,0),bottomBar={
        Column{
            HorizontalDivider(color=Color(0xFFE6E2E3),thickness=.5.dp)
            NavigationBar(containerColor=Paper,tonalElevation=0.dp){AppTab.entries.forEach{item ->
                NavigationBarItem(selected=tab==item,onClick={vm.cancelOperation();tab=item},icon={Icon(item.icon,item.label)},label={Text(item.label,fontSize=12.sp)},
                    colors=NavigationBarItemDefaults.colors(selectedIconColor=Berry,selectedTextColor=Berry,indicatorColor=Color(0xFFF7E2E8),unselectedIconColor=Muted,unselectedTextColor=Muted))
            }}
        }
    }){padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))){
            if(tab!=AppTab.Sources)StatusBanner(vm)
            Box(Modifier.weight(1f)){
                states.SaveableStateProvider(tab.name){when(tab){
                    AppTab.Home->HomeScreen(vm,openLocal,{tab=AppTab.Sources},{tab=AppTab.Sources;vm.add()},{s->tab=AppTab.Sources;vm.edit(s)})
                    AppTab.Sources->LibraryScreen(vm,openLocal,requestNearby)
                    AppTab.History->HistoryScreen(vm,openLocal,{tab=AppTab.Sources})
                }}
            }
        }
    }
    SourceDialogs(vm)
}

@Composable private fun StatusBanner(vm:LibraryViewModel){
    if(vm.error!=null||vm.busy)Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(horizontal=20.dp,vertical=8.dp)){
        vm.error?.let{message ->Surface(color=Color(0xFFFBE9E7),shape=RoundedCornerShape(16.dp)){
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f),fontSize=14.sp,color=Color(0xFF8C211B));TextButton(onClick={vm.error=null}){Text("关闭")}}
        }}
        if(vm.busy)Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("正在连接与读取…",Modifier.weight(1f).padding(start=12.dp),fontSize=14.sp);TextButton(onClick={vm.cancelOperation()}){Text("取消")}}
    }
}

@Composable fun BrandMark(modifier:Modifier=Modifier){
    Box(modifier.background(Berry,RoundedCornerShape(24)),contentAlignment=Alignment.Center){
        Icon(painterResource(R.drawable.ic_brand_foreground),contentDescription=null,tint=Color.Unspecified,modifier=Modifier.fillMaxSize())
    }
}

@Composable private fun HomeScreen(vm:LibraryViewModel,openLocal:()->Unit,onSources:()->Unit,onAdd:()->Unit,onSource:(io.localplayer.core.Source)->Unit){
    val continuing=vm.history.firstOrNull{!it.completed&&it.positionMs>0&&it.durationMs>0}
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)),contentPadding=PaddingValues(bottom=24.dp)){
        item{Column(Modifier.padding(start=24.dp,end=24.dp,top=20.dp,bottom=24.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){BrandMark(Modifier.size(36.dp));Spacer(Modifier.width(12.dp));Text("映汐",fontSize=32.sp,fontWeight=FontWeight.Bold,color=Ink)}
            Spacer(Modifier.height(10.dp));Text("你的影片，你的观影时刻。",fontSize=15.sp,color=Muted)
        }}
        if(continuing!=null)item{ContinueCard(continuing){vm.resume(continuing)}}
        else item{Surface(Modifier.fillMaxWidth().padding(horizontal=20.dp),color=Color(0xFFF4ECEF),shape=RoundedCornerShape(26.dp)){
            Column(Modifier.padding(24.dp)){
                Text("从一部影片开始",fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Spacer(Modifier.height(10.dp));Text("打开本机视频，或连接你的局域网来源。",fontSize=14.sp,lineHeight=21.sp,color=Muted)
                Spacer(Modifier.height(20.dp));Button(onClick=openLocal,shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=14.dp)){Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("打开本机视频")}
            }
        }}
        item{Text("从这里打开",Modifier.padding(start=24.dp,top=28.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
        item{MediaRow("本机文件","通过系统文件选择器打开视频",Icons.Rounded.FolderOpen,openLocal)}
        item{MediaRow("局域网来源",if(vm.sources.isEmpty())"连接 SMB / SFTP 媒体来源"else"${vm.sources.size} 个已保存来源",Icons.Rounded.Lan,onSources)}
        if(vm.sources.isNotEmpty()){
            item{Text("已保存的来源",Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
            items(vm.sources.take(3),key={"source-"+it.id}){s->MediaRow(s.label,s.protocol.name,Icons.Rounded.Dns){onSource(s)}}
        }else item{TextButton(onClick=onAdd,modifier=Modifier.padding(start=16.dp)){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text("添加第一个来源")}}
        val recent=vm.history.filterNot{it.media.key==continuing?.media?.key}.take(3)
        if(recent.isNotEmpty()){
            item{Text("最近播放",Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
            items(recent,key={"recent-"+it.media.key}){e->HistoryRow(e,{vm.resume(e)},{vm.resume(e,true)},{vm.removeHistory(e)})}
        }
    }
}

@Composable private fun ContinueCard(entry:HistoryEntry,onClick:()->Unit){
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),shape=RoundedCornerShape(26.dp),color=Color(0xFFF4ECEF)){
        Column(Modifier.padding(22.dp)){
            Text("继续观看",color=Berry,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f).padding(end=18.dp)){
                    Text(entry.media.title,fontSize=23.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,color=Ink)
                    Spacer(Modifier.height(8.dp));Text(entry.media.sourceLabel,fontSize=13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                BrandMark(Modifier.size(76.dp))
            }
            Spacer(Modifier.height(22.dp))
            LinearProgressIndicator(progress={entry.progress},modifier=Modifier.fillMaxWidth().height(3.dp),color=Berry,trackColor=Color(0xFFE0D1D6))
            Spacer(Modifier.height(12.dp));Row(verticalAlignment=Alignment.CenterVertically){Text("${time(entry.positionMs)} / ${time(entry.durationMs)}",Modifier.weight(1f),fontSize=12.sp,color=Muted);Text("继续播放",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Berry);Icon(Icons.Rounded.PlayArrow,null,tint=Berry)}
        }
    }
}

@Composable private fun HistoryScreen(vm:LibraryViewModel,openLocal:()->Unit,onSources:()->Unit){
    var clearConfirm by rememberSaveable{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))){
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=20.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("播放记录",fontSize=32.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text(if(vm.history.isEmpty())"每个观影时刻，都有迹可循。"else"${vm.history.size} 部影片 · 仅保存在本机",fontSize=14.sp,color=Muted)}
            IconButton(onClick={clearConfirm=true}){Icon(Icons.Rounded.DeleteSweep,"清空播放记录",tint=Muted)}
        }
        if(vm.history.isEmpty())Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Icon(Icons.Rounded.History,null,Modifier.size(44.dp),tint=Muted);Spacer(Modifier.height(20.dp));Text("还没有播放记录",fontSize=20.sp,fontWeight=FontWeight.Medium)
            Spacer(Modifier.height(10.dp));Text("实际播放影片后，会自动记住进度。",fontSize=14.sp,color=Muted)
            Spacer(Modifier.height(24.dp));Button(onClick=openLocal,shape=RoundedCornerShape(16.dp)){Text("打开本机视频")};TextButton(onClick=onSources){Text("浏览局域网来源")}
        }else LazyColumn(contentPadding=PaddingValues(bottom=24.dp)){items(vm.history,key={it.media.key}){e->HistoryRow(e,{vm.resume(e)},{vm.resume(e,true)},{vm.removeHistory(e)})}}
    }
    if(clearConfirm)AlertDialog(onDismissRequest={clearConfirm=false},title={Text("清空播放记录？")},text={Text("将清除本机的观看记录与续播进度。来源和密码会保留。")},confirmButton={TextButton(onClick={vm.clearHistory();clearConfirm=false}){Text("清空记录",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={clearConfirm=false}){Text("取消")}})
}

@Composable private fun HistoryRow(entry:HistoryEntry,onPlay:()->Unit,onRestart:()->Unit,onRemove:()->Unit){
    var menu by remember{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().clickable(onClick=onPlay).padding(start=20.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(56.dp).background(Color(0xFFF1E8EC),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(if(entry.completed)Icons.Rounded.CheckCircle else Icons.Rounded.PlayCircle,null,tint=Berry,modifier=Modifier.size(28.dp))}
        Column(Modifier.weight(1f).padding(horizontal=14.dp)){
            Text(entry.media.title,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp));Text("${entry.media.sourceLabel} · ${if(entry.completed)"已看完"else time(entry.positionMs)+" / "+time(entry.durationMs)}",fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp));Text(SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(entry.updatedAt)),fontSize=11.sp,color=Muted)
        }
        Box{IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreVert,"记录操作",tint=Muted)}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}){DropdownMenuItem(text={Text("从头播放")},onClick={menu=false;onRestart()});DropdownMenuItem(text={Text("移除此记录")},onClick={menu=false;onRemove()})}
        }
    }
}
