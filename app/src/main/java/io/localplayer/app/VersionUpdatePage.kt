package io.localplayer.app

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
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Info
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

@Composable internal fun VersionSettingsPage(vm:SettingsViewModel,about:Boolean,back:()->Unit,openAbout:()->Unit,install:()->Unit){
    var letter by rememberSaveable{mutableStateOf<String?>(null)}
    var seenRevision by rememberSaveable{mutableIntStateOf(0)}
    val language=LocalConfiguration.current.locales[0].language
    val context=LocalContext.current
    LaunchedEffect(vm.checkRevision){
        if(vm.checkRevision!=seenRevision&&vm.release!=null){seenRevision=vm.checkRevision;letter="available"}
    }
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings-page")){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回")}
            Text(if(about)"版本与更新" else "设置",Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=Ink)
        }
        LazyColumn(Modifier.weight(1f).testTag("settings-list"),state=remember(about){androidx.compose.foundation.lazy.LazyListState()},
            contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            if(!about){
                item{Surface(onClick=openAbout,shape=RoundedCornerShape(22.dp),color=GroupedSurface,
                    modifier=Modifier.fillMaxWidth().testTag("app-version")){
                    Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Rounded.Info,null,tint=Berry)
                        Column(Modifier.weight(1f).padding(horizontal=16.dp)){
                            Text("App 版本",fontSize=16.sp,fontWeight=FontWeight.Medium,color=Ink)
                            Text(vm.version.name,fontSize=14.sp,color=Muted)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
                    }
                }}
                item{EnrichmentSettings()}
            }else{
                item{Column(Modifier.padding(horizontal=4.dp)){
                    Text("映汐播放器 "+vm.version.name,fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.testTag("installed-version"))
                    Spacer(Modifier.height(6.dp))
                    Text("当前安装版本 · "+vm.version.code,fontSize=13.sp,color=Muted)
                }}
                item{Surface(onClick={letter="installed"},shape=RoundedCornerShape(20.dp),color=GroupedSurface,
                    modifier=Modifier.fillMaxWidth().testTag("installed-notes")){
                    Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Rounded.Article,null,tint=Berry)
                        Column(Modifier.weight(1f).padding(horizontal=14.dp)){
                            Text("本版说明",fontSize=16.sp,fontWeight=FontWeight.Medium,color=Ink)
                            Text("读一封放映站的信笺",fontSize=13.sp,color=Muted)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
                    }
                }}
                vm.release?.let{release->item{
                    Surface(shape=RoundedCornerShape(22.dp),color=Berry.copy(alpha=.06f),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("可用更新",fontSize=13.sp,color=Berry,fontWeight=FontWeight.SemiBold)
                            Text(release.version.name,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.testTag("available-version"))
                            Text("%.1f MB".format(java.util.Locale.ROOT,release.size/1048576.0),fontSize=13.sp,color=Muted)
                            TextButton(onClick={letter="available"},contentPadding=PaddingValues(0.dp)){Text("阅读更新信笺")}
                        }
                    }
                }}
                item{TextButton(onClick={runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,AppUpdates.RELEASES_PAGE.toUri()))}}){
                    Text("前往发布页",color=Muted)
                }}
            }
        }
        if(about){
            Surface(color=GroupedSurface,tonalElevation=0.dp,modifier=Modifier.fillMaxWidth().testTag("update-actions")){
                Column(Modifier.padding(horizontal=20.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    vm.message?.let{Text(it,fontSize=13.sp,lineHeight=19.sp,color=if(vm.error)MaterialTheme.colorScheme.error else Muted,modifier=Modifier.testTag("update-status"))}
                    if(vm.busy){
                        vm.progress?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth());Text((it*100).toInt().toString()+"%",fontSize=12.sp,color=Muted)}
                            ?:LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Text(if(vm.progress==null)"正在检查更新" else "正在下载",Modifier.weight(1f),fontSize=14.sp,color=Ink)
                            TextButton(onClick=vm::cancel){Text("取消")}
                        }
                    }else{
                        Button(onClick={when{vm.ready!=null->install();vm.release!=null->vm.download();else->vm.check()}},
                            shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)
                                .testTag(if(vm.release==null)"check-updates" else "download-update")){
                            Text(when{vm.ready!=null->"安装更新";vm.release!=null->"下载更新";else->"检查更新"})
                        }
                        if(vm.release!=null)TextButton(onClick=vm::check,modifier=Modifier.align(Alignment.CenterHorizontally).testTag("check-updates")){Text("重新检查")}
                    }
                }
            }
        }
    }
    val available=vm.release
    val shown=if(letter=="installed")vm.version else available?.version
    if(letter!=null&&shown!=null){
        val remote=letter=="available"
        val body=if(remote)available!!.notes else BuildConfig.VERSION_NOTES
        AlertDialog(onDismissRequest={letter=null},shape=RoundedCornerShape(26.dp),containerColor=Paper,
            modifier=Modifier.testTag("release-letter"),
            title={Column{
                Text(if(remote)"新的放映站信笺" else "本版说明",fontSize=17.sp,color=Muted)
                Text(shown.name,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.testTag("letter-version"))
            }},
            text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()).testTag("release-letter-body")){
                Text(displayReleaseNotes(body,language),fontSize=15.sp,lineHeight=24.sp,color=Ink)
            }},
            confirmButton={if(remote&&!vm.busy)TextButton(onClick={letter=null;if(vm.ready!=null)install()else vm.download()},modifier=Modifier.testTag("confirm-update")){Text(if(vm.ready!=null)"安装更新" else "下载更新")}
                else TextButton(onClick={letter=null}){Text("收好信笺")}},
            dismissButton={if(remote)TextButton(onClick={letter=null}){Text("稍后")}}
        )
    }
}
