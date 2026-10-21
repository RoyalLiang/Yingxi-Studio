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
            .onFailure{vm.fail("请在系统设置中允许映汐放映室安装应用")}
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
    Surface(shape=RoundedCornerShape(22.dp),color=GroupedSurface){Column(Modifier.padding(14.dp)){
        Text("海报与字幕",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动获取海报",Modifier.weight(1f));SettingsSwitch(posters,{posters=it;prefs.posters=it;MediaThumbnails.changes.value++})}
        Text("优先使用目录海报，再按片名匹配公开资料。仅发送片名，不发送服务器地址或账户。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动匹配字幕",Modifier.weight(1f));SettingsSwitch(subtitles,{subtitles=it;prefs.subtitles=it;if(!it)OnlineSubtitles.cancel()})}
        Text("没有内嵌字幕时，优先读取同目录同名字幕，再通过射手接口发送影片指纹和文件名查找。手动选择不会被覆盖。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically){Text("优先双语字幕",Modifier.weight(1f));SettingsSwitch(bilingual,{bilingual=it;prefs.bilingual=it})}
        Text("海报资料：Wikipedia / TVmaze（CC BY-SA）。具体图片版权见来源页。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
        TextButton(onClick={MediaPosters.clear(context);MediaThumbnails.changes.value++}){Text("清理海报缓存")}
    }}
}

@Composable internal fun SettingsSwitch(checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){
    Switch(checked,onCheckedChange,modifier=modifier,enabled=enabled,colors=SwitchDefaults.colors(
        checkedThumbColor=androidx.compose.ui.graphics.Color.White,checkedTrackColor=Berry,checkedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
        uncheckedThumbColor=androidx.compose.ui.graphics.Color.White,uncheckedTrackColor=androidx.compose.ui.graphics.Color(0xFFD3D4D7),uncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
        disabledCheckedThumbColor=androidx.compose.ui.graphics.Color.White.copy(alpha=.7f),disabledCheckedTrackColor=Berry.copy(alpha=.35f),disabledCheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
        disabledUncheckedThumbColor=androidx.compose.ui.graphics.Color.White.copy(alpha=.7f),disabledUncheckedTrackColor=androidx.compose.ui.graphics.Color(0xFFD3D4D7).copy(alpha=.5f),disabledUncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent))
}
