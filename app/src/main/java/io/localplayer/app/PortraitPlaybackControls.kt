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

/** Native portrait controls: one quiet row, with full touch targets and no labels over the film. */
@Composable internal fun PortraitPlaybackToolbar(
    position:Long,duration:Long,speed:Float,fill:Boolean,hasQueue:Boolean,buffering:Boolean,playRequested:Boolean,onPlay:()->Unit,
    onSpeed:()->Unit,onAudio:()->Unit,onSubtitles:()->Unit,onQueue:()->Unit,onRotate:()->Unit
){
    Column(Modifier.fillMaxWidth().testTag("portrait-playback-controls")){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp).testTag("playback-time"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Text(playbackTime(position),color=PlayerInk,fontSize=12.sp,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
            if(buffering)PlayerIcon(if(playRequested)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playRequested)"暂停"else"播放",onPlay)
            Text(playbackTime(duration),color=PlayerInk.copy(alpha=.65f),fontSize=12.sp,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().testTag("playback-toolbar"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            TextButton(onClick=onSpeed,contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("playback-speed").semantics{contentDescription="播放速度"}){
                Text(speedLabel(speed),color=PlayerInk,fontSize=14.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){PlayerIcon(Icons.Rounded.Audiotrack,"音轨",onAudio,modifier=Modifier.testTag("player-audio"))}
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){PlayerIcon(Icons.Rounded.Subtitles,"字幕",onSubtitles,modifier=Modifier.testTag("player-subtitles"))}
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){PlayerIcon(Icons.Rounded.PlaylistPlay,"选集",onQueue,hasQueue)}
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){PlayerIcon(Icons.Rounded.ScreenRotation,"旋转屏幕",onRotate)}
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
