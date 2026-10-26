package io.localplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem

@Composable fun PlaybackQueueSheet(items:List<MediaItem>,current:Int,truncated:Boolean,dismiss:()->Unit,select:(Int)->Unit){
    val state=rememberLazyListState(initialFirstVisibleItemIndex=current.coerceIn(0,(items.size-1).coerceAtLeast(0)))
    LaunchedEffect(current,items.size){if(items.isNotEmpty())state.scrollToItem(current.coerceIn(0,items.lastIndex))}
    PlaybackSheet("选集","playback-queue-drawer",dismiss){
        Text(if(truncated)"${items.size} 集 · 部分选集"else"${items.size} 集 · 连续播放",Modifier.padding(horizontal=20.dp,vertical=4.dp),fontSize=12.sp,color=PlayerInk.copy(alpha=.65f))
        LazyColumn(state=state,modifier=Modifier.fillMaxWidth().weight(1f,fill=false),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=8.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            itemsIndexed(items,key={index,item->"$index:${item.mediaId}"}){index,item->
                val selected=index==current
                Row(Modifier.fillMaxWidth().background(if(selected)PlayerAccent.copy(alpha=.14f)else Color.Transparent,RoundedCornerShape(16.dp))
                    .selectable(selected=selected,role=Role.RadioButton,onClick={if(selected)dismiss()else select(index)}).heightIn(min=64.dp).padding(12.dp).testTag("queue-item-$index"),verticalAlignment=Alignment.CenterVertically){
                    if(selected)Icon(Icons.Rounded.GraphicEq,"当前集",Modifier.width(24.dp),tint=PlayerAccent)
                    else Text("${index+1}",Modifier.width(24.dp),fontSize=13.sp,color=PlayerInk.copy(alpha=.6f))
                    Text(playbackItemTitle(item,"第 ${index+1} 集"),Modifier.weight(1f).padding(start=12.dp),fontSize=15.sp,lineHeight=22.sp,
                        color=if(selected)PlayerAccent else PlayerInk,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}
