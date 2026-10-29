package io.localplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared playback geometry: bottom in portrait, side in landscape. */
@Composable internal fun PlaybackSheet(title:String,tag:String,dismiss:()->Unit,closeLabel:String="关闭$title",content:@Composable ColumnScope.()->Unit){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        val view=LocalView.current
        SideEffect{(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let{window->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
            androidx.core.view.WindowInsetsControllerCompat(window,view).apply{
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }}
        MaterialTheme(colorScheme=darkColorScheme(primary=PlayerAccent,onPrimary=Color(0xFF281820),surface=PlayerPanel,onSurface=PlayerInk,secondaryContainer=PlayerAccent.copy(alpha=.18f),onSecondaryContainer=PlayerInk)){
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                val portrait=maxHeight>maxWidth
                val backdrop=when(tag){"playback-queue-drawer"->"queue-dismiss-backdrop";"audio-subtitle-panel"->"settings-dismiss-backdrop";else->"$tag-dismiss-backdrop"}
                Box(Modifier.fillMaxSize().testTag(backdrop).clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=dismiss))
                val width=minOf(maxWidth-(if(portrait)0.dp else 24.dp),if(portrait)480.dp else 380.dp).coerceAtLeast(1.dp)
                Surface(Modifier.align(if(portrait)Alignment.BottomCenter else Alignment.CenterEnd).padding(if(portrait)0.dp else 12.dp).width(width)
                    .heightIn(max=maxHeight*(if(portrait).76f else .9f)).testTag(tag).pointerInput(Unit){detectTapGestures(onTap={})},
                    shape=RoundedCornerShape(topStart=22.dp,topEnd=22.dp,bottomStart=if(portrait)0.dp else 22.dp,bottomEnd=if(portrait)0.dp else 22.dp),color=PlayerPanel){
                    Column(Modifier.fillMaxWidth()){
                        if(portrait)Box(Modifier.align(Alignment.CenterHorizontally).padding(top=10.dp).size(32.dp,4.dp).background(PlayerInk.copy(alpha=.25f),RoundedCornerShape(2.dp)))
                        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold,color=PlayerInk)
                            IconButton(onClick=dismiss,modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.Close,closeLabel,tint=PlayerInk)}
                        }
                        content()
                    }
                }
            }
        }
    }
}
