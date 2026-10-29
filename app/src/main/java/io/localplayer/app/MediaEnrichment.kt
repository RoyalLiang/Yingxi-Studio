package io.localplayer.app

import io.localplayer.core.SourceRoot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.*
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest

internal class EnrichmentPreferences(context:Context){
    private val p=MigratingPreferences.open(context,"media-enrichment-v1")
    var posters:Boolean get()=p.getBoolean("posters",true);set(v){p.edit{putBoolean("posters",v)}}
    var subtitles:Boolean get()=p.getBoolean("subtitles",true);set(v){p.edit{putBoolean("subtitles",v)}}
    var bilingual:Boolean get()=p.getBoolean("bilingual",false);set(v){p.edit{putBoolean("bilingual",v)}}
    var subtitleSource:String get()=p.getString("subtitle-source","subhd").takeIf{it in setOf("subhd","shooter","custom")}?:"subhd";set(v){require(v in setOf("subhd","shooter","custom"));p.edit{putString("subtitle-source",v)}}
    var customSubtitleSearch:String get()=p.getString("custom-subtitle-search","").orEmpty();set(v){subtitleSearchUrl(v,"Film");p.edit{putString("custom-subtitle-search",v.trim())}}
}
internal fun enrichmentKey(media:MediaRef)=MessageDigest.getInstance("SHA-256").digest((listOf(media.key,media.location,media.sizeBytes,media.localUri)+if((media.bindingEpoch?:0)>0)listOf("epoch:${media.bindingEpoch}")else emptyList()).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}

private fun posterPath(media:MediaRef,source:Source?)=media.posterPathHint?:media.path?.let{listOfNotNull(source?.root?.takeIf{it.isNotBlank()},it).joinToString("/")}
    ?:media.localUri?.let{raw->runCatching{val uri=raw.toUri();if("document" in uri.pathSegments)android.provider.DocumentsContract.getDocumentId(uri).substringAfter(':') else uri.path}.getOrNull()}
internal fun posterIdentity(media:MediaRef,source:Source?=null):MediaIdentity {
    if(!media.key.startsWith("directory:"))return MediaIdentity.forPoster(media.title,posterPath(media,source))
    val folder=MediaIdentity.forPoster("1.mkv",posterPath(media,source))
    return if(folder.title=="1")MediaIdentity.parse(media.title) else folder.copy(episode=null)
}

internal object MediaPosters {
    private val io=Dispatchers.IO.limitedParallelism(2)
    private val disk=Dispatchers.IO.limitedParallelism(2)
    private val background=CoroutineScope(SupervisorJob()+io)
    private val jobs=java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val protectedRequests=java.util.concurrent.ConcurrentHashMap<Cancellation,MediaRef>()
    fun cancelProtected(){protectedRequests.entries.filter{SealedMedia.protects(it.value)}.forEach{entry->background.launch(Dispatchers.IO){entry.key.close()}}}
    private val http=EnrichmentHttp()
    private val lookup=PosterLookup(http)
    private val failed=java.util.concurrent.ConcurrentHashMap<String,Long>()
    val changes=MutableStateFlow(0L)
    private val revision=java.util.concurrent.atomic.AtomicLong()
    private val writes=Any()
    fun retryMissing(){revision.incrementAndGet();failed.clear();changes.update{it+1}}
    internal var connector:Connector=AppConnectors()
    private val locks=Array(16){kotlinx.coroutines.sync.Mutex()}
    internal var onlineLookup:(MediaIdentity,Cancellation)->PosterMatch?={id,c->lookup.find(id,c)}
    internal var imageDownload:(String,Cancellation)->ByteArray={url,c->http.get(url,c,5*1024*1024)}
    private const val policy=3
    private const val recheckMs=7L*24*60*60*1000
    private fun identity(media:MediaRef,source:Source?)=posterIdentity(media,source).let{listOf(it.title,it.year,it.season,it.series).joinToString("|")}
    private fun source(context:Context,media:MediaRef)=media.sourceId?.let{id->SourceStore(context).load().firstOrNull{it.id==id}}
    private fun allowed(context:Context,media:MediaRef,source:Source?)=!SealedMedia.protects(media)&&EnrichmentPreferences(context).posters&&(media.sourceId==null||(source!=null&&media.location==mediaLocation(source)&&runCatching{SourceBindingEpochs.matches(context,source,media)&&media.path?.let{SourceRoot(source.initialPath).contains(it)}==true}.getOrDefault(false)))
    private fun record(context:Context,media:MediaRef,source:Source?)=PosterStore(context).metadata(enrichmentKey(media))?.takeIf{it.optString("identity")==identity(media,source)}
    fun credit(context:Context,media:MediaRef):Pair<String,String>?=runCatching {
        val source=source(context,media)
        if(!allowed(context,media,source))null else record(context,media,source)?.let{it.getString("credit") to it.getString("url")}
    }.getOrNull()
    internal fun decode(data:ByteArray):Bitmap?{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth !in 1..20000||bounds.outHeight !in 1..20000)return null
        val options=BitmapFactory.Options().apply{inSampleSize=1;while(bounds.outWidth/inSampleSize>800||bounds.outHeight/inSampleSize>1000)inSampleSize*=2}
        return BitmapFactory.decodeByteArray(data,0,data.size,options)
    }
    suspend fun load(context:Context,media:MediaRef,localOnly:Boolean=false):Bitmap?=withContext(disk) {
        val app=context.applicationContext;val source=source(app,media)
        if(!allowed(app,media,source))return@withContext null
        val id=enrichmentKey(media);val store=PosterStore(app)
        val cached=record(app,media,source)
        val bitmap=cached?.let(store::image)?:migrate(app,media,source,store)
        if(bitmap!=null){
            val data=record(app,media,source)
            val age=System.currentTimeMillis()-(data?.optLong("checkedAt")?:0)
            if(data?.optInt("policy")!=policy||age !in 0 until recheckMs)schedule(app,media)
            return@withContext bitmap
        }
        if(localOnly)null else fetch(app,media)
    }
    private fun migrate(context:Context,media:MediaRef,source:Source?,store:PosterStore):Bitmap?=runCatching {
        val generation=revision.get();val token=HistoryStore(context).token(media)
        // Movie covers from the previous identity policy remain useful, including expired ones.
        if(posterIdentity(media,source)!=MediaIdentity.parse(media.title))return@runCatching null
        val id=enrichmentKey(media);val dir=File(context.cacheDir,"media-posters-v1")
        val data=org.json.JSONObject(File(dir,"$id.json").readText())
        if(data.optInt("policy")!=2||data.optString("title")!=media.title)return@runCatching null
        val bitmap=decode(File(dir,"$id.jpg").readBytes())?:return@runCatching null
        synchronized(writes){
            if(revision.get()!=generation||HistoryStore(context).token(media)!=token||!allowed(context,media,source(context,media)))return@runCatching null
            store.save(id,bitmap,data.put("identity",identity(media,source)).put("policy",2).put("checkedAt",0).put("confidence",.96))
        }
        bitmap
    }.getOrNull()
    private fun schedule(context:Context,media:MediaRef) {
        val id=enrichmentKey(media)
        synchronized(jobs){
            if(jobs[id]?.isActive==true)return
            val job=background.launch(start=CoroutineStart.LAZY){fetch(context,media,refresh=true)}
            jobs[id]=job
            job.invokeOnCompletion{synchronized(jobs){if(jobs[id]===job)jobs.remove(id)}}
            job.start()
        }
    }
    internal suspend fun awaitRefresh(){jobs.values.toList().joinAll()}
    private suspend fun fetch(context:Context,media:MediaRef,refresh:Boolean=false):Bitmap?=coroutineScope {
        val cancellation=Cancellation()
        val worker=async(io){fetchRequest(context,media,cancellation,refresh)}
        try{worker.await()}finally{withContext(NonCancellable+Dispatchers.IO){cancellation.close()}}
    }
    private suspend fun fetchRequest(context:Context,media:MediaRef,c:Cancellation,refresh:Boolean):Bitmap?=withContext(io){
        val source=source(context,media)
        if(!allowed(context,media,source))return@withContext null
        val id=enrichmentKey(media);val lock=locks[(id.hashCode()and Int.MAX_VALUE)%locks.size]
        lock.lock()
        try{
            val store=PosterStore(context);val old=record(context,media,source)
            val previous=old?.let(store::image)
            val age=System.currentTimeMillis()-(old?.optLong("checkedAt")?:0)
            if(previous!=null&&old?.optInt("policy")==policy&&age in 0 until recheckMs)return@withContext previous
            if(System.currentTimeMillis()-(failed[id]?:0)<6*60*60*1000)return@withContext previous
            val token=HistoryStore(context).token(media);val generation=revision.get()
            protectedRequests[c]=media
        val watcher=EnrichmentDeadlines.timer.schedule({c.close()},20,java.util.concurrent.TimeUnit.SECONDS)
            val owner=coroutineContext.job.invokeOnCompletion{if(it!=null)c.close()}
            fun valid()=revision.get()==generation&&HistoryStore(context).token(media)==token&&allowed(context,media,source(context,media))
            try{
                var credit="目录海报";var url="";var imageUrl="";var confidence=1.0
                val local=runCatching{sidecar(context,media,source,c)}.getOrNull()?.let(::decode)
                if(!valid())return@withContext null
            val bitmap=local?:onlineLookup(posterIdentity(media,source),c)?.takeIf{it.confidence>=.96&&it.confidence>=(old?.optDouble("confidence",0.0)?:0.0)}?.let{match->
                    credit=match.credit;url=match.sourceUrl;imageUrl=match.imageUrl;confidence=match.confidence
                    if(previous!=null&&old?.optString("imageUrl")==match.imageUrl)previous else decode(imageDownload(match.imageUrl,c))
                }
                ensureActive();c.check()
                if(!valid())return@withContext previous
                if(bitmap==null){if(valid())failed[id]=System.currentTimeMillis();return@withContext previous}
                val data=org.json.JSONObject().put("credit",credit).put("url",url).put("imageUrl",imageUrl).put("confidence",confidence)
                    .put("policy",policy).put("identity",identity(media,source)).put("title",media.title).put("fetchedAt",System.currentTimeMillis()).put("checkedAt",System.currentTimeMillis())
                synchronized(writes){
                    if(!valid())return@withContext previous
                    if(bitmap===previous&&old!=null)store.update(id,data.put("image",old.getString("image"))) else store.save(id,bitmap,data)
                }
                failed.remove(id)
                if(refresh)changes.update{it+1}
                bitmap
            }catch(e:CancellationException){throw e}catch(_:Exception){if(coroutineContext.isActive&&valid())failed[id]=System.currentTimeMillis();previous}
            finally{watcher.cancel(false);owner.dispose();protectedRequests.remove(c);c.close()}
        }finally{lock.unlock()}
    }
    private fun sidecar(context:Context,media:MediaRef,source:Source?,c:Cancellation):ByteArray?{
        if(source!=null){
            val pass=SourceStore(context).password(source.id)
            if(pass==null&&source.username.isNotBlank())return null
            return connector.connect(source,Credentials(pass?:""),c).use{session->
                val entries=session.list(media.path?.substringBeforeLast('/',"")?:"")
                val names=posterSidecarNames(media.title,entries.filter{!it.directory}.map{it.name},posterPath(media,source))
            val image=names.firstNotNullOfOrNull{n->entries.firstOrNull{!it.directory&&it.name.equals(n,true)&&it.size in 1..5L*1024*1024}}?:return@use null
                session.open(image.path).use image@{file->if(file.size !in 1..5L*1024*1024)return@image null;val data=ByteArray(file.size.toInt());var n=0
                    while(n<data.size){c.check();val k=file.readAt(n.toLong(),data,n,data.size-n);if(k<=0)return@image null;n+=k};data}
            }
        }
        val uri=media.localUri?.let(Uri::parse)?:return null
        if(uri.scheme!="content"||!android.provider.DocumentsContract.isTreeUri(uri))return null
        val doc=android.provider.DocumentsContract.getDocumentId(uri)
        if ('/' !in doc)return null
        val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri,doc.substringBeforeLast('/'))
        val fields=arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val found=mutableMapOf<String,Uri>()
        context.contentResolver.query(children,fields,null,null,null)?.use{cursor->var count=0;while(cursor.moveToNext()&&count++<2000){c.check();found[cursor.getString(1).lowercase()]=android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,cursor.getString(0))}}
        val names=posterSidecarNames(media.title,found.keys.toList(),posterPath(media,source))
        val image=names.firstNotNullOfOrNull{found[it.lowercase()]}?:return null
        return context.contentResolver.openInputStream(image)?.use{it.readNBytes(5*1024*1024+1)}?.takeIf{it.size<=5*1024*1024}
    }

    fun remove(c:Context,m:MediaRef)=synchronized(writes){revision.incrementAndGet();val id=enrichmentKey(m);jobs.remove(id)?.cancel();PosterStore(c).remove(id);File(c.cacheDir,"media-posters-v1/$id.jpg").delete();File(c.cacheDir,"media-posters-v1/$id.json").delete();failed.remove(id);Unit}
    fun clear(c:Context)=synchronized(writes){revision.incrementAndGet();jobs.values.forEach{it.cancel()};jobs.clear();PosterStore(c).clear();File(c.cacheDir,"media-posters-v1").listFiles()?.forEach{it.delete()};failed.clear();changes.update{it+1}}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OnlineSubtitles {
    val resources=MutableStateFlow<Pair<String,List<SubtitleResource>>?>(null)
    internal var subHd=SubHdSubtitles()
    private var playerRef:java.lang.ref.WeakReference<Player>?=null
    val status=MutableStateFlow<Pair<String,String>?>(null)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var attempted:String?=null
    private var active:Cancellation?=null
    private var activeMedia:MediaRef?=null
    fun cancelProtected(){if(activeMedia?.let{SealedMedia.protects(it)}==true)cancel()}
    private var generation=0L
    private fun stopRequest(){activeMedia=null;generation++;job?.cancel();job=null;val old=active;active=null;scope.launch(Dispatchers.IO){old?.close()}}
    internal var provider=ShooterSubtitles()
    fun cancel(){stopRequest();attempted=null;status.value=null;resources.value=null;playerRef=null}
    fun preferencesChanged(context:Context){val player=playerRef?.get();cancel();if(player!=null)runCatching{schedule(context.applicationContext,player)}}
    fun schedule(c:Context,player:Player){
        playerRef=java.lang.ref.WeakReference(player)
        val item=player.currentMediaItem?:return
        val ref=item.mediaMetadata.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}
        if(ref!=null&&SealedMedia.protects(ref))return
        val requestKey=item.mediaId+"\u0000"+item.mediaMetadata.extras?.getString("play-intent")
        if(attempted==requestKey)return
        if(!EnrichmentPreferences(c).subtitles||player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))return
        if(player.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT&&it.isSupported}||item.localConfiguration?.subtitleConfigurations?.isNotEmpty()==true)return
        stopRequest();status.value=null;resources.value=null;attempted=requestKey;activeMedia=ref
        job=scope.launch{
            repeat(10){
                delay(3000)
                if(player.currentMediaItem?.let{it.mediaId+"\u0000"+it.mediaMetadata.extras?.getString("play-intent")}!=requestKey)return@launch
                if(!player.isPlaying||player.bufferedPosition-player.currentPosition>=5000){
                    match(c,player,false);return@launch
                }
            }
            attempted=null
        }
    }
    fun request(c:Context,player:Player){stopRequest();job=scope.launch{match(c,player,true)}}
    private suspend fun match(context:Context,player:Player,force:Boolean){
        val item=player.currentMediaItem?:return
        val id=item.mediaId;val intent=item.mediaMetadata.extras?.getString("play-intent")
        val parameters=player.trackSelectionParameters
        val prefs=EnrichmentPreferences(context);val preferBilingual=prefs.bilingual;val source=prefs.subtitleSource
        val uri=item.localConfiguration?.uri?:return
        val media=item.mediaMetadata.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}?:return
        if(SealedMedia.protects(media)){status.value=id to "封存档案不进行互联网匹配";return}
        activeMedia=media
        if(source=="custom"){
            val identity=posterIdentity(media)
            val query=listOfNotNull(identity.title,identity.year?.toString(),identity.season?.let{"S%02d".format(it)},identity.episode?.let{"E%02d".format(it)}).joinToString(" ")
            val url=runCatching{subtitleSearchUrl(prefs.customSubtitleSearch,query)}.getOrNull()
            resources.value=if(url==null)null else id to listOf(SubtitleResource("custom",identity.title,url))
            status.value=id to if(url==null)"请先在站务填写字幕驿站的搜寻地址"else"字幕驿站已备好片名搜寻，点按后前往"
            return
        }
        if(!force&&(!prefs.subtitles||parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)||
            player.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT&&it.isSupported}||item.localConfiguration?.subtitleConfigurations?.isNotEmpty()==true))return
        if(!force&&player.isPlaying&&player.bufferedPosition-player.currentPosition<5000)return
        status.value=id to "正在匹配字幕…";resources.value=null
        val cancellation=Cancellation();active=cancellation;val requestGeneration=generation
        var found=emptyList<SubtitleResource>()
        fun current()=generation==requestGeneration&&player.currentMediaItem?.mediaId==id&&player.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")==intent
        try{
            val caption=withContext(Dispatchers.IO){
                val dir=File(context.cacheDir,"online-subtitles-v1").apply{mkdirs()}
                val key=enrichmentKey(media)+"-$source"+(if(preferBilingual)"-bi"else"-zh")
                val cached=dir.listFiles()?.firstOrNull{it.nameWithoutExtension==key&&it.extension in setOf("srt","ass","ssa","vtt")}
                val watcher=EnrichmentDeadlines.timer.schedule({cancellation.close()},22,java.util.concurrent.TimeUnit.SECONDS)
                val completion=coroutineContext.job.invokeOnCompletion{if(it!=null)cancellation.close()}
                try{
                    if(cached!=null)return@withContext cached
                    var best:SubtitleContent?=null;var bestScore=Int.MIN_VALUE
                    fun consider(candidate:SubtitleContent){
                        val score=provider.preferenceScore(candidate.bytes,preferBilingual)?:return
                        if(score>bestScore){best=candidate;bestScore=score}
                    }
                    if(source=="subhd"){
                        val identity=posterIdentity(media)
                        found=subHd.search(identity,cancellation)
                        for(candidate in found.filter{it.automatic}.take(3)){
                            ensureActive();cancellation.check()
                            try{subHd.download(candidate,identity,cancellation).forEach(::consider)}
                            catch(e:SubtitleDownloadUnavailable){throw e}
                            catch(e:CancellationException){throw e}
                            catch(_:Exception){cancellation.check()}
                        }
                    }else{
                        val file=if(uri.isPlayerNetworkUri()){
                            val lease=PlaybackRegistry.preview(uri).also(cancellation::own);lease.open().also(cancellation::own)
                        }else{
                            val descriptor=context.contentResolver.openFileDescriptor(uri,"r")?:throw java.io.IOException("影片无法读取")
                            val input=java.io.FileInputStream(descriptor.fileDescriptor);val size=input.channel.size()
                            (object:RemoteFile{
                                override val size=size
                                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{cancellation.check();input.channel.position(position);return input.read(buffer,offset,length)}
                                override fun close(){runCatching{input.close()};runCatching{descriptor.close()}}
                            }).also(cancellation::own)
                        }
                        for(candidate in provider.search(file,media.title,preferBilingual,cancellation).take(4)){
                            try{consider(SubtitleContent(provider.download(candidate,cancellation),candidate.extension,candidate.label))}
                            catch(e:CancellationException){throw e}catch(_:Exception){cancellation.check()}
                        }
                    }
                    ensureActive();cancellation.check()
                    val chosen=best?:return@withContext null
                    val target=File(dir,key+"."+chosen.extension);val temporary=File(dir,"$key.tmp")
                    try{temporary.writeBytes(chosen.bytes);check(temporary.renameTo(target))}finally{temporary.delete()}
                    dir.listFiles()?.filter{it.name!=target.name}?.sortedByDescending{it.lastModified()}?.drop(63)?.forEach{it.delete()}
                    target
                }finally{watcher.cancel(false);completion.dispose();cancellation.close()}
            }
            if(!current()||(!force&&player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))||player.trackSelectionParameters!=parameters)return
            resources.value=if(caption==null)id to found else null
            if(caption==null){status.value=id to "未找到可直接载入的匹配字幕";return}
            attach(player,caption)
            status.value=id to if(source=="subhd")"已载入 SubHD 字幕"else"已载入射手字幕"
        }catch(e:CancellationException){throw e}catch(_:Exception){if(current()){
            resources.value=id to found
            status.value=id to if(found.isNotEmpty())"已找到匹配资源；网站暂未提供可直接载入的字幕"else"字幕来源暂不可用，稍后重播时重试"
        }}finally{if(active===cancellation)active=null;withContext(NonCancellable+Dispatchers.IO){cancellation.close()}}
    }
    fun clear(context:Context){cancel();File(context.cacheDir,"online-subtitles-v1").listFiles()?.forEach{it.delete()}}
    internal fun attach(player:Player,file:File){
        val item=player.currentMediaItem?:return
        val mime=when(file.extension){"ass","ssa"->MimeTypes.TEXT_SSA;"vtt"->MimeTypes.TEXT_VTT;else->MimeTypes.APPLICATION_SUBRIP}
        val caption=MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(mime).setLanguage("zh").setLabel(if(file.name.startsWith("sidecar-"))"同目录字幕 · 自动匹配"else"射手字幕 · 自动匹配").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val index=player.currentMediaItemIndex;val position=player.currentPosition;val playing=player.playWhenReady
        val items=(0 until player.mediaItemCount).map{if(it==index)item.buildUpon().setSubtitleConfigurations((listOf(caption)+item.localConfiguration?.subtitleConfigurations.orEmpty()).distinctBy{it.uri}).build()else player.getMediaItemAt(it)}
        player.replaceMediaItem(index,items[index])
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage("zh").build()
        player.playWhenReady=playing
    }
}

internal object EnrichmentDeadlines {
    val timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
}
