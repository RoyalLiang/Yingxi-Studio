package io.localplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
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
@Composable fun PlaybackOptions(controller:MediaController,onDismiss:()->Unit,onExternal:()->Unit,onAudioExternal:()->Unit={},trackType:Int?=null){
    val context=LocalContext.current
    val prefs=remember(context){PlaybackPreferences(context)}
    val media=controller.currentMediaItem?.mediaMetadata?.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}
    val onlineAllowed=media!=null&&!SealedMedia.protects(media)

    var tracks by remember(controller){mutableStateOf(controller.currentTracks)}
    var currentItem by remember(controller){mutableStateOf(controller.currentMediaItem)}
    var parameters by remember(controller){mutableStateOf(controller.trackSelectionParameters)}
    DisposableEffect(controller){
        val listener=object:Player.Listener{
            override fun onTracksChanged(value:Tracks){tracks=value}
            override fun onMediaItemTransition(item:MediaItem?,reason:Int){currentItem=item}
            override fun onTimelineChanged(timeline:Timeline,reason:Int){currentItem=controller.currentMediaItem}
            override fun onTrackSelectionParametersChanged(value:TrackSelectionParameters){parameters=value}
        }
        controller.addListener(listener);onDispose{controller.removeListener(listener)}
    }
    PlaybackSheet(when(trackType){C.TRACK_TYPE_AUDIO->"音轨";C.TRACK_TYPE_TEXT->"字幕";C.TRACK_TYPE_VIDEO->"画质";else->"播放选项"},"audio-subtitle-panel",onDismiss,closeLabel="关闭播放选项"){
        LazyColumn(modifier=Modifier.weight(1f,fill=false).testTag("playback-settings-list"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){

                if(trackType==null||trackType==C.TRACK_TYPE_VIDEO)item{PlaybackQualityChoices(controller,currentItem,tracks,parameters)}
                if(trackType==null||trackType==C.TRACK_TYPE_AUDIO){
                if(trackType==null)item{Text("音轨",fontSize=17.sp,fontWeight=FontWeight.Medium)}
                val audio=tracks.groups.filter{it.type==C.TRACK_TYPE_AUDIO}
                val externalAudio=currentItem?.let{SidecarAudio.from(it)}.orEmpty()
                val audioAutomatic=parameters.overrides.keys.none{it.type==C.TRACK_TYPE_AUDIO}
                if(audio.isEmpty())item{Text("影片没有可用音轨",fontSize=14.sp,color=Color(0xFFB9B9BF))}
                else{
                    item{TrackChoice("自动选择",audioAutomatic){prefs.rememberedAudioLanguage=null;controller.trackSelectionParameters=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(prefs.audioLanguage).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).build()}}
                    audio.forEachIndexed{g,group->for(i in 0 until group.length){
                        item(key="audio-$g-$i"){TrackChoice(trackLabel(group.getTrackFormat(i),group.mediaTrackGroup.id.takeIf{it.contains(':')}?.substringBefore(':')?.toIntOrNull()?.takeIf{it>0}?.let{externalAudio.getOrNull(it-1)?.name}?:"音轨 ${audio.take(g).sumOf{it.length}+i+1}"),!audioAutomatic&&group.isTrackSelected(i),enabled=group.isTrackSupported(i)){
                            if(prefs.rememberManual)prefs.rememberedAudioLanguage=group.getTrackFormat(i).language
                            controller.trackSelectionParameters=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,i)).build()
                        }}
                    }}
                }
                item{HorizontalDivider(color=PlayerInk.copy(alpha=.08f));TextButton(onClick={onDismiss();onAudioExternal()},colors=ButtonDefaults.textButtonColors(contentColor=PlayerAccent),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Rounded.InsertDriveFile,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("载入音轨文件")}}
                if(currentItem?.let{SidecarAudio.from(it).isNotEmpty()}==true)item{OutlinedButton(onClick={
                    controller.currentMediaItem?.let{controller.replaceMediaItem(controller.currentMediaItemIndex,SidecarAudio.attach(it,emptyList()))}
                },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("移除外挂音轨")}}
                }
                if(trackType==null||trackType==C.TRACK_TYPE_TEXT){
                if(trackType==null)item{HorizontalDivider(Modifier.padding(vertical=8.dp),color=Color(0xFF404148))}
                if(trackType==null)item{Text("字幕",fontSize=17.sp,fontWeight=FontWeight.Medium)}
                val disabled=parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                val textAutomatic=!disabled&&parameters.overrides.keys.none{it.type==C.TRACK_TYPE_TEXT}
                item{TrackChoice("自动选择",textAutomatic){if(prefs.rememberManual)prefs.subtitlesDisabled=false;controller.trackSelectionParameters=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setPreferredTextLanguage(prefs.textLanguage).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build()}}
                item{TrackChoice("关闭字幕",disabled){if(prefs.rememberManual)prefs.subtitlesDisabled=true;controller.trackSelectionParameters=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()}}
                val texts=tracks.groups.filter{it.type==C.TRACK_TYPE_TEXT}
                texts.forEachIndexed{g,group->for(i in 0 until group.length){
                    item(key="text-$g-$i"){TrackChoice(trackLabel(group.getTrackFormat(i),"字幕 ${texts.take(g).sumOf{it.length}+i+1}"),!disabled&&!textAutomatic&&group.isTrackSelected(i),enabled=group.isTrackSupported(i)){
                        if(prefs.rememberManual)prefs.subtitlesDisabled=false;if(prefs.rememberManual)prefs.textLanguage=group.getTrackFormat(i).language
                        controller.trackSelectionParameters=parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,i)).build()
                    }}
                }}
                item{
                    val found by OnlineSubtitles.resources.collectAsState()
                    found?.takeIf{it.first==controller.currentMediaItem?.mediaId&&onlineAllowed}?.second?.filter{it.id=="custom"}?.take(1)?.forEach{resource->
                        OutlinedButton(onClick={runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(resource.page)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{android.widget.Toast.makeText(context,"没有可打开字幕驿站的浏览器",android.widget.Toast.LENGTH_LONG).show()}},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
                            Text("前往自定义字幕驿站",maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                item{HorizontalDivider(color=PlayerInk.copy(alpha=.08f));TextButton(onClick={onDismiss();onExternal()},colors=ButtonDefaults.textButtonColors(contentColor=PlayerAccent),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Rounded.InsertDriveFile,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("载入字幕文件")}}
                }
            }
    }
}

@Composable fun TrackChoice(label:String,selected:Boolean,modifier:Modifier=Modifier,enabled:Boolean=true,choose:()->Unit){
    Row(modifier.fillMaxWidth().background(if(selected)PlayerInk.copy(alpha=.035f)else Color.Transparent,RoundedCornerShape(12.dp))
        .selectable(selected=selected,enabled=enabled,role=Role.RadioButton,onClick=choose).heightIn(min=52.dp).padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,Modifier.weight(1f).padding(end=12.dp),fontSize=15.sp,lineHeight=21.sp,maxLines=3,overflow=TextOverflow.Ellipsis,color=if(!enabled)PlayerInk.copy(alpha=.45f)else if(selected)PlayerAccent else PlayerInk)
        RadioButton(selected=selected,onClick=null,enabled=enabled,colors=RadioButtonDefaults.colors(selectedColor=PlayerAccent,unselectedColor=PlayerInk.copy(alpha=.45f)),modifier=Modifier.size(20.dp))
        if(!enabled)Text("不支持",fontSize=12.sp,color=PlayerInk.copy(alpha=.45f))
    }
}

fun speedLabel(value:Float)=if(value==value.toInt().toFloat())"${value.toInt()}×"else"${value}×"
private fun trackLabel(format:Format,fallback:String):String{
    val language=normalizedTrackLanguage(format.language)?:sidecarLanguage(format.label.orEmpty())?:sidecarLanguage(fallback)
    val audio=format.sampleMimeType?.startsWith("audio/")==true
    val name=when(language){"yue"->"粤语";"cmn"->"普通话";"zh-Hans"->if(audio)"中文"else"简体中文";"zh-Hant"->if(audio)"中文"else"繁体中文";"mul"->"多语种"
        null->"未标注语种";else->Locale.forLanguageTag(language).getDisplayName(Locale.SIMPLIFIED_CHINESE)}
    val label=format.label?.trim()?.take(240)?.takeIf{it.isNotEmpty()&&!Regex("(?i)(?:(?:audio|subtitle|track|音轨|字幕)\\s*\\d+|(?:sound|audio|subtitle|text)handler)").matches(it)}
    val codec=format.sampleMimeType?.takeUnless{it.contains("media3-cues")}
        ?.substringAfter('/')?.replace("mp4a-latm","AAC")?.uppercase(Locale.ROOT)
    val role=when{format.roleFlags and C.ROLE_FLAG_COMMENTARY!=0->"解说";format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO!=0->"口述影像"
        format.selectionFlags and C.SELECTION_FLAG_FORCED!=0->"强制字幕";else->null}
    return listOfNotNull(name,label?.takeUnless{it==name||it==fallback},role,codec,fallback).distinct().joinToString(" · ")
}
