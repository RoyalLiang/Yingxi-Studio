package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    SourceFeedbackLifetime(vm)
    var localOpen by rememberSaveable{mutableStateOf(false)}
    var settingsOpen by rememberSaveable{mutableStateOf(false)}
    val openLocal={vm.stopDiscovery();vm.cancelOperation();localFiles.refresh();localOpen=true}
    var tab by rememberSaveable{mutableStateOf(AppTab.Home)}
    if(settingsOpen){SettingsRoute{settingsOpen=false};return}
    if(localOpen){LocalFilesScreen(localFiles,vm,{localOpen=false},addLocalFolder,pickLocalFile);return}
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START){vm.refreshHistory()}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){vm.stopDiscovery();vm.cancelOperation()}
    LaunchedEffect(tab,lifecycleState,vm.discoveryPanelOpen){
        vm.setDiscoveryEnabled(tab==AppTab.Sources&&vm.discoveryPanelOpen&&lifecycleState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(vm){onDispose{vm.setDiscoveryEnabled(false)}}
    BackHandler(tab!=AppTab.Home&&(tab!=AppTab.Sources||vm.source==null)&&vm.draft==null&&vm.access==null&&vm.privateRequest==null){tab=AppTab.Home}
    val states=rememberSaveableStateHolder()
    val scene=rememberGraphicsLayer()
    Box(Modifier.fillMaxSize()){
        // Keep the chrome out of the captured scene. List content can move beneath it.
        Column(Modifier.fillMaxSize().drawWithContent{scene.record{this@drawWithContent.drawContent()};drawLayer(scene)}
            .background(Paper).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))){
            AnimatedContent(targetState=tab,modifier=Modifier.weight(1f),transitionSpec={
                fadeIn(tween(220)) togetherWith fadeOut(tween(140))
            },label="page fade"){page->
                states.SaveableStateProvider(page.name){when(page){
                    AppTab.Home->HomeScreen(vm,openLocal,{vm.closeSource();tab=AppTab.Sources},{source->tab=AppTab.Sources;vm.open(source)},{vm.stopDiscovery();vm.cancelOperation();settingsOpen=true})
                    AppTab.Sources->LibraryScreen(vm,openLocal,requestNearby)
                    AppTab.History->HistoryScreen(vm,openLocal,{vm.closeSource();tab=AppTab.Sources})
                }}
            }
        }
        if(tab!=AppTab.Sources&&vm.busy)Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=104.dp)){StatusBanner(vm)}
        CompositionLocalProvider(LocalGlassBackdrop provides scene){
        if(vm.draft==null&&vm.access==null)SourceFeedback(vm,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=100.dp))
            GlassSurface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal=20.dp,vertical=10.dp).fillMaxWidth(),radius=30){
                Box(Modifier.fillMaxWidth().padding(5.dp)){
                    BoxWithConstraints(Modifier.matchParentSize()){
                        val width=maxWidth/AppTab.entries.size
                        val x = animateDpAsState(width*tab.ordinal,spring(dampingRatio=.86f,stiffness=420f),label="navigation lens")
                        Box(Modifier.graphicsLayer{translationX=x.value.toPx()}.width(width).fillMaxHeight()
                            .background(Brush.verticalGradient(listOf(Berry.copy(alpha=.15f),Berry.copy(alpha=.06f))),RoundedCornerShape(25.dp))
                            .border(.7.dp,Color.White.copy(alpha=.85f),RoundedCornerShape(25.dp)))
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup()){
                        AppTab.entries.forEach{item->
                            val selected=tab==item
                            val scale by animateFloatAsState(if(selected)1.04f else .96f,spring(stiffness=500f),label="tab emphasis")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(25.dp)).selectable(selected=selected,role=Role.Tab,onClick={if(item==AppTab.Sources)vm.closeSource()else vm.cancelOperation();tab=item})
                                .heightIn(min=58.dp).padding(horizontal=4.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                Icon(item.icon,null,tint=if(selected)Berry else Muted,modifier=Modifier.size(23.dp).graphicsLayer{scaleX=scale;scaleY=scale})
                                Spacer(Modifier.height(3.dp))
                                Text(item.label,fontSize=11.sp,lineHeight=14.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,
                                    color=if(selected)Berry else Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        SourceDialogs(vm,requestNearby)
    }
}

@Composable private fun StatusBanner(vm:LibraryViewModel){
    if(vm.busy)GlassSurface(Modifier.padding(horizontal=20.dp),radius=24){
        Row(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            Text("正在连接与读取…",Modifier.weight(1f).padding(start=12.dp),fontSize=14.sp)
            TextButton(onClick={vm.cancelOperation()}){Text("取消")}
        }
    }
}

@Composable fun BrandMark(modifier:Modifier=Modifier){
    Box(modifier.background(Berry,RoundedCornerShape(24)),contentAlignment=Alignment.Center){
        Icon(painterResource(R.drawable.ic_brand_foreground),contentDescription=null,tint=Color.Unspecified,modifier=Modifier.fillMaxSize())
    }
}

@Composable private fun HomeScreen(vm:LibraryViewModel,openLocal:()->Unit,onSources:()->Unit,onSource:(io.localplayer.core.Source)->Unit,onSettings:()->Unit){
    val continuing=vm.history.firstOrNull{!it.completed&&it.positionMs>0&&it.durationMs>0}
    LazyColumn(Modifier.fillMaxSize().testTag("home-list"),contentPadding=PaddingValues(bottom=112.dp)){
        item{Column(Modifier.padding(start=24.dp,end=24.dp,top=18.dp,bottom=20.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){BrandMark(Modifier.size(36.dp));Spacer(Modifier.width(12.dp));Text("映汐播放器",Modifier.weight(1f),fontSize=32.sp,letterSpacing=(-.7).sp,fontWeight=FontWeight.Bold,color=Ink);IconButton(onClick=onSettings,modifier=Modifier.testTag("open-settings")){Icon(Icons.Rounded.Settings,"设置",tint=Muted)}}
            Spacer(Modifier.height(10.dp));Text("让喜欢的影片，触手可及。",fontSize=15.sp,color=Muted)
        }}
        if(continuing!=null)item{ContinueCard(continuing,preparing=vm.preparingKey==continuing.media.key){vm.resume(continuing)}}
        else item{Surface(Modifier.fillMaxWidth().padding(horizontal=20.dp),color=GroupedSurface,shape=RoundedCornerShape(26.dp)){
            Column(Modifier.padding(22.dp)){
                Text("现在，放映。",fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Spacer(Modifier.height(10.dp));Text("打开本机视频，或连接你的媒体来源。",fontSize=14.sp,lineHeight=21.sp,color=Muted)
                Spacer(Modifier.height(20.dp));Button(onClick=openLocal,shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=14.dp)){Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("打开本机视频")}
            }
        }}
        item{Text("从这里打开",Modifier.padding(start=24.dp,top=28.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
        item{MediaRow("本机文件","通过系统文件选择器打开视频",Icons.Rounded.FolderOpen,openLocal)}
        item{MediaRow("网络来源",if(vm.sources.isEmpty())"连接 SMB / SFTP / WebDAV"else"${vm.sources.size} 个已保存来源",Icons.Rounded.Lan,onSources)}
        if(vm.sources.isNotEmpty()){
            item{Text("已保存的来源",Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
            items(vm.sources.take(3),key={"source-"+it.id}){s->MediaRow(s.label,s.protocol.name,Icons.Rounded.Dns){onSource(s)}}
        }
        val recent=vm.history.filterNot{it.media.key==continuing?.media?.key}.take(3)
        if(recent.isNotEmpty()){
            item{Text("最近播放",Modifier.padding(start=24.dp,top=24.dp,bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
            items(recent,key={"recent-"+it.media.key}){e->HistoryRow(e,{vm.resume(e)},{vm.resume(e,true)},{vm.removeHistory(e)})}
        }
    }

}

@Composable internal fun ContinueCard(entry:HistoryEntry,preparing:Boolean=false,onClick:()->Unit){
    Surface(onClick=onClick,enabled=!preparing,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=16.dp).testTag("continue-player"),
        shape=RoundedCornerShape(22.dp),color=GroupedSurface,shadowElevation=1.dp){
        Column{
            Row(Modifier.fillMaxWidth().padding(start=12.dp,end=8.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
                MediaThumbnail(entry.media,Modifier.width(78.dp).height(44.dp))
                Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                    Text(entry.media.title,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(if(preparing)"正在准备播放…"else"继续观看 · ${time(entry.positionMs)} / ${time(entry.durationMs)}",fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Box(Modifier.size(48.dp).semantics{contentDescription="继续播放"},contentAlignment=Alignment.Center){if(preparing)CircularProgressIndicator(Modifier.size(24.dp).testTag("continue-loading"),color=Berry,strokeWidth=2.dp)else Icon(Icons.Rounded.PlayArrow,null,tint=Berry,modifier=Modifier.size(30.dp))}
            }
            LinearProgressIndicator(progress={entry.progress},modifier=Modifier.fillMaxWidth().height(2.dp),color=Berry,trackColor=Berry.copy(alpha=.08f),drawStopIndicator={})
        }
    }
}

@Composable private fun HistoryScreen(vm:LibraryViewModel,openLocal:()->Unit,onSources:()->Unit){
    var clearConfirm by rememberSaveable{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=20.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("播放记录",fontSize=32.sp,letterSpacing=(-.7).sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text(if(vm.history.isEmpty())"每个观影时刻，都有迹可循。"else"${vm.history.size} 部影片 · 仅保存在本机",fontSize=14.sp,color=Muted)}
            IconButton(onClick={clearConfirm=true}){Icon(Icons.Rounded.DeleteSweep,"清空播放记录",tint=Muted)}
        }
        if(vm.history.isEmpty())Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Icon(Icons.Rounded.History,null,Modifier.size(44.dp),tint=Muted);Spacer(Modifier.height(20.dp));Text("还没有播放记录",fontSize=20.sp,fontWeight=FontWeight.Medium)
            Spacer(Modifier.height(10.dp));Text("实际播放影片后，会自动记住进度。",fontSize=14.sp,color=Muted)
            Spacer(Modifier.height(24.dp));Button(onClick=openLocal,shape=RoundedCornerShape(16.dp)){Text("打开本机视频")};TextButton(onClick=onSources){Text("浏览局域网来源")}
        }else LazyColumn(contentPadding=PaddingValues(bottom=112.dp)){
            vm.history.groupBy{historyDay(it.updatedAt)}.forEach{(day,entries)->
                item(key="day-$day"){Text(day,Modifier.padding(start=24.dp,top=12.dp,bottom=10.dp),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Muted)}
                items(entries,key={it.media.key}){e->HistoryRow(e,{vm.resume(e)},{vm.resume(e,true)},{vm.removeHistory(e)})}
            }
        }
    }
    if(clearConfirm)AlertDialog(onDismissRequest={clearConfirm=false},title={Text("清空播放记录？")},text={Text("将清除本机的观看记录与续播进度。来源和密码会保留。")},confirmButton={TextButton(onClick={vm.clearHistory();clearConfirm=false}){Text("清空记录",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={clearConfirm=false}){Text("取消")}})
}

internal fun historyDay(timestamp:Long):String{
    val format=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault())
    val today=format.format(Date());val yesterday=java.util.Calendar.getInstance().apply{add(java.util.Calendar.DATE,-1)}.time
    val value=format.format(Date(timestamp))
    return when(value){today->"今天";format.format(yesterday)->"昨天";else->value}
}

@Composable private fun HistoryRow(entry:HistoryEntry,onPlay:()->Unit,onRestart:()->Unit,onRemove:()->Unit){
    var menu by remember{mutableStateOf(false)}
    Surface(onClick=onPlay,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp).testTag("history-row:${entry.media.key}"),
        color=GroupedSurface,shape=RoundedCornerShape(20.dp)){
        Column{
            Row(Modifier.padding(start=10.dp,end=4.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically){
                MediaThumbnail(entry.media,Modifier.width(78.dp).height(44.dp))
                Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                    Text(entry.media.title,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp));Text("${if(entry.completed)"已看完"else time(entry.positionMs)+" / "+time(entry.durationMs)} · ${entry.media.sourceLabel}",fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.PlayArrow,null,tint=Berry,modifier=Modifier.size(24.dp))
                Box{IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreHoriz,"记录操作 ${entry.media.title}",tint=Muted,modifier=Modifier.size(20.dp))}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        DropdownMenuItem(text={Text("从头播放")},onClick={menu=false;onRestart()})
                        DropdownMenuItem(text={Text("移除此记录")},onClick={menu=false;onRemove()})
                    }
                }
            }
            LinearProgressIndicator(progress={entry.progress},modifier=Modifier.fillMaxWidth().height(2.dp),color=Berry.copy(alpha=if(entry.completed).35f else 1f),trackColor=Berry.copy(alpha=.06f),drawStopIndicator={})
        }
    }
}
