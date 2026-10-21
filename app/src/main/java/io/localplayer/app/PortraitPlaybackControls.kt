package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun PlayerRailAction(icon:ImageVector,label:String,description:String=label,enabled:Boolean=true,click:()->Unit){
    Surface(onClick=click,enabled=enabled,color=Color.Transparent,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().heightIn(min=58.dp).semantics{contentDescription=description}){
        Column(Modifier.padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)){
            Icon(icon,null,Modifier.size(24.dp),tint=Color.White.copy(alpha=if(enabled)1f else .4f))
            Text(label,color=Color.White.copy(alpha=if(enabled)1f else .4f),fontSize=12.sp,lineHeight=16.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Clip)
        }
    }
}

@Composable internal fun PlaybackSpeedPanel(current:Float,dismiss:()->Unit,select:(Float)->Unit){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        val view=LocalView.current
        SideEffect{(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let{window->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
            androidx.core.view.WindowInsetsControllerCompat(window,view).apply{
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }}
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
            val width=minOf(maxWidth-24.dp,320.dp).coerceAtLeast(1.dp)
            Surface(modifier=Modifier.align(Alignment.CenterEnd).padding(12.dp).width(width).heightIn(max=maxHeight*.85f).testTag("playback-speed-panel"),color=Color(0xFF202126),contentColor=Color.White,shape=RoundedCornerShape(22.dp)){
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){Text("播放速度",Modifier.weight(1f),fontSize=20.sp);IconButton(onClick=dismiss){Icon(Icons.Rounded.Close,"关闭倍速面板")}}
                    listOf(.25f,.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f,3.5f,4f).chunked(3).forEach{row->
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            row.forEach{value->
                                TextButton(onClick={select(value)},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("speed-option-$value"),colors=ButtonDefaults.textButtonColors(containerColor=if(value==current)Color(0xFF5C3443)else Color.Transparent,contentColor=Color(0xFFFFB4C8))){Text("${value}×",maxLines=1)}
                            }
                        }
                    }
                }
            }
        }
    }
}
