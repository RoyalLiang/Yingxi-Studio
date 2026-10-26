package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Portrait actions stay within thumb reach; track icons live beside the picture. */
@Composable internal fun PortraitPlaybackToolbar(
    position:Long,duration:Long,speed:Float,index:Int,count:Int,playRequested:Boolean,enabled:Boolean,
    onPlay:()->Unit,onSpeed:()->Unit,onQueue:()->Unit,onPrevious:()->Unit,onNext:()->Unit,onRotate:()->Unit
){
    Column(Modifier.fillMaxWidth().testTag("portrait-playback-controls")){
        Row(Modifier.fillMaxWidth().testTag("playback-toolbar"),verticalAlignment=Alignment.CenterVertically){
            PlayerIcon(if(playRequested)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playRequested)"暂停"else"播放",onPlay,enabled)
            Text("${playbackTime(position)} / ${playbackTime(duration)}",Modifier.weight(1f).testTag("playback-time"),color=PlayerInk,fontSize=11.sp,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
            TextButton(onClick=onSpeed,enabled=enabled,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.heightIn(min=48.dp).testTag("playback-speed").semantics{contentDescription="播放速度"}){
                Text(speedLabel(speed),color=PlayerInk,fontSize=13.sp,maxLines=1,softWrap=false)
            }
            PlayerIcon(Icons.Rounded.Fullscreen,"旋转屏幕",onRotate)
        }
        if(count>1)Row(Modifier.fillMaxWidth().testTag("portrait-episode-bar"),verticalAlignment=Alignment.CenterVertically){
            PlayerIcon(Icons.Rounded.SkipPrevious,"上一集",onPrevious,enabled&&index>0)
            TextButton(onClick=onQueue,modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics{contentDescription="选集"},contentPadding=PaddingValues(horizontal=8.dp)){
                Icon(Icons.Rounded.PlaylistPlay,null,Modifier.size(20.dp),tint=PlayerInk)
                Spacer(Modifier.width(8.dp))
                Text("选集 ${index+1}/$count",color=PlayerInk,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp));Icon(Icons.Rounded.KeyboardArrowUp,null,Modifier.size(16.dp),tint=PlayerInk.copy(alpha=.6f))
            }
            PlayerIcon(Icons.Rounded.SkipNext,"下一集",onNext,enabled&&index<count-1)
        }
    }
}

@Composable internal fun PlaybackSpeedPanel(current:Float,dismiss:()->Unit,select:(Float)->Unit){
    PlaybackSheet("播放速度","playback-speed-panel",dismiss,closeLabel="关闭倍速面板"){
        Column(Modifier.fillMaxWidth().weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(.25f,.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f,3.5f,4f).chunked(3).forEach{row->
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    row.forEach{value->
                        TextButton(onClick={select(value)},contentPadding=PaddingValues(4.dp),shape=RoundedCornerShape(14.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("speed-option-$value"),
                            colors=ButtonDefaults.textButtonColors(containerColor=if(value==current)PlayerAccent.copy(alpha=.18f)else Color.Transparent,contentColor=if(value==current)PlayerAccent else PlayerInk)){
                            Text(speedLabel(value),fontSize=15.sp,maxLines=1,softWrap=false)
                        }
                    }
                }
            }
        }
    }
}
