package io.localplayer.app

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun SavedSourceRow(source:Source,pinned:Boolean,open:()->Unit,edit:()->Unit,pin:()->Unit,remove:()->Unit,privateAccess:()->Unit,trailing:@Composable RowScope.()->Unit={}){
    val density=LocalDensity.current
    val leftLimit=with(density){96.dp.toPx()}
    val rightLimit=with(density){144.dp.toPx()}
    var offset by remember(source.id){mutableFloatStateOf(0f)}
    val scope=rememberCoroutineScope()
    var settling by remember{mutableStateOf<Job?>(null)}
    val settle:(Float)->Unit={target->settling?.cancel();settling=scope.launch{animate(offset,target,animationSpec=tween(180)){value,_->offset=value}}}
    val collapse={settle(0f)}
    Box(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(20.dp)).testTag("source-row:${source.id}")){
        if(offset>0f)Button(onClick={collapse();privateAccess()},modifier=Modifier.align(Alignment.CenterStart).width(96.dp).fillMaxHeight(),
            shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(4.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF414655))){
            Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.Fingerprint,null);Spacer(Modifier.height(3.dp));Text("私密访问",fontSize=12.sp,maxLines=1)}
        }
        if(offset<0f)Row(Modifier.align(Alignment.CenterEnd).width(144.dp).fillMaxHeight()){
            Button(onClick={collapse();pin()},modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(2.dp),
                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF73604A))){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(if(pinned)Icons.Rounded.PushPin else Icons.Rounded.VerticalAlignTop,null);Spacer(Modifier.height(3.dp));Text(if(pinned)"取消置顶"else"置顶",fontSize=12.sp,maxLines=1)}
            }
            Button(onClick={collapse();remove()},modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(2.dp),
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.DeleteOutline,null);Spacer(Modifier.height(3.dp));Text("删除",fontSize=12.sp)}
            }
        }
        Surface(onClick={if(offset!=0f)collapse()else open()},color=GroupedSurface,shape=RoundedCornerShape(20.dp),
            modifier=Modifier.fillMaxWidth().offset{IntOffset(offset.roundToInt(),0)}
                .semantics{customActions=listOf(
                    CustomAccessibilityAction(if(pinned)"取消置顶"else"置顶"){pin();true},
                    CustomAccessibilityAction("删除来源"){remove();true},
                    CustomAccessibilityAction("私密访问"){privateAccess();true})}
                .draggable(orientation=Orientation.Horizontal,state=rememberDraggableState{delta->offset=(offset+delta).coerceIn(-rightLimit,leftLimit)},
                    onDragStarted={settling?.cancel()},onDragStopped={velocity->
                        settle(when{offset>leftLimit*.35f||velocity>800f->leftLimit;offset< -rightLimit*.3f||velocity< -800f->-rightLimit;else->0f})
                    })){
            Row(Modifier.heightIn(min=76.dp).padding(start=14.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.size(40.dp).background(Berry.copy(alpha=.07f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.Dns,null,tint=Berry,modifier=Modifier.size(22.dp))}
                Column(Modifier.weight(1f).padding(start=12.dp,end=6.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text(source.label,Modifier.weight(1f,false),fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(pinned)Icon(Icons.Rounded.PushPin,"已置顶",Modifier.padding(start=6.dp).size(13.dp),tint=Berry)
                    }
                    Spacer(Modifier.height(3.dp));Text("${source.protocol} · ${source.host} · ${source.root}",fontSize=12.sp,lineHeight=18.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconButton(onClick={collapse();edit()}){Icon(Icons.Rounded.Tune,"修改配置 ${source.label}",tint=Muted,modifier=Modifier.size(21.dp))}
            trailing()
            }
        }
    }
}
