package io.localplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.*
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest

internal class EnrichmentPreferences(context:Context){
    private val p=context.getSharedPreferences("media-enrichment-v1",0)
    var posters:Boolean get()=p.getBoolean("posters",true);set(v){p.edit{putBoolean("posters",v)}}
    var subtitles:Boolean get()=p.getBoolean("subtitles",true);set(v){p.edit{putBoolean("subtitles",v)}}
    var bilingual:Boolean get()=p.getBoolean("bilingual",false);set(v){p.edit{putBoolean("bilingual",v)}}
}
internal fun enrichmentKey(media:MediaRef)=MessageDigest.getInstance("SHA-256").digest(listOf(media.key,media.location,media.sizeBytes,media.localUri).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}

internal object MediaPosters {
    private val io=Dispatchers.IO.limitedParallelism(2)
    private val http=EnrichmentHttp()
    private val lookup=PosterLookup(http)
    private val failed=java.util.concurrent.ConcurrentHashMap<String,Long>()
    val changes=MutableStateFlow(0L)
    fun retryMissing(){revision.incrementAndGet();failed.clear();changes.update{it+1}}
    private val revision=java.util.concurrent.atomic.AtomicLong()
    internal var connector:Connector=AppConnectors()
    private val locks=Array(16){kotlinx.coroutines.sync.Mutex()}
    internal var onlineLookup:(MediaIdentity,Cancellation)->PosterMatch?={id,c->lookup.find(id,c)}
    private fun dir(c:Context)=File(c.cacheDir,"media-posters-v1").apply{mkdirs()}
    private const val posterPolicy = 2
    private const val posterLifetimeMs = 7L * 24 * 60 * 60 * 1000
    private fun cachedMetadata(c:Context,m:MediaRef):org.json.JSONObject? = runCatching {
        val data=org.json.JSONObject(File(dir(c),enrichmentKey(m)+".json").readText())
        val age=System.currentTimeMillis()-data.getLong("fetchedAt")
        data.takeIf{it.optInt("policy")==posterPolicy&&it.optString("title")==m.title&&age in 0 until posterLifetimeMs}
    }.getOrNull()
    fun credit(c:Context,m:MediaRef):Pair<String,String>?=runCatching{cachedMetadata(c,m)?.let{it.getString("credit") to it.getString("url")}}.getOrNull()
    internal fun decode(data:ByteArray):Bitmap?{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth !in 1..20000||bounds.outHeight !in 1..20000)return null
        val options=BitmapFactory.Options().apply{inSampleSize=1;while(bounds.outWidth/inSampleSize>800||bounds.outHeight/inSampleSize>1000)inSampleSize*=2}
        return BitmapFactory.decodeByteArray(data,0,data.size,options)
    }
    suspend fun load(context:Context,media:MediaRef):Bitmap?=coroutineScope {
        val cancellation=Cancellation()
        val worker=async(io){loadRequest(context,media,cancellation)}
        try{worker.await()}finally{withContext(NonCancellable+Dispatchers.IO){cancellation.close()}}
    }

    private suspend fun loadRequest(context:Context,media:MediaRef,c:Cancellation):Bitmap?=withContext(io){
        if(!EnrichmentPreferences(context).posters)return@withContext null
        val store=SourceStore(context)
        val source=media.sourceId?.let{id->store.load().firstOrNull{it.id==id}}
        if(media.sourceId!=null&&(source==null||media.location!=mediaLocation(source)))return@withContext null
        val id=enrichmentKey(media);val lock=locks[(id.hashCode()and Int.MAX_VALUE)%locks.size]
        lock.lock()
        try{
            val target=File(dir(context),"$id.jpg")
            if(target.isFile&&cachedMetadata(context,media)!=null)decode(target.readBytes())?.let{return@withContext it}
            target.delete();File(dir(context),"$id.json").delete()
            if(System.currentTimeMillis()-(failed[id]?:0)<6*60*60*1000)return@withContext null
            val token=HistoryStore(context).token(media.key)
        val generation=revision.get()
            val watcher=EnrichmentDeadlines.timer.schedule({c.close()},20,java.util.concurrent.TimeUnit.SECONDS)
            val owner=coroutineContext.job.invokeOnCompletion{if(it!=null)c.close()}
            try{
                var credit="目录海报";var url=""
                val local=runCatching{sidecar(context,media,source,c)}.getOrNull()
                val bitmap=local?.let(::decode)?:onlineLookup(MediaIdentity.parse(media.title),c)?.let{match->
                    credit=match.credit;url=match.sourceUrl
                    decode(http.get(match.imageUrl,c,5*1024*1024))
                }?:run{c.check();if(revision.get()==generation)failed[id]=System.currentTimeMillis();return@withContext null}
                ensureActive();c.check()
                if(revision.get()!=generation||HistoryStore(context).token(media.key)!=token||!EnrichmentPreferences(context).posters)return@withContext null
                val temp=File(dir(context),"$id.tmp")
                try{temp.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,88,it))};check(temp.renameTo(target))}finally{temp.delete()}
                File(dir(context),"$id.json").writeText(org.json.JSONObject().put("credit",credit).put("url",url).put("policy",posterPolicy).put("title",media.title).put("fetchedAt",System.currentTimeMillis()).toString())
                trim(context);bitmap
            }catch(e:CancellationException){throw e}catch(_:Exception){if(coroutineContext.isActive&&revision.get()==generation)failed[id]=System.currentTimeMillis();null}
            finally{watcher.cancel(false);owner.dispose();c.close()}
        }finally{lock.unlock()}
    }
    private fun sidecar(context:Context,media:MediaRef,source:Source?,c:Cancellation):ByteArray?{
        if(source!=null){
            val pass=SourceStore(context).password(source.id)
            if(pass==null&&source.username.isNotBlank())return null
            return connector.connect(source,Credentials(pass?:""),c).use{session->
                val entries=session.list(media.path?.substringBeforeLast('/',"")?:"")
                val names=posterSidecarNames(media.title,entries.filter{!it.directory}.map{it.name})
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
        val names=posterSidecarNames(media.title,found.keys.toList())
        val image=names.firstNotNullOfOrNull{found[it.lowercase()]}?:return null
        return context.contentResolver.openInputStream(image)?.use{it.readNBytes(5*1024*1024+1)}?.takeIf{it.size<=5*1024*1024}
    }
    private fun trim(c:Context){var bytes=0L;dir(c).listFiles()?.filter{it.extension=="jpg"}?.sortedByDescending{it.lastModified()}?.forEachIndexed{i,f->bytes+=f.length();if(i>=128||bytes>48L*1024*1024){f.delete();File(f.parentFile,f.nameWithoutExtension+".json").delete()}}}
    fun remove(c:Context,m:MediaRef){revision.incrementAndGet();val id=enrichmentKey(m);File(dir(c),"$id.jpg").delete();File(dir(c),"$id.json").delete();failed.remove(id)}
    fun clear(c:Context){revision.incrementAndGet();dir(c).listFiles()?.forEach{it.delete()};failed.clear()}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OnlineSubtitles {
    val status=MutableStateFlow<Pair<String,String>?>(null)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var attempted:String?=null
    private var active:Cancellation?=null
    private var generation=0L
    private fun stopRequest(){generation++;job?.cancel();job=null;val old=active;active=null;scope.launch(Dispatchers.IO){old?.close()}}
    internal var provider=ShooterSubtitles()
    fun cancel(){stopRequest();attempted=null;status.value=null}
    fun schedule(c:Context,player:Player){
        val item=player.currentMediaItem?:return
        if(attempted==item.mediaId||job?.isActive==true)return
        if(!EnrichmentPreferences(c).subtitles||PlaybackPreferences(c).subtitlesDisabled)return
        if(player.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT&&it.isSupported}||item.localConfiguration?.subtitleConfigurations?.isNotEmpty()==true)return
        attempted=item.mediaId
        job=scope.launch{
            repeat(10){
                delay(3000)
                if(player.currentMediaItem?.mediaId!=item.mediaId)return@launch
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
        val id=item.mediaId
        val parameters=player.trackSelectionParameters
        val preferBilingual=EnrichmentPreferences(context).bilingual
        val uri=item.localConfiguration?.uri?:return
        val media=item.mediaMetadata.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}?:return
        if(!force&&player.isPlaying&&player.bufferedPosition-player.currentPosition<5000)return
        status.value=id to "正在匹配字幕…"
        val cancellation=Cancellation()
        active=cancellation
        val requestGeneration=generation
        try{
            val caption=withContext(Dispatchers.IO){
                val dir=File(context.cacheDir,"online-subtitles-v1").apply{mkdirs()}
                val key=enrichmentKey(media)+(if(preferBilingual)"-bi"else"-zh")
                val cached=dir.listFiles()?.firstOrNull{it.nameWithoutExtension==key&&it.extension in setOf("srt","ass","ssa","vtt")}
                if(cached!=null)return@withContext cached
                val watcher=EnrichmentDeadlines.timer.schedule({cancellation.close()},22,java.util.concurrent.TimeUnit.SECONDS)
                val completion=coroutineContext.job.invokeOnCompletion{if(it!=null)cancellation.close()}
                try{
                    val file=if(uri.scheme=="localplayer"){
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
                    val matches=provider.search(file,media.title,preferBilingual,cancellation)
                var best:Pair<SubtitleMatch,ByteArray>?=null
                var bestScore=Int.MIN_VALUE
                for(candidate in matches.take(4)){
                    try{
                        val bytes=provider.download(candidate,cancellation);ensureActive();cancellation.check()
                        val score=provider.preferenceScore(bytes,preferBilingual)?:continue
                        if(score>bestScore){best=candidate to bytes;bestScore=score}
                    }catch(e:CancellationException){throw e}catch(_:Exception){cancellation.check()}
                }
                val chosen=best?:return@withContext null
                ensureActive();cancellation.check()
                val target=File(dir,key+"."+chosen.first.extension)
                val temporary=File(dir,"$key.tmp")
                try{temporary.writeBytes(chosen.second);check(temporary.renameTo(target))}finally{temporary.delete()}
                dir.listFiles()?.filter{it.name!=target.name}?.sortedByDescending{it.lastModified()}?.drop(63)?.forEach{it.delete()}
                target
                }finally{watcher.cancel(false);completion.dispose();cancellation.close()}
            }
            if(generation!=requestGeneration||player.currentMediaItem?.mediaId!=id||(!force&&PlaybackPreferences(context).subtitlesDisabled)||player.trackSelectionParameters!=parameters)return
            if(caption==null){status.value=id to "未找到匹配字幕";return}
            attach(player,caption)
            status.value=id to "已载入射手字幕"
        }catch(e:CancellationException){throw e}catch(_:Exception){if(generation==requestGeneration&&player.currentMediaItem?.mediaId==id)status.value=id to "字幕服务暂不可用，可重试"}
        finally{if(active===cancellation)active=null;withContext(NonCancellable+Dispatchers.IO){cancellation.close()}}
    }
    fun clear(context:Context){cancel();File(context.cacheDir,"online-subtitles-v1").listFiles()?.forEach{it.delete()}}
    internal fun attach(player:Player,file:File){
        val item=player.currentMediaItem?:return
        val mime=when(file.extension){"ass","ssa"->MimeTypes.TEXT_SSA;"vtt"->MimeTypes.TEXT_VTT;else->MimeTypes.APPLICATION_SUBRIP}
        val caption=MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(mime).setLanguage("zh").setLabel("射手字幕 · 自动匹配").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val index=player.currentMediaItemIndex;val position=player.currentPosition;val playing=player.playWhenReady
        val items=(0 until player.mediaItemCount).map{if(it==index)item.buildUpon().setSubtitleConfigurations(listOf(caption)).build()else player.getMediaItemAt(it)}
        player.setMediaItems(items,index,position)
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage("zh").build()
        player.prepare();player.playWhenReady=playing
    }
}

private object EnrichmentDeadlines {
    val timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
}
