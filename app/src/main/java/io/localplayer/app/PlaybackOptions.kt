package io.localplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.session.MediaController
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun PlaybackOptions(controller:MediaController,onDismiss:()->Unit,onExternal:()->Unit){
    val context=LocalContext.current
    val prefs=remember(context){PlaybackPreferences(context)}
    var tracks by remember(controller){mutableStateOf(controller.currentTracks)}
    var parameters by remember(controller){mutableStateOf(controller.trackSelectionParameters)}
    DisposableEffect(controller){
        val listener=object:Player.Listener{
            override fun onTracksChanged(value:Tracks){tracks=value}
            override fun onTrackSelectionParametersChanged(value:TrackSelectionParameters){parameters=value}
        }
        controller.addListener(listener);onDispose{controller.removeListener(listener)}
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFFFA1BC),surface=Color(0xFF202126),onSurface=Paper,onPrimary=Color(0xFF53102A),secondaryContainer=Color(0xFF67273B),onSecondaryContainer=Paper)){
        Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
            val view=androidx.compose.ui.platform.LocalView.current
            SideEffect {
                (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                    androidx.core.view.WindowInsetsControllerCompat(window,view).apply {
                        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val panelWidth=if(maxWidth>maxHeight)(maxWidth*.42f).coerceIn(300.dp,380.dp)else minOf(maxWidth*.92f,380.dp)
                Surface(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(panelWidth).heightIn(max=maxHeight*.9f).testTag("audio-subtitle-panel"),
                    shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,contentColor=Paper) {
            LazyColumn(modifier=Modifier.testTag("playback-settings-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("音轨与字幕",Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"关闭音轨与字幕")}
                } }
                item{Text("音轨",fontSize=17.sp,fontWeight=FontWeight.Medium)}
                val audio=tracks.groups.filter{it.type==C.TRACK_TYPE_AUDIO}
                val audioAutomatic=parameters.overrides.keys.none{it.type==C.TRACK_TYPE_AUDIO}
                if(audio.isEmpty())item{Text("影片没有可用音轨",fontSize=14.sp,color=Color(0xFFB9B9BF))}
                else{
                    item{TrackChoice("自动选择",audioAutomatic){prefs.audioLanguage=null;controller.trackSelectionParameters=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(null).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).build()}}
                    audio.forEachIndexed{g,group->for(i in 0 until group.length){
                        item(key="audio-$g-$i"){TrackChoice(trackLabel(group.getTrackFormat(i),"音轨 ${g+1}"),!audioAutomatic&&group.isTrackSelected(i),group.isTrackSupported(i)){
                            prefs.audioLanguage=group.getTrackFormat(i).language
                            controller.trackSelectionParameters=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,i)).build()
                        }}
                    }}
                }
                item{HorizontalDivider(Modifier.padding(vertical=8.dp),color=Color(0xFF404148))}
                item{Text("字幕",fontSize=17.sp,fontWeight=FontWeight.Medium)}
                val disabled=parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                val textAutomatic=!disabled&&parameters.overrides.keys.none{it.type==C.TRACK_TYPE_TEXT}
                item{TrackChoice("关闭字幕",disabled){prefs.subtitlesDisabled=true;controller.trackSelectionParameters=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()}}
                item{TrackChoice("自动选择",textAutomatic){prefs.subtitlesDisabled=false;controller.trackSelectionParameters=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setPreferredTextLanguage(prefs.textLanguage).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build()}}
                val texts=tracks.groups.filter{it.type==C.TRACK_TYPE_TEXT}
                texts.forEachIndexed{g,group->for(i in 0 until group.length){
                    item(key="text-$g-$i"){TrackChoice(trackLabel(group.getTrackFormat(i),"字幕 ${g+1}"),!disabled&&!textAutomatic&&group.isTrackSelected(i),group.isTrackSupported(i)){
                        prefs.subtitlesDisabled=false;prefs.textLanguage=group.getTrackFormat(i).language
                        controller.trackSelectionParameters=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,i)).build()
                    }}
                }}
                if(texts.isEmpty())item{Text("影片没有内嵌字幕，可以载入本机 SRT / VTT 文件。",fontSize=13.sp,lineHeight=20.sp,color=Color(0xFFB9B9BF))}
                item{OutlinedButton(onClick={onDismiss();onExternal()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("载入字幕文件")}}
            }
                }
            }
        }
    }
}

@Composable fun TrackChoice(label:String,selected:Boolean,enabled:Boolean=true,choose:()->Unit){
    Row(Modifier.fillMaxWidth().background(if(selected)Color(0xFF422C37)else Color(0xFF292A31),RoundedCornerShape(14.dp))
        .selectable(selected=selected,enabled=enabled,role=Role.RadioButton,onClick=choose).heightIn(min=52.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,Modifier.weight(1f).padding(end=12.dp),fontSize=15.sp,lineHeight=21.sp,maxLines=3,overflow=TextOverflow.Ellipsis,color=if(enabled)Paper else Color(0xFF9C9CA5))
        if(selected)Icon(Icons.Rounded.Check,null,tint=Color(0xFFFFB4C8),modifier=Modifier.size(20.dp))
        if(!enabled)Text("不支持",fontSize=12.sp,color=Color(0xFF9C9CA5))
    }
}
fun speedLabel(value:Float)=if(value==value.toInt().toFloat())"${value.toInt()}×"else"${value}×"
private fun trackLabel(format:Format,fallback:String):String{
    val name=format.label?.takeIf{it.isNotBlank()} ?: format.language?.takeIf{it!="und"}?.let{Locale.forLanguageTag(it).getDisplayName(Locale.SIMPLIFIED_CHINESE)} ?: "未标注语言"
    val codec=format.sampleMimeType?.takeUnless{it.contains("media3-cues")}
        ?.substringAfter('/')?.replace("mp4a-latm","AAC")?.uppercase(Locale.ROOT)
    return listOfNotNull(fallback,name,codec).joinToString(" · ")
}
