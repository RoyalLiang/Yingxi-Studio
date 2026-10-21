package io.localplayer.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.ArchiveIdentity
import io.localplayer.core.mediaDisplayTitle
import kotlinx.coroutines.delay

internal fun localArchiveIdentity(entry:LocalEntry,files:LocalFilesViewModel)=
    ArchiveIdentity.parse(entry.name,(files.trail.map{it.name}+entry.name).joinToString("/"))

@Composable internal fun ArchiveDirectory(files:LocalFilesViewModel,player:LibraryViewModel) {
    var filter by rememberSaveable{mutableStateOf("全部")}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    val videos=files.entries.filter{it.video&&!it.directory}.map{it to localArchiveIdentity(it,files)}
    val shown=videos.filter{filter=="全部"||it.second.category==filter}
    val groups=shown.filter{it.second.category!="电影"}.groupBy{it.second.title to it.second.category}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("全部","电影","剧集","综艺").forEach{value->FilterChip(filter==value,{filter=value},label={Text(value)})}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("${files.entries.size} 个项目",Modifier.weight(1f),fontSize=13.sp,color=Muted);TextButton(onClick={files.refresh()}){Text("刷新")}}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(filter=="全部")items(files.entries.filter{it.directory||!it.video},key={it.uri.toString()}){entry->
                MediaRow(entry.name,if(entry.directory)"文件夹"else"非视频文件",if(entry.directory)Icons.Rounded.Folder else Icons.Rounded.Description,selected=false,seal=if(entry.directory)({player.sealLocal(entry,files)})else null){if(entry.directory)files.enter(entry)else files.error="请选择视频文件"}
            }
            if(shown.isEmpty()&&files.entries.none{it.directory}&&!files.busy)item{Text("这个分类还没有影片",Modifier.padding(24.dp),color=Muted)}
            groups.forEach { (group,pairs)->
                val members=pairs.map{it.first};val latest=player.history.firstOrNull{h->members.any{it.uri.toString()==h.media.key&&(h.media.sizeBytes==null||it.size<=0||h.media.sizeBytes==it.size)}}
                val chosen=latest?.let{h->val i=members.indexOfFirst{it.uri.toString()==h.media.key};if(h.completed)members.getOrNull(i+1) else members.getOrNull(i)} ?: members.first()
                val progress=latest?.takeIf{it.media.key==chosen.uri.toString()&&!it.completed}
                val info=pairs.first{it.first==chosen}.second
                item(key="summary:${group.first}:${group.second}") {
                    Surface(color=GroupedSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp)) {
                        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            val ref=MediaRef(chosen.uri.toString(),chosen.name,files.active?.name?:"本机文件",localUri=chosen.uri.toString(),sizeBytes=chosen.size)
                            MediaThumbnail(ref,Modifier.width(82.dp).height(110.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(group.first,fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                                Text("${members.size} ${if(group.second=="综艺")"期" else "集"}",fontSize=13.sp,color=Muted)
                                Button(onClick={player.playLocal(chosen.uri,siblings=members,privatePlayback=files.privateBrowse)},modifier=Modifier.heightIn(min=48.dp).testTag("series-resume"),contentPadding=PaddingValues(horizontal=12.dp),shape=RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Rounded.PlayArrow,null,Modifier.size(20.dp));Spacer(Modifier.width(4.dp))
                                    Text((if(progress!=null)"继续" else "播放")+(info.episode?.let{"第 $it ${if(group.second=="综艺")"期"else"集"}"}?:"观看")+(progress?.let{" · ${playbackTime(it.resumeMs)}"}?:""),fontSize=13.sp)
                                }
                            }
                        }
                    }
                }
                pairs.groupBy{it.second.season}.toSortedMap(compareBy<Int?>{it?:Int.MAX_VALUE}).forEach{(season,episodes)->
                    item(key="season:${group.first}:${group.second}:${season}"){Text(season?.let{"第 $it 季"}?:"剧集",Modifier.padding(horizontal=24.dp,vertical=10.dp),fontSize=16.sp,fontWeight=FontWeight.SemiBold)}
                    items(episodes,key={it.first.uri.toString()}){(entry,id)->
                        val h=player.history.firstOrNull{it.media.key==entry.uri.toString()}
                        Column(Modifier.background(if(h!=null&&!h.completed)Berry.copy(alpha=.06f)else androidx.compose.ui.graphics.Color.Transparent)) {
                            MediaRow(id.episode?.let{"第 $it ${if(id.category=="综艺")"期"else"集"}"}?:mediaDisplayTitle(entry.name),entry.name+(h?.let{if(it.completed)" · 已看完"else" · 上次看到 ${playbackTime(it.positionMs)}"}?:""),Icons.Rounded.PlayCircle,selected=selected==entry.uri.toString(),busy=player.preparingKey==entry.uri.toString(),seal={player.sealLocal(entry,files)}) { selected=entry.uri.toString();player.playLocal(entry.uri,siblings=members,privatePlayback=files.privateBrowse) }
                        }
                    }
                }
            }
            items(shown.filter{it.second.category=="电影"},key={it.first.uri.toString()}){(entry,_)->
                MediaRow(entry.name,formatSize(entry.size),Icons.Rounded.PlayCircle,selected=selected==entry.uri.toString(),busy=player.preparingKey==entry.uri.toString()) {selected=entry.uri.toString();player.playLocal(entry.uri,siblings=files.entries,privatePlayback=files.privateBrowse)}
            }
        }
    }
}
