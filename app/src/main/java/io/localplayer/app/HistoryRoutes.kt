package io.localplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.mediaDisplayTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The only input is public local playback history; this is not a geographic map. */
@Composable internal fun ScavengerRoute(vm:LibraryViewModel,modifier:Modifier=Modifier) {
    LazyColumn(modifier.testTag("scavenger-route"),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=112.dp)) {
        item { Text("拾荒者路线图",fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.padding(top=4.dp,bottom=6.dp))
            Text("本机最近观看",fontSize=13.sp,color=Muted,modifier=Modifier.padding(bottom=16.dp)) }
        vm.history.groupBy{historyDay(it.updatedAt)}.forEach{(day,entries)->
            item(key="route-day-$day") {Text(day,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Muted,modifier=Modifier.padding(top=8.dp,bottom=10.dp))}
            itemsIndexed(entries,key={_,entry->entry.media.key}){index,entry->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("route-entry:${entry.media.key}"),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Column(Modifier.width(10.dp).fillMaxHeight(),horizontalAlignment=Alignment.CenterHorizontally){
                        Spacer(Modifier.height(22.dp))
                        Box(Modifier.size(8.dp).background(if(entry.completed)Berry else Muted,CircleShape))
                        if(index<entries.lastIndex)Box(Modifier.width(1.dp).weight(1f).background(Hairline))
                    }
                    Surface(onClick={vm.resume(entry)},color=GroupedSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.weight(1f).padding(bottom=12.dp)){
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Text(SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(entry.updatedAt)),Modifier.weight(1f),fontSize=12.sp,color=Muted)
                                HistoryActionMenu(entry,{vm.resume(entry,true)},{vm.removeHistory(entry)},{vm.sealMedia(entry.media)})
                            }
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                MediaPoster(entry.media,Modifier.size(80.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                    Text(mediaDisplayTitle(entry.media.title),fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,color=Ink,maxLines=3,overflow=TextOverflow.Ellipsis)
                                    Text(if(entry.completed)"已看完"else time(entry.positionMs)+" / "+time(entry.durationMs),fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=2)
                                }
                            }
                            val progress=if(entry.durationMs>0)(entry.positionMs.toFloat()/entry.durationMs).coerceIn(0f,1f)else 0f
                            LinearProgressIndicator(progress={if(entry.completed)1f else progress},modifier=Modifier.fillMaxWidth().height(3.dp),color=Berry,trackColor=Hairline)
                        }
                    }
                }
            }
        }
    }
}
@Composable internal fun HistoryActionMenu(entry:HistoryEntry,onRestart:()->Unit,onRemove:()->Unit,onSeal:(()->Unit)?=null){
    var menu by remember{mutableStateOf(false)}
    Box{
        IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreHoriz,"记录操作 ${mediaDisplayTitle(entry.media.title)}",tint=Muted,modifier=Modifier.size(20.dp))}
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
            DropdownMenuItem(text={Text("从头播放")},onClick={menu=false;onRestart()})
            if(onSeal!=null)DropdownMenuItem(text={Text(sealActionLabel(false))},onClick={menu=false;onSeal()})
            DropdownMenuItem(text={Text("移除此记录")},onClick={menu=false;onRemove()})
        }
    }
}
