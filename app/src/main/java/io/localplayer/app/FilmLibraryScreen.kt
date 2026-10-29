package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FilmLibraryView(val all:List<LibraryWork> = emptyList(),val shown:List<LibraryWork> = emptyList(),val shelves:List<LibraryShelf> = emptyList(),val moments:Map<String,List<LibraryMoment>> = emptyMap(),val loadedAt:Int=-1)

// Native Compose; existing mist/berry palette, paper labels and quiet, functional motion.
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FilmLibraryScreen(vm:LibraryViewModel,databaseName:String=MEDIA_DATABASE,onSources:()->Unit={},back:()->Unit){
    val context=LocalContext.current.applicationContext
    val zh=LocalConfiguration.current.locales[0].language=="zh"
    fun words(cn:String,en:String)=if(zh)cn else en
    var tool by rememberSaveable{mutableStateOf<String?>(null)}
    if(tool!=null){LibraryToolsScreen(vm,databaseName,tool!!,onSources){tool=null};return}
    val scope=rememberCoroutineScope()
    var revision by remember{mutableIntStateOf(0)}
    var view by remember{mutableStateOf(FilmLibraryView())}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf<String?>(null)}
    var query by rememberSaveable{mutableStateOf("")}
    var shelf by rememberSaveable{mutableStateOf<String?>(null)}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var pendingPlaylist by remember{mutableStateOf(false)}
    var newShelf by remember{mutableStateOf(false)}
    var editShelf by remember{mutableStateOf(false)}
    var renameWork by remember{mutableStateOf(false)}
    var shelfFilm by remember{mutableStateOf<LibraryFilm?>(null)}
    var relinkFilm by remember{mutableStateOf<LibraryFilm?>(null)}
    var relinkTarget by remember{mutableStateOf<LibraryFilm?>(null)}
    var groupFilm by remember{mutableStateOf<LibraryFilm?>(null)}
    var pendingMoment by remember{mutableStateOf<Pair<LibraryFilm,LibraryMoment>?>(null)}
    var editMoment by remember{mutableStateOf<LibraryMoment?>(null)}
    var label by remember{mutableStateOf("")}
    fun change(action:(FilmLibraryStore)->Unit){scope.launch{
        runCatching{withContext(Dispatchers.IO){FilmLibraryStore(context,databaseName).use(action)}}.onSuccess{revision++}.onFailure{if(it is CancellationException)throw it;error=it.message}
    }}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){revision++}
    DisposableEffect(Unit){val remove=SealedMedia.observe{view=FilmLibraryView();selected=null;relinkFilm=null;relinkTarget=null;pendingMoment=null;shelfFilm=null;groupFilm=null;editMoment=null;renameWork=false;editShelf=false;newShelf=false;revision++};onDispose{remove()}}
    val migration=MediaDataMigration.status
    LaunchedEffect(migration.state){revision++}
    LaunchedEffect(revision,shelf,query,selected){
        val requestedRevision=revision;val requestedShelf=shelf;val requestedQuery=query;val requestedSelected=selected
        fun current()=revision==requestedRevision&&shelf==requestedShelf&&query==requestedQuery&&selected==requestedSelected
        loading=true
        runCatching{withContext(Dispatchers.IO){FilmLibraryStore(context,databaseName).use{store->
            if(view.loadedAt!=requestedRevision)store.registerLegacy(HistoryStore(context).entries(),StationInventory(context).entries())
            val all=if(view.loadedAt==requestedRevision)view.all else store.works();val moments=all.firstOrNull{it.id==selected}?.films?.associate{it.id to store.moments(it.id)}?:emptyMap()
            FilmLibraryView(all,if(query.isBlank()&&shelf==null)all else store.works(query,shelf),if(view.loadedAt==requestedRevision)view.shelves else store.shelves(),moments,requestedRevision)
        }}}.onSuccess{if(current()){view=it;error=null}}.onFailure{if(it is CancellationException)throw it;if(current())error=it.message}
        if(current())loading=false
    }
    fun safe(works:List<LibraryWork>)=works.mapNotNull{item->
        val films=item.films.filter{SealedMedia.visible(it.media)}
        if(films.isEmpty())null else item.copy(films=films)
    }
    val all=safe(view.all)
    val shown=safe(view.shown)
    val work=if(shelf==null)all.firstOrNull{it.id==selected} else shown.filter{it.id==selected}.takeIf{it.isNotEmpty()}?.let{LibraryWork(it.first().id,it.first().title,it.flatMap{item->item.films})}
    BackHandler{if(selected!=null)selected=null else back()}
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("film-library")){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={if(selected!=null)selected=null else back()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,words("返回","Back"))}
            Column(Modifier.weight(1f)){
                Text(if(work!=null)words("影片档案","Film dossier")else words("片匣总录","Film catalog"),fontSize=25.sp,fontWeight=FontWeight.SemiBold)
            }
            IconButton(onClick={revision++},modifier=Modifier.testTag("film-library-refresh")){Icon(Icons.Rounded.Refresh,words("刷新已浏览影片","Refresh browsed films"))}
        }
        if(selected==null)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            TextButton(onClick={tool="index"},modifier=Modifier.testTag("catalog-index")){Text(words("收录目录","Index folders"))}
            TextButton(onClick={tool="backup"},modifier=Modifier.testTag("catalog-backup")){Text(words("行囊备份","Travel backup"))}
            TextButton(onClick={tool="repair"},modifier=Modifier.testTag("catalog-repair")){Text(words("检修台","Repair bench"))}
        }
        if(migration.state!="Committed"||migration.quarantined>0)Column(Modifier.padding(horizontal=24.dp,vertical=8.dp).testTag("data-migration-state")){
            Text(if(migration.state=="Committed")words("旧记录已归位，${migration.quarantined} 项仍需核对，原件已保留。","Old records are in place; ${migration.quarantined} need review. Originals were retained.")else words("旧记录正在归位；此时仍沿用原记录。若未完成，可稍后重试。","Old records are being moved into place. Original storage remains in use until completion; retry if needed."),fontSize=13.sp,color=Muted,lineHeight=20.sp)
            if(migration.state!="Committed")TextButton(onClick={MediaDataMigration.start(context)}){Text(words("重试归位","Retry migration"))}
        }
        if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp).testTag("film-library-error"))
        if(work==null){
            OutlinedTextField(query,{query=it},label={Text(words("片名或信源","Title or source"))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp).testTag("film-library-search"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(shelf==null,{shelf=null},label={Text(words("全部影片","All films"))})
                view.shelves.forEach{item->FilterChip(shelf==item.id,{shelf=item.id},label={Text(item.name)})}
                AssistChip(onClick={label="";newShelf=true},label={Text(words("新建放映单","New screening list"))},leadingIcon={Icon(Icons.Rounded.Add,null,Modifier.size(18.dp))})
            }
            if(shelf!=null)TextButton(onClick={label=view.shelves.firstOrNull{it.id==shelf}?.name.orEmpty();editShelf=true},modifier=Modifier.padding(start=16.dp)){Text(words("整理放映单","Manage this shelf"))}
        if(shelf!=null)Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically){
            Text(words("${shown.sumOf{it.films.size}} 份影片","${shown.sumOf{it.films.size}} films"),Modifier.weight(1f),color=Muted)
            Button(onClick={pendingPlaylist=true},enabled=shown.isNotEmpty(),modifier=Modifier.testTag("playlist-play")){Text(words("放映整单","Play list"))}
        }
        val config=LocalConfiguration.current
        val columns=if(shelf!=null)1 else if(config.screenWidthDp<360||config.fontScale>=1.4f)2 else 3
        LazyVerticalGrid(GridCells.Fixed(columns),Modifier.weight(1f).testTag("film-library-list"),contentPadding=PaddingValues(24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            if(loading)item(span={GridItemSpan(maxLineSpan)}){LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(!loading&&shown.isEmpty())item(span={GridItemSpan(maxLineSpan)}){
                Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Icon(Icons.Rounded.VideoLibrary,null,Modifier.size(40.dp),tint=Muted)
                    Spacer(Modifier.height(16.dp));Text(if(query.isNotBlank())words("没有找到影片","No matching films")else words("片匣还空着","The catalog is empty"),fontSize=20.sp)
                    if(query.isBlank())TextButton(onClick={tool="index"}){Text(words("选择目录，收录影片","Choose folders to index"))}
                }
            }
            items(shown,key={if(shelf==null)it.id else it.films.first().id}){item->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{selected=item.id}.testTag("film-work:${item.id}")){
                    if(shelf==null){
                        MediaThumbnail(item.films.first().media,Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.height(8.dp));Text(io.localplayer.core.mediaDisplayTitle(item.title),fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(item.films.map{it.media.sourceLabel}.distinct().joinToString(" · "),fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }else Row(Modifier.fillMaxWidth().background(GroupedSurface).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                        MediaThumbnail(item.films.first().media,Modifier.size(48.dp,68.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.weight(1f)){Text(io.localplayer.core.mediaDisplayTitle(item.title),fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text(item.films.first().media.sourceLabel,fontSize=12.sp,color=Muted)}
                        Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
                    }
                }
            }
        }
        }else{
        LazyColumn(Modifier.weight(1f).testTag("film-dossier"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                item{Text(work.title,fontSize=28.sp,fontWeight=FontWeight.SemiBold)}
                item{TextButton(onClick={label=work.title;renameWork=true}){Text(words("更正显示片名","Edit display title"))}}
                item{Text(words("版本与信源","Versions and sources"),fontSize=18.sp,fontWeight=FontWeight.Medium)}
                items(work.films,key={it.id}){film->
                    Column(Modifier.fillMaxWidth().background(GroupedSurface,RoundedCornerShape(18.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(film.media.title,fontSize=16.sp,fontWeight=FontWeight.Medium)
                        Text(film.media.sourceLabel,fontSize=13.sp,color=Muted)
                        Text(words("记录了文件位置与尺寸，内容版本仍待核对。播放时会检查访问权限；失联不会删除档案。","Location and size recorded; content version unverified. Access is checked when opening. An unavailable source does not erase the dossier."),fontSize=12.sp,lineHeight=19.sp,color=Muted)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button(onClick={vm.resume(HistoryEntry(film.media,0,0,film.firstSeen,false))},modifier=Modifier.testTag("film-play:${film.id}")){Text(words("继续放映","Play"))}
                            OutlinedButton(onClick={shelfFilm=film}){Text(words("加入放映单","Add to screening list"))}
                            TextButton(onClick={relinkFilm=film;relinkTarget=null}){Text(words("关联新位置","Relink location"))}
                            TextButton(onClick={groupFilm=film}){Text(words("归入另一份档案","Group with another film"))}
                            if(all.firstOrNull{it.id==film.workId}?.films.orEmpty().size>1)TextButton(onClick={change{it.separateFilm(film.id)};selected=null}){Text(words("单独成档","Separate dossier"))}
                            if(shelf!=null&&shown.any{item->item.films.any{it.id==film.id}})TextButton(onClick={change{it.removeFromShelf(shelf!!,film.id)}}){Text(words("移出放映单","Remove from shelf"))}
                        }
                        if(shelf!=null)Row{
                            TextButton(onClick={change{it.moveInShelf(shelf!!,film.id,-1)}}){Text(words("向前摆","Move earlier"))}
                            TextButton(onClick={change{it.moveInShelf(shelf!!,film.id,1)}}){Text(words("向后摆","Move later"))}
                        }
                        Text(words("片段札记","Moment notes"),fontSize=15.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=8.dp))
                        val notes=view.moments[film.id].orEmpty()
                        if(notes.isEmpty())Text(words("放映时轻点书签，记住想再看的那一处。散场后再补一句。","Tap the bookmark while watching. Add a line after the screening."),fontSize=13.sp,lineHeight=20.sp,color=Muted)
                        notes.forEach{moment->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                TextButton(onClick={pendingMoment=film to moment},modifier=Modifier.testTag("film-moment:${moment.id}")){Text(momentClock(moment.positionMs))}
                                Text(moment.note.ifEmpty{words("留一行给自己","Leave yourself a line")},Modifier.weight(1f).clickable{label=moment.note;editMoment=moment}.padding(8.dp),fontSize=14.sp,lineHeight=21.sp)
                                IconButton(onClick={label=moment.note;editMoment=moment}){Icon(Icons.Rounded.Edit,words("编辑札记","Edit note"),Modifier.size(20.dp))}
                            }
                        }
                    }
                }
            }
        }
    }
    if(pendingPlaylist)AlertDialog(onDismissRequest={pendingPlaylist=false},title={Text(words("按单放映","Play in list order"))},text={Text(words("无法访问、版本变化或封存的影片会跳过。最多载入 200 份，放映中的队列按本次顺序保留。","Unavailable, changed or sealed films will be skipped. Up to 200 films are loaded; the active queue keeps this order."))},confirmButton={TextButton(onClick={pendingPlaylist=false;vm.playCatalog(shown.flatMap{it.films})}){Text(words("开始放映","Start screening"))}},dismissButton={TextButton(onClick={pendingPlaylist=false}){Text(words("取消","Cancel"))}})
    pendingMoment?.let{(film,moment)->AlertDialog(
        onDismissRequest={pendingMoment=null},
        title={Text(words("核对这一份影片","Check this version"))},
        text={Text(words("札记记在 ${film.media.title} 的 ${momentClock(moment.positionMs)}。目前只记录了位置与尺寸，无法排除同尺寸替换。请确认信源中仍是当时那份影片。","This note points to ${momentClock(moment.positionMs)} in ${film.media.title}. Only its location and size were recorded; a replacement of the same size cannot be ruled out. Confirm that the source still contains the version you watched."))},
        confirmButton={TextButton(onClick={pendingMoment=null;vm.resume(HistoryEntry(film.media,moment.positionMs,0,film.firstSeen,false),positionMs=moment.positionMs)}){Text(words("用当前文件打开","Open current file"))}},
        dismissButton={TextButton(onClick={pendingMoment=null}){Text(words("暂不打开","Cancel"))}}
    )}
    if(newShelf||editShelf||renameWork||editMoment!=null)AlertDialog(
        onDismissRequest={newShelf=false;editShelf=false;renameWork=false;editMoment=null},
        title={Text(when{editMoment!=null->words("侧签上的一行","A line on the label");renameWork->words("更正片名","Edit title");editShelf->words("整理放映单","Manage list");else->words("给放映单起个名字","Name this shelf")})},
        text={OutlinedTextField(label,{label=it},modifier=Modifier.fillMaxWidth().testTag("film-library-label"),minLines=if(editMoment!=null)3 else 1,maxLines=if(editMoment!=null)8 else 3)},
        confirmButton={val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current;TextButton(onClick={
            val moment=editMoment;val title=label;val chosenShelf=shelf;val workId=work?.id;val isRenameWork=renameWork;val isEditShelf=editShelf;val isNewShelf=newShelf
            val limit=if(moment!=null)2000 else if(isRenameWork)200 else 60
            if(title.length>limit||(moment==null&&title.isBlank())){error=words("请填写 1–$limit 个字","Enter 1–$limit characters");return@TextButton}
            change{store->when{moment!=null->store.writeMoment(moment.id,title);isRenameWork->store.renameWork(requireNotNull(workId),title);isEditShelf->store.renameShelf(requireNotNull(chosenShelf),title);isNewShelf->store.createShelf(title);else->error("这次编辑已失效，请重新打开")}}
            focus.clearFocus(force=true);keyboard?.hide();newShelf=false;editShelf=false;renameWork=false;editMoment=null
        }){Text(words("保存","Save"))}},
        dismissButton={Row{
            if(editMoment!=null){val moment=editMoment!!;TextButton(onClick={change{it.deleteMoment(moment.id)};editMoment=null}){Text(words("删除札记","Delete note"))}}
            if(editShelf){val chosen=shelf;TextButton(onClick={if(chosen!=null)change{it.deleteShelf(chosen)};shelf=null;editShelf=false}){Text(words("删除放映单","Delete screening list"))}}
            TextButton(onClick={newShelf=false;editShelf=false;renameWork=false;editMoment=null}){Text(words("取消","Cancel"))}
        }}
    )
    if(relinkFilm!=null)AlertDialog(onDismissRequest={relinkFilm=null;relinkTarget=null},title={Text(words("为同一版本找新位置","Relink this version"))},
        text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            val origin=relinkFilm!!;val target=relinkTarget
            if(target==null){
                Text(words("仅手动选择同一信源中已浏览的新位置。片名相同不代表内容相同；不同剪辑请分别保留。","Choose a browsed location from the same source. Matching titles do not prove matching content; keep different edits separate."),fontSize=13.sp,color=Muted)
                val candidates=all.flatMap{it.films}.filter{it.id!=origin.id&&(origin.media.key.startsWith("restored:")||(it.media.sourceId==origin.media.sourceId&&(origin.media.location==null||it.media.location==origin.media.location)&&(it.media.bindingEpoch?:0)==(origin.media.bindingEpoch?:0)))}
                if(candidates.isEmpty())Text(words("先从信源或本机目录浏览新位置，再回来关联。","Browse the new source or local folder first, then return here."),fontSize=13.sp)
                candidates.forEach{candidate->TextButton(onClick={relinkTarget=candidate}){Text(candidate.media.title+"\n"+(candidate.media.path?:candidate.media.localUri.orEmpty()))}}
            }else{
                Text(words("原位置：","Old location: ")+(origin.media.path?:origin.media.localUri.orEmpty()),fontSize=13.sp)
                Text(words("新位置：","New location: ")+(target.media.path?:target.media.localUri.orEmpty()),fontSize=13.sp)
                Text(words("确认这仍是同一版本后，保留档案身份、放映单顺序、进度与札记。关联不会移动文件；播放时仍核验权限和文件。","Confirm this is the same version to retain its identity, shelf order, progress and notes. Relinking does not move files; opening still checks access and the file."),fontSize=13.sp,color=Muted)
            }
        }},
        confirmButton={if(relinkTarget!=null)TextButton(onClick={val origin=relinkFilm!!;val target=relinkTarget!!;change{it.rebindFilm(origin.id,target.media,true)};relinkFilm=null;relinkTarget=null}){Text(words("确认同一版本并关联","Confirm and relink"))}},
        dismissButton={TextButton(onClick={relinkFilm=null;relinkTarget=null}){Text(words("取消","Cancel"))}}
    )
    if(shelfFilm!=null)AlertDialog(onDismissRequest={shelfFilm=null},title={Text(words("加入放映单","Add to screening list"))},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){
        if(view.shelves.isEmpty())Text(words("先返回列表新建一张放映单。","Create a shelf from the list first."))
        view.shelves.forEach{item->TextButton(onClick={val film=shelfFilm!!;change{it.addToShelf(item.id,film.id)};shelfFilm=null}){Text(item.name)}}
    }},confirmButton={TextButton(onClick={shelfFilm=null}){Text(words("返回","Back"))}})
    if(groupFilm!=null)AlertDialog(onDismissRequest={groupFilm=null},title={Text(words("选择影片档案","Choose a film dossier"))},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){
        Text(words("只归拢档案。版本、札记和播放进度各自保留。","Only the dossier is grouped. Versions, notes, and playback progress stay separate."),fontSize=13.sp,color=Muted)
        all.filter{it.id!=groupFilm?.workId}.forEach{item->TextButton(onClick={val film=groupFilm!!;change{it.groupFilm(film.id,item.id)};selected=item.id;groupFilm=null}){Text(item.title)}}
    }},confirmButton={TextButton(onClick={groupFilm=null}){Text(words("取消","Cancel"))}})
}

internal fun momentClock(ms:Long):String {val seconds=ms.coerceAtLeast(0)/1000;return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)else "%d:%02d".format(seconds/60,seconds%60)}
