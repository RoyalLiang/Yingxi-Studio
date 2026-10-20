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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import java.io.File

internal class SettingsViewModel(app:Application):AndroidViewModel(app) {
    val version=installedVersion(app)
    private val updates=AppUpdates(app)
    var busy by mutableStateOf(false);private set
    var progress by mutableStateOf<Float?>(null);private set
    var message by mutableStateOf<String?>(null);private set
    var error by mutableStateOf(false);private set
    var release by mutableStateOf<AppRelease?>(null);private set
    var ready by mutableStateOf<File?>(null);private set
    var installationOffered=false
    private var job:Job?=null
    fun check() {
        if(busy)return
        busy=true;error=false;message="正在检查更新…";release=null;ready=null
        job=viewModelScope.launch {
            try {release=updates.check();message=if(release==null)"当前没有可用更新" else "发现新版本 ${release!!.version.name}"}
            catch(e:CancellationException){message="已取消";throw e}
            catch(e:Exception){fail(e.message ?: "检查更新失败，请稍后重试")}
            finally{busy=false}
        }
    }
    fun download() {
        val selected=release ?: return
        if(busy)return
        busy=true;progress=0f;error=false;message="正在下载安装包…";installationOffered=false
        job=viewModelScope.launch {
            try {
                ready=updates.download(selected){value->withContext(Dispatchers.Main){progress=value}}
                message="安装包已校验，即将打开系统安装器"
            } catch(e:CancellationException){message="已取消";throw e}
            catch(e:Exception){fail(e.message ?: "下载失败，请重试")}
            finally{busy=false;progress=null}
        }
    }
    fun cancel(){job?.cancel();message="正在取消…"}
    fun fail(text:String){message=text;error=true}
    suspend fun installIntent():Intent? {
        val file=ready ?: return null;val selected=release ?: return null
        return try {
            withContext(Dispatchers.IO){
                val hash=file.inputStream().use { input -> val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)} }
                require(hash==selected.sha256){"安装包校验失败，请重新下载"}
                AppUpdates.verifyArchive(getApplication(),file,selected)
            }
            Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(getApplication(),"${getApplication<Application>().packageName}.updates",file),"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch(e:Exception){fail(e.message ?: "无法打开安装包");null}
    }
}

@Composable internal fun SettingsRoute(onClose:()->Unit) {
    val vm:SettingsViewModel=viewModel()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var about by rememberSaveable{mutableStateOf(false)}
    val back={if(about)about=false else onClose()}
    BackHandler(onBack=back)
    val install={scope.launch{vm.installIntent()?.let { intent -> runCatching{context.startActivity(intent)}.onFailure{vm.fail("无法打开系统安装器")} } };Unit}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls())install() else vm.fail("未允许安装；授权后可再次点击安装")
    }
    val offerInstall={
        if(context.packageManager.canRequestPackageInstalls())install()
        else runCatching{permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}.onFailure{vm.fail("请在系统设置中允许映汐播放器安装应用")}
        Unit
    }
    LaunchedEffect(vm.ready) { if(vm.ready!=null&&!vm.installationOffered){vm.installationOffered=true;offerInstall()} }
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回")}
            Text(if(about)"版本与更新" else "设置",Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f),state=remember(about){androidx.compose.foundation.lazy.LazyListState()},contentPadding=PaddingValues(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(!about) {
                item { EnrichmentSettings() }
            item{Text("关于映汐播放器",Modifier.padding(start=4.dp),fontSize=13.sp,color=Muted,fontWeight=FontWeight.SemiBold)}
                item{Surface(onClick={about=true},shape=RoundedCornerShape(22.dp),color=GroupedSurface,modifier=Modifier.fillMaxWidth().testTag("app-version")){
                    Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Rounded.Info,null,tint=Berry);Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)){Text("App 版本",fontSize=16.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(4.dp));Text(vm.version.name,fontSize=14.sp,color=Muted)}
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
                    }
                }}
            } else {
                item{Column(Modifier.padding(horizontal=4.dp)){Text("映汐播放器 ${vm.version.name}",fontSize=24.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text("安装版本号 ${vm.version.code}",fontSize=13.sp,color=Muted)}}
                if(BuildConfig.VERSION_CHANGES.isNotBlank())item{Column(Modifier.padding(horizontal=4.dp)){Text("本版说明",fontSize=18.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(12.dp));Text(BuildConfig.VERSION_CHANGES,fontSize=14.sp,lineHeight=23.sp,color=Muted)}}
                item{Button(onClick=vm::check,enabled=!vm.busy,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("check-updates")){Text("检查更新")}}
                vm.message?.let { text -> item{Text(text,color=if(vm.error)MaterialTheme.colorScheme.error else Muted,fontSize=14.sp,lineHeight=21.sp)} }
                vm.progress?.let { value -> item{Column{LinearProgressIndicator(progress={value},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));Text("${(value*100).toInt()}%",fontSize=13.sp,color=Muted)}} }
                vm.release?.let { release ->
                    item{Column{Text(release.version.name,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp));Text(release.notes.ifBlank{"此版本未附版本说明。"},fontSize=14.sp,lineHeight=22.sp,color=Muted)}}
                    if(!vm.busy)item{Button(onClick={if(vm.ready!=null)offerInstall()else vm.download()},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(18.dp)){Text(if(vm.ready!=null)"安装更新" else "下载并安装")}}
                }
                if(vm.busy)item{TextButton(onClick=vm::cancel){Text("取消")}}
                item{Text("更新来自映汐播放器 GitHub 发布页；下载后校验包名、版本、完整性与签名，安装时需系统确认。",fontSize=12.sp,lineHeight=19.sp,color=Muted)}
            }
        }
    }
}

@Composable internal fun EnrichmentSettings(){
    val context=LocalContext.current
    val prefs=remember(context){EnrichmentPreferences(context)}
    var posters by remember{mutableStateOf(prefs.posters)}
    var subtitles by remember{mutableStateOf(prefs.subtitles)}
    var bilingual by remember{mutableStateOf(prefs.bilingual)}
    Surface(shape=RoundedCornerShape(22.dp),color=GroupedSurface){Column(Modifier.padding(18.dp)){
        Text("海报与字幕",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动获取海报",Modifier.weight(1f));Switch(posters,{posters=it;prefs.posters=it;MediaThumbnails.changes.value++})}
        Text("优先使用目录海报，再按片名匹配公开资料。仅发送片名，不发送服务器地址或账户。",fontSize=12.sp,lineHeight=19.sp,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动匹配字幕",Modifier.weight(1f));Switch(subtitles,{subtitles=it;prefs.subtitles=it;if(!it)OnlineSubtitles.cancel()})}
        Text("没有内嵌字幕时，通过射手接口发送影片指纹和文件名查找字幕，优先简体中文，兼容双语。可在音字幕面板手动重试。",fontSize=12.sp,lineHeight=19.sp,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically){Text("优先双语字幕",Modifier.weight(1f));Switch(bilingual,{bilingual=it;prefs.bilingual=it})}
        Text("海报资料：Wikipedia / TVmaze（CC BY-SA）。具体图片版权见来源页。",fontSize=12.sp,lineHeight=19.sp,color=Muted)
        TextButton(onClick={MediaPosters.clear(context);MediaThumbnails.changes.value++}){Text("清理海报缓存")}
    }}
}
