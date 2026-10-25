package io.localplayer.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import java.io.File

internal class SettingsViewModel @JvmOverloads constructor(app:Application,private val injectedUpdates:AppUpdates?=null):AndroidViewModel(app) {
    private val updates=injectedUpdates?:AppUpdates(app)
    val version=installedVersion(app)
    var busy by mutableStateOf(false);private set
    var progress by mutableStateOf<Float?>(null);private set
    var message by mutableStateOf<String?>(null);private set
    var error by mutableStateOf(false);private set
    var release by mutableStateOf<AppRelease?>(null);private set
    var ready by mutableStateOf<File?>(null);private set
    var checkRevision by mutableIntStateOf(0);private set
    var installationOffered=false
    private var job:Job?=null
    private var generation=0L
    init {
        if(injectedUpdates==null){
            UpdateDownloads.restore(app)
            viewModelScope.launch{UpdateDownloads.state.collect{state->if(state!=null){release=state.release;busy=state.busy;progress=state.progress;ready=state.ready;message=state.message;error=state.error}}}
        }
    }
    fun check(){
        if(busy)return
        val id=++generation
        busy=true;error=false;progress=null;message="正在检查更新…";release=null;ready=null
        job=viewModelScope.launch{
            try{
                val found=updates.check()
                if(id==generation){release=found;message=if(found==null)"当前没有可用更新" else "有一封新信笺";checkRevision++}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(id==generation)fail(e.message?:"检查更新失败，请稍后重试")}
            finally{if(id==generation)busy=false}
        }
    }
    fun download(){
        val selected=release?:return
        if(busy)return
        if(injectedUpdates==null){
            installationOffered=false
            runCatching{UpdateDownloads.start(getApplication(),selected)}.onFailure{fail(it.message?:"无法启动后台下载，请重试")}
            return
        }
        val id=++generation
        busy=true;progress=0f;error=false;message="正在下载安装包…";installationOffered=false
        job=viewModelScope.launch{
            try{
                val file=updates.download(selected){value->withContext(Dispatchers.Main){if(id==generation)progress=value}}
                if(id==generation){ready=file;message="安装包已校验，可以安装更新"}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(id==generation)fail(e.message?:"下载失败，请重试")}
            finally{if(id==generation){busy=false;progress=null}}
        }
    }
    fun cancel(){
        if(injectedUpdates==null&&UpdateDownloads.state.value?.busy==true){UpdateDownloads.pause(getApplication());return}
generation++;job?.cancel();job=null;busy=false;progress=null;message="已取消"}
    fun fail(text:String){message=text;error=true}
    fun installMessage(text:String){message=text;error=false}
    suspend fun installIntent():Intent? {
        val file=ready ?: return null;val selected=release ?: return null
        return try {
            withContext(Dispatchers.IO){
                val hash=file.inputStream().use { input -> val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)} }
                require(hash==selected.sha256){"安装包校验失败，请重新下载"}
                AppUpdates.verifyArchive(getApplication(),file,selected)
            }
            require(selected.version.code>installedVersion(getApplication()).code){"此安装包不是更新版本，请重新检查更新"}
            UpdateInstallActivity.intent(getApplication(),file,selected)
        } catch(e:Exception){fail(e.message ?: "无法打开安装包");null}
    }
}

@Composable internal fun SettingsRoute(vm:SettingsViewModel=viewModel(),onClose:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,vm) {
        fun consume(){UpdateInstallResult.take(context)?.let{(text,error)->if(error)vm.fail(text)else vm.installMessage(text)}}
        val resultPreferences=context.getSharedPreferences("update-install-result",android.content.Context.MODE_PRIVATE)
        val resultListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="text")consume()}
        resultPreferences.registerOnSharedPreferenceChangeListener(resultListener)
        consume()
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)consume()}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);resultPreferences.unregisterOnSharedPreferenceChangeListener(resultListener)}
    }
    var about by rememberSaveable{mutableStateOf(false)}
    val back={if(about)about=false else onClose()}
    BackHandler(onBack=back)
    val install={scope.launch{vm.installIntent()?.let{intent->
        runCatching{context.startActivity(intent)}.onFailure{vm.fail("无法打开系统安装器")}
    }};Unit}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
        if(context.packageManager.canRequestPackageInstalls())install()else vm.fail("未允许安装；授权后可再次点击安装")
    }
    val offerInstall={
        if(context.packageManager.canRequestPackageInstalls())install()
        else runCatching{permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)))}
            .onFailure{vm.fail("请在系统设置中允许映汐放映站安装应用")}
        Unit
    }
    LaunchedEffect(vm.ready){if(vm.ready!=null&&!vm.installationOffered){vm.installationOffered=true;offerInstall()}}
    VersionSettingsPage(vm,about,back,{about=true},offerInstall)
}

@Composable internal fun EnrichmentSettings(){
    val context=LocalContext.current
    val prefs=remember(context){EnrichmentPreferences(context)}
    var posters by remember{mutableStateOf(prefs.posters)}
    var subtitles by remember{mutableStateOf(prefs.subtitles)}
    var bilingual by remember{mutableStateOf(prefs.bilingual)}
    var subtitleSource by remember{mutableStateOf(prefs.subtitleSource)}
    Surface(modifier=Modifier.fillMaxWidth().testTag("enrichment-settings"),shape=RoundedCornerShape(22.dp),color=GroupedSurface){Column(Modifier.padding(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动获取海报",Modifier.weight(1f));SettingsSwitch(posters,{posters=it;prefs.posters=it;MediaThumbnails.changes.value++})}
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动匹配字幕",Modifier.weight(1f));SettingsSwitch(subtitles,{subtitles=it;prefs.subtitles=it;OnlineSubtitles.preferencesChanged(context)})}
        Row(verticalAlignment=Alignment.CenterVertically){Text("优先双语字幕",Modifier.weight(1f));SettingsSwitch(bilingual,{bilingual=it;prefs.bilingual=it;OnlineSubtitles.preferencesChanged(context)})}
        Spacer(Modifier.height(12.dp))
        StationChoiceSetting("字幕来源",listOf("subhd" to "SubHD","shooter" to "射手"),subtitleSource,"subtitle-source"){subtitleSource=it;prefs.subtitleSource=it;OnlineSubtitles.preferencesChanged(context)}
        Text(if(subtitleSource=="subhd")"缺少内嵌与同目录字幕时，按片名、年份和集数查询 SubHD。网站要求验证或不提供直链时，可查看匹配资源。"else"缺少字幕时，向射手发送影片指纹和文件名匹配；不上传影片内容。",fontSize=13.sp,lineHeight=19.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
    }}
}

@Composable internal fun SettingsSwitch(checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){
    Switch(checked,onCheckedChange,modifier=modifier,enabled=enabled,colors=SwitchDefaults.colors(
        checkedThumbColor=androidx.compose.ui.graphics.Color.White,checkedTrackColor=Berry,checkedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
        uncheckedThumbColor=androidx.compose.ui.graphics.Color.White,uncheckedTrackColor=androidx.compose.ui.graphics.Color(0xFFD3D4D7),uncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
        disabledCheckedThumbColor=androidx.compose.ui.graphics.Color.White.copy(alpha=.7f),disabledCheckedTrackColor=Berry.copy(alpha=.35f),disabledCheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
        disabledUncheckedThumbColor=androidx.compose.ui.graphics.Color.White.copy(alpha=.7f),disabledUncheckedTrackColor=androidx.compose.ui.graphics.Color(0xFFD3D4D7).copy(alpha=.5f),disabledUncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent))
}

@Composable internal fun StationChoiceSetting(title:String,values:List<Pair<String,String>>,selected:String,tag:String,select:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact=maxWidth<300.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            if(compact)Box {
                OutlinedButton(onClick={expanded=true},shape=RoundedCornerShape(14.dp),modifier=Modifier.heightIn(min=48.dp).testTag("$tag-selector")) {
                    Text(values.first{it.first==selected}.second,maxLines=1)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,Modifier.size(18.dp))
                }
                DropdownMenu(expanded,onDismissRequest={expanded=false}) {
                    values.forEach{(value,label)->DropdownMenuItem(text={Text(label)},onClick={select(value);expanded=false},modifier=Modifier.testTag("$tag-$value"))}
                }
            } else SingleChoiceSegmentedButtonRow(Modifier.testTag("$tag-options")) {
                values.forEachIndexed{index,(value,label)->
                    SegmentedButton(selected=selected==value,onClick={select(value)},shape=SegmentedButtonDefaults.itemShape(index,values.size),icon={},modifier=Modifier.testTag("$tag-$value")){Text(label,maxLines=1)}
                }
            }
        }
    }
}
