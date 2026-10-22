package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun VersionSettingsPage(vm:SettingsViewModel,about:Boolean,back:()->Unit,openAbout:()->Unit,install:()->Unit) {
    val notificationPermission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){vm.download()}
    val contextForDownload=LocalContext.current
    val download={if(androidx.core.content.ContextCompat.checkSelfPermission(contextForDownload,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)vm.download()else notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
    var letter by rememberSaveable { mutableStateOf<String?>(null) }
    var world by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var seenRevision by rememberSaveable { mutableIntStateOf(0) }
    val language=LocalConfiguration.current.locales[0].language
    val context=LocalContext.current
    LaunchedEffect(vm.checkRevision) {
        if(vm.checkRevision!=seenRevision&&vm.release!=null) { seenRevision=vm.checkRevision;letter="available" }
    }
    if(world) {
        BackHandler { world=false }
        StationWorldPage { world=false }
        return
    }
    Scaffold(
        modifier=Modifier.fillMaxSize().testTag("settings-page"),containerColor=Paper,
        contentWindowInsets=WindowInsets.safeDrawing,
        bottomBar={UpdateFooter(vm,install,download){letter="available"}}
    ) { contentPadding ->
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回")}
            Text(if(about)"信笺" else "站务",Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            if(about)Box {
                Box {
                val interaction=remember{MutableInteractionSource()}
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                val tint by animateColorAsState(if(more||hovered||pressed)Berry.copy(alpha=.14f)else GroupedSurface,label="version-more-color")
                FilledTonalIconButton(onClick={more=!more},interactionSource=interaction,colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=tint,contentColor=Berry),
                    modifier=Modifier.testTag("version-more").hoverable(interaction).graphicsLayer{scaleX=if(pressed).94f else 1f;scaleY=scaleX}){Icon(Icons.Rounded.MoreVert,"信笺更多操作")}
                DropdownMenu(more,{more=false}) {
                    DropdownMenuItem(text={Text("阅读本版信笺")},onClick={more=false;letter="installed"})
                DropdownMenuItem(text={Text("前往发布页")},onClick={more=false;runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,AppUpdates.RELEASES_PAGE.toUri()))}})
                }
            }
            }
        }
        if(!about)LazyColumn(Modifier.weight(1f).testTag("settings-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Surface(onClick=openAbout,shape=RoundedCornerShape(22.dp),color=GroupedSurface,modifier=Modifier.fillMaxWidth().testTag("app-version")) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info,null,tint=Berry)
                    Column(Modifier.weight(1f).padding(horizontal=16.dp)) { Text("映汐放映站 · 信笺",fontSize=16.sp,fontWeight=FontWeight.Medium,color=Ink);Text(vm.version.name,fontSize=14.sp,color=Muted) }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
                }
            } }
            item { Surface(color=GroupedSurface,shape=RoundedCornerShape(22.dp)) { StationCharacterRow("keeper","迟灯 · 设备修理师","先把设备调顺，今晚安心看。") } }
            item { StorageUsageSettings() }
            item { StationAudioSettings() }
            item { EnrichmentSettings()
            CloudPlaybackSettings() }
            item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { StationAtmosphereSettings() } }
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if(maxHeight<420.dp)Row(Modifier.fillMaxSize().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.widthIn(max=290.dp).fillMaxWidth(.38f).fillMaxHeight().verticalScroll(rememberScrollState())) { VersionCounter(vm,install,{world=true},{letter="available"},compact=true) }
                CurrentLetter(language,{letter="installed"},Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize().padding(horizontal=24.dp)) {
                VersionCounter(vm,install,{world=true},{letter="available"})
                CurrentLetter(language,{letter="installed"},Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    }
    if(letter!=null) {
        val available=vm.release
        val remote=letter=="available"&&available!=null
        val shown=if(remote)available!!.version else vm.version
        val body=if(remote)available!!.notes else BuildConfig.VERSION_NOTES
        AlertDialog(onDismissRequest={letter=null},shape=RoundedCornerShape(26.dp),containerColor=Paper,modifier=Modifier.testTag("release-letter"),
            title={Column { Text(if(remote)"可用更新" else "放映站来信",fontSize=17.sp,color=Muted);Text(shown.name,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.testTag("letter-version")) }},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).testTag("release-letter-body")) { ReleaseLetterContent(body,language) }},
            confirmButton={if(remote&&!vm.busy)TextButton(onClick={letter=null;if(vm.ready!=null)install() else download()},modifier=Modifier.testTag("confirm-update")){Text(if(vm.ready!=null)"安装更新" else "下载更新")} else TextButton(onClick={letter=null}){Text("收好信笺")}},
            dismissButton={if(remote)TextButton(onClick={letter=null}){Text("稍后")}})
    }
}

@Composable private fun VersionCounter(vm:SettingsViewModel,install:()->Unit,world:()->Unit,available:()->Unit,compact:Boolean=false) {
    Column(Modifier.fillMaxWidth().testTag("version-counter").padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("当前版本 "+vm.version.name,fontSize=if(compact)18.sp else 22.sp,lineHeight=if(compact)25.sp else 31.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.testTag("installed-version"))
        Text("安装序号 "+vm.version.code,fontSize=12.sp,color=Muted)
        TextButton(onClick=world,modifier=Modifier.testTag("open-world"),contentPadding=PaddingValues(vertical=4.dp)){
            Icon(Icons.AutoMirrored.Rounded.Article,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(compact)"档案室"else"档案室 · 人物与来信",fontSize=13.sp)
        }
    }
}

@Composable private fun UpdateFooter(vm:SettingsViewModel,install:()->Unit,download:()->Unit,available:()->Unit) {
    var details by remember{mutableStateOf(false)}
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("更新提示")},text={Text(vm.message.orEmpty(),Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={details=false}){Text("关闭")}})
    Column(Modifier.fillMaxWidth().background(Paper).testTag("update-footer")) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))) {
        Column(Modifier.padding(horizontal=24.dp,vertical=12.dp).testTag("update-actions"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            vm.release?.let{release->TextButton(onClick=available,contentPadding=PaddingValues(0.dp)){
                Text("可用更新 ",color=Berry,fontSize=14.sp);Text(release.version.name,color=Berry,fontSize=14.sp,modifier=Modifier.testTag("available-version"))}}
            vm.message?.let{Text(it,fontSize=12.sp,lineHeight=18.sp,color=if(vm.error)MaterialTheme.colorScheme.error else Muted,modifier=Modifier.testTag("update-status").clickable(enabled=vm.error){details=true},maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
            if(vm.busy) {
                vm.progress?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth())}?:LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(if(vm.progress==null)"正在检查更新"else"正在下载 ${(vm.progress!!*100).toInt()}%",Modifier.weight(1f),fontSize=13.sp,color=Muted)
                    TextButton(onClick=vm::cancel){Text(if(vm.progress==null)"取消"else"暂停")}
                }
            } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick=vm::check,colors=ButtonDefaults.buttonColors(containerColor=Berry,contentColor=GroupedSurface),border=androidx.compose.foundation.BorderStroke(1.dp,GroupedSurface.copy(alpha=.2f)),elevation=ButtonDefaults.buttonElevation(defaultElevation=2.dp,pressedElevation=4.dp),modifier=Modifier.weight(1f).heightIn(min=50.dp).testTag("check-updates"),shape=RoundedCornerShape(16.dp)){Text("检查更新",maxLines=1)}
                if(vm.release!=null)Button(onClick={if(vm.ready!=null)install()else download()},contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.weight(1f).heightIn(min=50.dp).testTag("download-update"),shape=RoundedCornerShape(16.dp)){
                    Text(if(vm.ready!=null)"安装更新"else if((vm.progress?:0f)>0f)"继续下载"else"下载更新",maxLines=1)}
            }
        }
    }
    }
}

@Composable private fun CurrentLetter(language:String,expand:()->Unit,modifier:Modifier) {
    Column(modifier) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("current-letter-scroll").padding(bottom=24.dp)) {
            ReleaseLetterContent(BuildConfig.VERSION_NOTES,language)
        }
    }
}
