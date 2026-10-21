package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
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
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回")}
            Text(if(about)"版本" else "放映设置",Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            if(about)Box {
                IconButton(onClick={more=true},modifier=Modifier.testTag("version-more")){Icon(Icons.Rounded.MoreVert,"版本更多操作")}
                DropdownMenu(more,{more=false}) {
                    DropdownMenuItem(text={Text("前往发布页")},onClick={more=false;runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,AppUpdates.RELEASES_PAGE.toUri()))}})
                }
            }
        }
        if(!about)LazyColumn(Modifier.weight(1f).testTag("settings-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Surface(onClick=openAbout,shape=RoundedCornerShape(22.dp),color=GroupedSurface,modifier=Modifier.fillMaxWidth().testTag("app-version")) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info,null,tint=Berry)
                    Column(Modifier.weight(1f).padding(horizontal=16.dp)) { Text("映汐放映室 · 版本",fontSize=16.sp,fontWeight=FontWeight.Medium,color=Ink);Text(vm.version.name,fontSize=14.sp,color=Muted) }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
                }
            } }
            item { Surface(color=GroupedSurface,shape=RoundedCornerShape(22.dp)) { StationCharacterRow("keeper","迟灯 · 设备修理师","先把设备调顺，今晚安心看。") } }
            item { StationAudioSettings() }
            item { EnrichmentSettings() }
            item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { StationAtmosphereSettings() } }
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if(maxHeight<420.dp)Row(Modifier.fillMaxSize().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.widthIn(max=290.dp).fillMaxWidth(.38f)) { VersionCounter(vm,install,{world=true},{letter="available"},compact=true) }
                CurrentLetter(language,{letter="installed"},Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize().padding(horizontal=24.dp)) {
                VersionCounter(vm,install,{world=true},{letter="available"})
                CurrentLetter(language,{letter="installed"},Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    if(letter!=null) {
        val available=vm.release
        val remote=letter=="available"&&available!=null
        val shown=if(remote)available!!.version else vm.version
        val body=if(remote)available!!.notes else BuildConfig.VERSION_NOTES
        AlertDialog(onDismissRequest={letter=null},shape=RoundedCornerShape(26.dp),containerColor=Paper,modifier=Modifier.testTag("release-letter"),
            title={Column { Text(if(remote)"可用更新" else "本版说明",fontSize=17.sp,color=Muted);Text(shown.name,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.testTag("letter-version")) }},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).testTag("release-letter-body")) { ReleaseLetterContent(body,language) }},
            confirmButton={if(remote&&!vm.busy)TextButton(onClick={letter=null;if(vm.ready!=null)install() else vm.download()},modifier=Modifier.testTag("confirm-update")){Text(if(vm.ready!=null)"安装更新" else "下载更新")} else TextButton(onClick={letter=null}){Text("收好信笺")}},
            dismissButton={if(remote)TextButton(onClick={letter=null}){Text("稍后")}})
    }
}

@Composable private fun VersionCounter(vm:SettingsViewModel,install:()->Unit,world:()->Unit,available:()->Unit,compact:Boolean=false) {
    Column(Modifier.fillMaxWidth().testTag("version-counter"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("映汐放映室 "+vm.version.name,fontSize=if(compact)18.sp else 22.sp,lineHeight=if(compact)25.sp else 31.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.testTag("installed-version"))
        if(!compact)Text("安装序号 "+vm.version.code,fontSize=12.sp,color=Muted)
        Column(Modifier.fillMaxWidth().testTag("update-actions"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            vm.release?.let { release ->
                TextButton(onClick=available,contentPadding=PaddingValues(0.dp),) { Text("新信笺 · ",color=Berry,fontSize=14.sp);Text(release.version.name,color=Berry,fontSize=14.sp,modifier=Modifier.testTag("available-version")) }
            }
            vm.message?.let { Text(it,fontSize=12.sp,lineHeight=18.sp,color=if(vm.error)MaterialTheme.colorScheme.error else Muted,modifier=Modifier.testTag("update-status")) }
            if(vm.busy) {
                vm.progress?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth())} ?:LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(vm.progress==null)"正在检查更新" else "正在下载 ${(vm.progress!!*100).toInt()}%",Modifier.weight(1f),fontSize=13.sp,color=Muted)
                    TextButton(onClick=vm::cancel){Text("取消")}
                }
            } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(vm.release!=null)OutlinedButton(onClick=vm::check,contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.weight(1f).heightIn(min=50.dp).testTag("check-updates")){Text("检查更新",maxLines=1)}
                else Button(onClick=vm::check,modifier=Modifier.weight(1f).heightIn(min=50.dp).testTag("check-updates"),shape=RoundedCornerShape(16.dp)){Text("检查更新")}
                if(vm.release!=null)Button(onClick={if(vm.ready!=null)install() else vm.download()},contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.weight(1f).heightIn(min=50.dp).testTag("download-update"),shape=RoundedCornerShape(16.dp)){Text(if(vm.ready!=null)"安装更新" else "下载更新",maxLines=1)}
            }
        }
        TextButton(onClick=world,modifier=Modifier.testTag("open-world"),contentPadding=PaddingValues(vertical=4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Article,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(compact)"档案室" else "档案室 · 人物与世界图谱",fontSize=13.sp)
        }
    }
}

@Composable private fun CurrentLetter(language:String,expand:()->Unit,modifier:Modifier) {
    Column(modifier) {
        TextButton(onClick=expand,modifier=Modifier.testTag("installed-notes"),contentPadding=PaddingValues(vertical=8.dp)) {Text("本版说明",color=Muted,fontSize=13.sp)}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("current-letter-scroll").padding(bottom=24.dp)) {
            ReleaseLetterContent(BuildConfig.VERSION_NOTES,language)
        }
    }
}
