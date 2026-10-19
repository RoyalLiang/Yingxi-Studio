package io.localplayer.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class Draft(val source:Source=Source(label="",protocol=Protocol.SMB,host=""),val port:String=source.port.toString(),val password:String="",val remember:Boolean=true)
data class PlayTarget(val uri:Uri,val title:String,val resume:Long,val media:MediaRef,val historyToken:HistoryToken?=null,val playId:String=java.util.UUID.randomUUID().toString())
private data class ReplayRequest(val entry:HistoryEntry,val fromStart:Boolean,val token:HistoryToken)

class LibraryViewModel(app:Application):AndroidViewModel(app) {
    private val store=SourceStore(app)
    private val historyStore=HistoryStore(app)
    var sources by mutableStateOf(store.load());private set
    var history by mutableStateOf(emptyList<HistoryEntry>());private set
    var candidates by mutableStateOf(emptyList<Candidate>());private set
    var discoveryMessage by mutableStateOf("发现当前局域网中的 SMB / SFTP 服务");private set
    var scanning by mutableStateOf(false);private set
    var source by mutableStateOf<Source?>(null);private set
    var path by mutableStateOf("");private set
    var entries by mutableStateOf(emptyList<Entry>());private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null)
    var draft by mutableStateOf<Draft?>(null)
    var trust by mutableStateOf<HostKeyRequired?>(null);private set
    private var credentials:Credentials?=null
    private var pendingReplay:ReplayRequest?=null
    private var discovery:Discovery?=null
    private var discoveryJob:Job?=null
    private var probe:LocalProbe?=null
    private var job:Job?=null
    private var cancellation:Cancellation?=null
    @Volatile private var generation=0L
    private val playChannel=Channel<PlayTarget>(Channel.BUFFERED)
    val plays=playChannel.receiveAsFlow()
    init { refreshHistory() }

    fun refreshHistory(){runCatching{historyStore.entries()}.onSuccess{history=it}.onFailure{error=it.message}}
    fun removeHistory(entry:HistoryEntry){if(pendingReplay?.entry?.media?.key==entry.media.key)dismissDraft();runCatching{historyStore.remove(entry.media.key);store.forgetPosition(entry.media.key)}.onFailure{error=it.message};refreshHistory()}
    fun clearHistory(){if(pendingReplay!=null)dismissDraft();historyStore.clear();store.clearPositions();refreshHistory()}
    fun discover(){
        stopDiscovery();candidates=emptyList();scanning=true;discoveryMessage="正在发现附近服务…"
        discovery=Discovery(getApplication(),::onCandidate,{discoveryMessage=it}).also{it.start()}
        discoveryJob=viewModelScope.launch{delay(10_000);stopDiscovery();discoveryMessage="发现结束；未广播的服务器可手动添加"}
    }
    private fun onCandidate(c:Candidate){candidates=(candidates.filterNot{it.id==c.id}+c).sortedBy{it.name.lowercase()}}
    fun stopDiscovery(){discoveryJob?.cancel();discoveryJob=null;discovery?.close();discovery=null;probe?.close();probe=null;scanning=false}
    fun probe(){stopDiscovery();scanning=true;discoveryMessage="补充发现中 · 仅当前局域网 22/445 端口"
        probe=LocalProbe(getApplication(),::onCandidate,{discoveryMessage=it;scanning=false}).also{it.start()}}
    fun edit(saved:Source){
        cancelOperation();pendingReplay=null;draft=Draft(saved)
        viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){store.password(saved.id)}
            if(draft?.source?.id==saved.id&&draft?.password.isNullOrEmpty())draft=draft?.copy(password=pass ?: "")
        }catch(e:Exception){error=e.message}}
    }
    fun add(candidate:Candidate?=null){
        cancelOperation();pendingReplay=null
        val protocol=candidate?.protocol ?: Protocol.SMB
        draft=Draft(Source(label=candidate?.name ?: "",protocol=protocol,host=candidate?.host ?: "",port=candidate?.port ?: if(protocol==Protocol.SMB)445 else 22,root=if(protocol==Protocol.SFTP)"/"else""))
    }
    fun connectDraft(){
        val d=draft ?: return
        try{
            val s=d.source.copy(host=d.source.host.trim().removeSurrounding("[","]"),label=d.source.label.trim().ifEmpty{d.source.host.trim()},port=d.port.toIntOrNull() ?: throw IllegalArgumentException("端口应为数字"),root=d.source.root.trim())
            s.validate();if(s.protocol==Protocol.SFTP)require(s.username.isNotBlank()){"请填写 SFTP 用户名"}
            val parent=pendingReplay?.takeIf{it.entry.media.sourceId==s.id}?.entry?.media?.path?.substringBeforeLast('/',"") ?: ""
            load(s,Credentials(d.password),parent,d.remember,true)
        }catch(e:IllegalArgumentException){error=e.message}
    }
    fun trustKey(){val t=trust ?: return
        draft=draft?.let{it.copy(source=it.source.copy(trustedKey=t.fingerprint,trustedAuthority=t.source.authority))}
        trust=null;connectDraft()
    }
    fun rejectKey(){trust=null;error="已拒绝该服务器，未发送密码认证"}
    fun browse(newPath:String){val s=source ?: return;val c=credentials ?: return;load(s,c,newPath,false,false)}
    private fun load(s:Source,c:Credentials,newPath:String,remember:Boolean,save:Boolean){
        cancelOperation();val id=++generation;val token=Cancellation();cancellation=token;busy=true;error=null
        job=viewModelScope.launch{
            try{
                val result=withContext(Dispatchers.IO){Protocols().connect(s,c,token).use{session ->
                    val list=session.list(newPath);token.check()
                    if(save)store.save(s,c.password,remember){id==generation}
                    list
                }}
                if(id==generation){
                    source=s;credentials=c;path=newPath;entries=result;draft=null;sources=store.load()
                    val replay=pendingReplay?.takeIf{it.entry.media.sourceId==s.id};pendingReplay=null
                    if(replay!=null){
                        val entry=result.firstOrNull{!it.directory&&it.path==replay.entry.media.path}
                    if(replay.entry.media.location!=null&&replay.entry.media.location!=mediaLocation(s))error="来源身份已变更，请从目录重新选择影片"
                    else if(entry==null)error="原文件不存在或不可读取，请在来源中重新选择"else play(entry,replay.fromStart,replay.token)
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(e:HostKeyRequired){if(id==generation)trust=e}
            catch(e:Exception){if(id==generation)error=if(e is IllegalArgumentException)e.message else "连接或读取失败，请核对网络、账户和目录权限（${e.javaClass.simpleName}）"}
            finally{if(id==generation){busy=false;cancellation=null}}
        }
    }
    fun cancelOperation(){generation++;job?.cancel();job=null;val old=cancellation;cancellation=null;busy=false
        if(old!=null)CoroutineScope(Dispatchers.IO).launch{old.close()}}
    fun closeSource(){cancelOperation();source=null;path="";entries=emptyList();credentials=null;pendingReplay=null;error=null}
    fun up(){if(path.isEmpty())closeSource()else browse(path.substringBeforeLast('/',""))}
    fun play(entry:Entry,fromStart:Boolean=false,requestedToken:HistoryToken?=null){
        val s=source ?: return;val c=credentials ?: return
        val ref=MediaRef("${s.id}:${entry.path}",entry.name,s.label,s.id,entry.path,sizeBytes=entry.size,location=mediaLocation(s))
        runCatching{
            val resume=if(fromStart)0L else runCatching{historyStore.resumePosition(ref,store.position(ref.key))}.getOrDefault(0L)
            val token=requestedToken ?: historyStore.token(ref.key)
            if(token!=historyStore.token(ref.key))return
            val uri=PlaybackRegistry.add(s,c,entry.path,entry.name)
            viewModelScope.launch{playChannel.send(PlayTarget(uri,entry.name,resume,ref,token))}
        }.onFailure{error=it.message}
    }
    fun resume(entry:HistoryEntry,fromStart:Boolean=false){
        error=null
        val ref=entry.media
        if(ref.localUri!=null){playLocal(Uri.parse(ref.localUri),fromStart);return}
        val saved=sources.firstOrNull{it.id==ref.sourceId}
        if(saved==null){error="这个来源已移除，请重新添加后选择影片";return}
        if(ref.location!=null&&ref.location!=mediaLocation(saved)){error="来源身份已变更，请从来源重新选择影片";return}
        cancelOperation();pendingReplay=ReplayRequest(entry,fromStart,historyStore.token(ref.key))
        val request=generation;val memoryPassword=credentials?.password?.takeIf{source?.id==saved.id}
        viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){memoryPassword ?: store.password(saved.id)}
            if(request!=generation)return@launch
            draft=Draft(saved,password=pass ?: "",remember=store.hasPassword(saved.id))
            if(pass!=null||saved.username.isBlank())connectDraft()
        }catch(e:Exception){if(request==generation){error="无法读取保存的凭据，请重新输入";draft=Draft(saved,remember=false)}}}
    }
    fun playLocal(uri:Uri,fromStart:Boolean=false){
        cancelOperation();pendingReplay=null;val request=generation
        val requestedToken=historyStore.token(uri.toString())
        viewModelScope.launch{try{
            val ref=withContext(Dispatchers.IO){
                require(uri.scheme=="content"){"请通过系统文件选择器打开视频"}
                val resolver=getApplication<Application>().contentResolver
                resolver.openAssetFileDescriptor(uri,"r")?.use{} ?: throw java.io.IOException("文件无法读取")
                var title="本机视频";var size:Long?=null
                resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor ->
                    if(cursor.moveToFirst()){
                        val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0)title=cursor.getString(n) ?: title
                        val z=cursor.getColumnIndex(OpenableColumns.SIZE);if(z>=0&&!cursor.isNull(z))size=cursor.getLong(z)
                    }
                }
                MediaRef(uri.toString(),title,"本机文件",localUri=uri.toString(),sizeBytes=size)
            }
            val resume=if(fromStart)0L else runCatching{historyStore.resumePosition(ref,store.position(ref.key))}.getOrDefault(0L)
            if(request==generation&&requestedToken==historyStore.token(ref.key))playChannel.send(PlayTarget(uri,ref.title,resume,ref,requestedToken))
        }catch(e:Exception){if(request==generation)error="本机文件无法读取，请通过“打开本机视频”重新选择"}}
    }
    fun dismissDraft(){cancelOperation();pendingReplay=null;trust=null;draft=null;error=null}
    fun deleteDraft(){cancelOperation();trust=null;pendingReplay=null;draft?.source?.id?.let{store.delete(it)};draft=null;sources=store.load()}
    override fun onCleared(){stopDiscovery();cancelOperation();super.onCleared()}
}
