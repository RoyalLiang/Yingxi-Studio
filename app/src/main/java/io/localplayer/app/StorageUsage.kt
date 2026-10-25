package io.localplayer.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

internal data class AppStorageUsage(val packageBytes:Long,val cacheBytes:Long)
internal object StorageUsage {
    var revision by mutableIntStateOf(0)
    suspend fun measure(context:Context):AppStorageUsage=withContext(Dispatchers.IO){
        val info=context.applicationInfo
        val apks=(listOf(info.sourceDir)+info.splitSourceDirs.orEmpty()).map(::File).distinctBy{it.canonicalPath}
        require(apks.all{it.isFile})
        val candidates=(listOf(context.cacheDir,context.codeCacheDir)+context.externalCacheDirs.filterNotNull()).map{it.canonicalFile}.distinctBy{it.path}.sortedBy{it.path.length}
        val roots=mutableListOf<File>()
        candidates.forEach{dir->if(roots.none{dir.path==it.path||dir.path.startsWith(it.path+File.separator)})roots+=dir}
        var bytes=0L
        roots.forEach{dir->
            if(dir.exists())dir.walkTopDown().onEnter{!Files.isSymbolicLink(it.toPath())}.forEach{file->
                coroutineContext.ensureActive()
                if(file.isFile&&!Files.isSymbolicLink(file.toPath()))bytes+=file.length()
            }
        }
        AppStorageUsage(apks.sumOf{it.length()},bytes)
    }
}

@Composable internal fun StorageUsageSettings(){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var clearing by remember{mutableStateOf(false)}
    var cleared by remember{mutableStateOf(false)}
    LaunchedEffect(cleared){if(cleared){delay(2500);cleared=false}}
    var resumed by remember{mutableIntStateOf(0)}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){resumed++}
    val usage by produceState<AppStorageUsage?>(null,resumed,StorageUsage.revision){value=runCatching{StorageUsage.measure(context)}.getOrNull()}
    Surface(color=GroupedSurface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().testTag("storage-usage")){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text("存储占用",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth()){Text("安装包体积",Modifier.weight(1f));Text(usage?.let{formatSize(it.packageBytes)}?:"—",color=Muted)}
            HorizontalDivider(color=Hairline)
            Row(Modifier.fillMaxWidth()){Text("当前应用缓存",Modifier.weight(1f));Text(usage?.let{formatSize(it.cacheBytes)}?:"—",color=Muted)}
            FilledTonalButton(enabled=!clearing,onClick={
                clearing=true;cleared=false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO){MediaPosters.clear(context);CloudReadPreferences.cache(context).clear()}
                        MediaThumbnails.changes.value++;cleared=true
                    } catch(e:CancellationException){throw e}
                    catch(_:Exception){android.widget.Toast.makeText(context,"部分缓存暂时无法回收，请稍后重试",android.widget.Toast.LENGTH_LONG).show()}
                    finally{clearing=false;StorageUsage.revision++}
                }
            },shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("media-cache-clear")) {
                if(clearing){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                Text(if(clearing)"正在回收…"else if(cleared)"已回收缓存"else"回收缓存")
            }
        }
    }
}
