package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.*

@Composable internal fun LibraryToolsScreen(vm:LibraryViewModel,databaseName:String,tool:String,onSources:()->Unit,back:()->Unit){
    val context=LocalContext.current.applicationContext
    val zh=LocalConfiguration.current.locales[0].language=="zh"
    fun words(cn:String,en:String)=if(zh)cn else en
    val scope=rememberCoroutineScope();val parcel=remember(context,databaseName){LibraryParcel(context,databaseName)}
    var message by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var worker by remember{mutableStateOf<Job?>(null)}
    var roots by remember{mutableStateOf<List<CatalogRoot>>(emptyList())}
    var films by remember{mutableStateOf<List<LibraryFilm>>(emptyList())}
    var findings by remember{mutableStateOf<Map<String,FilmAccess>>(emptyMap())}
    var scan by remember{mutableStateOf(CatalogScan(0,0))}
    var preview by remember{mutableStateOf<ParcelPreview?>(null)}
    var exportText by remember{mutableStateOf<String?>(null)}
    var recoverConfirm by remember{mutableStateOf(false)}
    var recovery by remember{mutableStateOf(parcel.hasRecovery())}
    fun work(action:suspend ()->Unit){
        if(busy)return
        busy=true;message=null
        worker=scope.launch{try{action()}catch(e:CancellationException){message=words("已停止，原有条目保留","Stopped; existing records retained");throw e}catch(e:Exception){message=e.message?:words("本次操作未完成","The operation did not finish")}finally{busy=false;recovery=parcel.hasRecovery()}}
    }
    LaunchedEffect(tool){withContext(Dispatchers.IO){
        roots=CatalogIndex.roots(context)
        films=FilmLibraryStore(context,databaseName).use{it.works().flatMap{w->w.films}}
    }}
    DisposableEffect(Unit){val remove=SealedMedia.observe{worker?.cancel();films=emptyList();findings=emptyMap();preview=null;exportText=null};onDispose{worker?.cancel();remove()}}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){if(tool!="backup")worker?.cancel()}
    BackHandler{worker?.cancel();back()}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val text=exportText;exportText=null
        if(uri!=null&&text!=null)work{
            withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(text.toByteArray(Charsets.UTF_8));it.flush()}?:throw java.io.IOException(words("无法写入所选位置","Cannot write to this location"))}
            message=words("行囊已写好","Backup saved")
        }
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)work{preview=withContext(Dispatchers.IO){
            val bytes=context.contentResolver.openInputStream(uri)?.use{it.readNBytes(LibraryParcel.MAX_BYTES+1)}?:throw java.io.IOException(words("无法读取行囊","Cannot read backup"))
            require(bytes.size<=LibraryParcel.MAX_BYTES){words("行囊超过 16 MB","Backup exceeds 16 MB")}
            parcel.preview(bytes.toString(Charsets.UTF_8))
        }}
    }
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("library-$tool")){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={worker?.cancel();back()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,words("返回","Back"))}
            Text(when(tool){"index"->words("收录目录","Index folders");"backup"->words("行囊备份","Travel backup");else->words("检修台","Repair bench")},Modifier.weight(1f),fontSize=25.sp,fontWeight=FontWeight.SemiBold)
            if(busy)IconButton(onClick={worker?.cancel()}){Icon(Icons.Rounded.Close,words("停止","Stop"))}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message!=null)Text(message!!,Modifier.padding(horizontal=24.dp,vertical=12.dp).testTag("library-tool-message"),color=Muted)
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            when(tool){
                "index"->{
                    item{Text(words("选择一处片源","Choose a root"),fontSize=21.sp,fontWeight=FontWeight.Medium)}
                    if(busy)item{Text(words("已走过 ${scan.folders} 个目录 · 收录 ${scan.films} 份影片","${scan.folders} folders · ${scan.films} films indexed"),Modifier.testTag("catalog-index-progress"),color=Muted)}
                    items(roots,key={it.id}){root->
                        Surface(color=GroupedSurface,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){
                            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Icon(if(root.tree!=null)Icons.Rounded.Folder else Icons.Rounded.Dns,null,tint=Berry)
                                Column(Modifier.weight(1f)){Text(root.label,fontSize=17.sp);Text(root.source?.protocol?.name?:words("本机文件夹","Local folder"),fontSize=12.sp,color=Muted)}
                                TextButton(enabled=!busy,onClick={work{
                                    scan=CatalogScan(0,0)
                                    val result=withContext(Dispatchers.IO){CatalogIndex.scan(context,root,root.source?.let{vm.catalogCredentials(it)},{scan=it})}
                                    message=words("收录完成 · ${result.films} 份影片","Indexed ${result.films} films")
                                }},modifier=Modifier.testTag("catalog-root:${root.id}")){Text(words("收录","Index"))}
                            }
                        }
                    }
                    if(roots.isEmpty())item{Text(words("还没有可收录的目录","No folders registered"),color=Muted)}
                    item{TextButton(onClick=onSources,enabled=!busy){Text(words("登记信源或本机文件夹","Register sources or local folders"))}}
                    item{Text(words("本机文件夹、SMB 与 SFTP 可整目录收录。中断或失联会保留已有片签。","Index local folders, SMB and SFTP. Interrupted walks retain existing records."),fontSize=13.sp,color=Muted)}
                }
                "backup"->{
                    item{Text(words("把片签带在身边","Carry your film records"),fontSize=23.sp,fontWeight=FontWeight.Medium)}
                    item{Button(enabled=!busy,onClick={work{
                        exportText=withContext(Dispatchers.IO){parcel.export()}
                        export.launch("yingxi-library.json")
                    }},modifier=Modifier.fillMaxWidth().testTag("parcel-export")){Text(words("打包行囊","Export backup"))}}
                    item{OutlinedButton(enabled=!busy,onClick={import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth().testTag("parcel-import")){Text(words("打开行囊","Open backup"))}}
                    item{Text(words("只带走普通影片的档案、放映单、札记、进度与播放偏好。封存内容、文件位置、密码与访问授权留在本机。","Includes ordinary film records, lists, notes, progress and playback preferences. Sealed content, locations, passwords and access grants stay on this device."),fontSize=14.sp,lineHeight=22.sp,color=Muted)}
                    item{TextButton(enabled=!busy,onClick={work{
                        vm.endCatalogScreening()
                        withTimeout(3000){while(runCatching{MediaDataMigration.session(context).requireIdle()}.isFailure)delay(50)}
                        preview=preview?.let{withContext(Dispatchers.IO){parcel.preview(it.text)}}
                        message=words("当前放映已结束，可打开行囊核对","Screening ended; open a backup to preview")
                    }},modifier=Modifier.testTag("parcel-end-screening")){Text(words("结束当前放映","End current screening"))}}
                    if(recovery)item{TextButton(enabled=!busy,onClick={recoverConfirm=true},modifier=Modifier.testTag("parcel-recover")){Text(words("回到上次恢复前","Undo the last restore"))}}
                }
                else->{
                    item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(words("逐份核对","Check each film"),Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Medium)
                        TextButton(enabled=!busy&&films.isNotEmpty(),onClick={work{
                            findings=emptyMap()
                            withContext(Dispatchers.IO){for(film in films){ensureActive();val source=film.media.sourceId?.let{id->SourceStore(context).load().firstOrNull{it.id==id}};findings=findings+(film.id to LibraryRepair.inspect(context,film.media,source?.let{vm.catalogCredentials(it)}))}}
                            message=words("本次核对完成","Check complete")
                        }},modifier=Modifier.testTag("repair-inspect")){Text(words("核对","Check"))}
                    }}
                    items(films,key={it.id}){film->
                        Surface(color=GroupedSurface,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(film.media.title,fontSize=16.sp,fontWeight=FontWeight.Medium)
                                Text(findings[film.id]?.let{access->accessLabel(access,zh)}?:words("尚未核对","Not checked"),fontSize=13.sp,color=Muted,modifier=Modifier.testTag("repair-result:${film.id}"))
                                if(findings[film.id] in setOf(FilmAccess.SOURCE_REMOVED,FilmAccess.AUTHORITY_CHANGED,FilmAccess.PERMISSION,FilmAccess.TRUST_REQUIRED,FilmAccess.UNKNOWN))TextButton(onClick=onSources,enabled=!busy){Text(words("去信源处理","Open sources"))}
                            }
                        }
                    }
                    if(films.isEmpty())item{Text(words("暂无待检片签","No film records to check"),color=Muted)}
                    item{Text(words("位置变化后，先收录新目录，再到影片档案关联。核对未完成的影片会保留原记录。","After a move, index the new folder and relink in the film dossier. Unresolved records are retained."),fontSize=13.sp,lineHeight=21.sp,color=Muted)}
                }
            }
        }
    }
    preview?.let{p->AlertDialog(onDismissRequest={if(!busy)preview=null},title={Text(words("核对这只行囊","Preview backup"))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth()){ParcelCount(words("影片","Films"),p.films,Modifier.weight(1f));ParcelCount(words("放映单","Lists"),p.lists,Modifier.weight(1f))}
        Row(Modifier.fillMaxWidth()){ParcelCount(words("札记","Notes"),p.moments,Modifier.weight(1f));ParcelCount(words("进度","Positions"),p.positions,Modifier.weight(1f))}
        Text(words("将替换本机 ${p.replacing} 份普通影片记录与播放偏好。封存关联资料保留；无法确认的文件位置需手动接回。恢复前会创建本机恢复点，请先结束当前放映。","Replaces ${p.replacing} ordinary film records and playback preferences. Sealed records are retained; unresolved locations require manual relinking. A local recovery point is saved first. End the current screening before restoring."),fontSize=14.sp,lineHeight=21.sp)
    }},confirmButton={TextButton(enabled=!busy,onClick={work{withContext(Dispatchers.IO){parcel.restore(p)};preview=null;message=words("片签已归位，失联位置可到检修台处理","Records restored; review unresolved locations at the repair bench")}},modifier=Modifier.testTag("parcel-restore-confirm")){Text(words("恢复行囊","Restore records"))}},dismissButton={TextButton(enabled=!busy,onClick={preview=null}){Text(words("取消","Cancel"))}})}
    if(recoverConfirm)AlertDialog(onDismissRequest={recoverConfirm=false},title={Text(words("回到恢复前？","Undo restore?"))},text={Text(words("会替换恢复后新增或更改的记录与播放偏好。当前放映必须先结束。","Replaces records and playback preferences added or changed since the restore. End the current screening first."))},confirmButton={TextButton(enabled=!busy,onClick={work{withContext(Dispatchers.IO){parcel.recover()};recoverConfirm=false;message=words("已回到上次恢复前","Previous records restored")}}){Text(words("回到恢复前","Undo restore"))}},dismissButton={TextButton(onClick={recoverConfirm=false}){Text(words("取消","Cancel"))}})
}

@Composable private fun ParcelCount(label:String,count:Int,modifier:Modifier){Column(modifier.padding(8.dp)){Text(count.toString(),fontSize=25.sp,fontWeight=FontWeight.SemiBold);Text(label,fontSize=12.sp,color=Muted)}}
internal fun accessLabel(access:FilmAccess,zh:Boolean):String=when(access){
    FilmAccess.READY->if(zh)"当前可读取" else "Readable now"
    FilmAccess.UNBOUND->if(zh)"片签已归位，文件位置待接回" else "Records restored; location unresolved"
    FilmAccess.SOURCE_REMOVED->if(zh)"原信源已移除" else "Source removed"
    FilmAccess.AUTHORITY_CHANGED->if(zh)"信源身份或根目录已变化" else "Source identity or root changed"
    FilmAccess.PERMISSION->if(zh)"需重新核对访问授权" else "Access permission needs attention"
    FilmAccess.MISSING->if(zh)"原位置未找到文件" else "File not found at its location"
    FilmAccess.VERSION_CHANGED->if(zh)"文件尺寸已变化，请另存版本" else "File size changed; keep as a separate version"
    FilmAccess.TRUST_REQUIRED->if(zh)"主机身份需在信源中确认" else "Confirm host identity in sources"
    FilmAccess.UNKNOWN->if(zh)"核对未完成，原记录保留" else "Check incomplete; records retained"
}
