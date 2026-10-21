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

data class Draft(val source:Source=Source(label="",protocol=Protocol.SMB,host=""),val port:String=source.port.toString(),val password:String="",val remember:Boolean=true,
    val browsePath:String?=null,val webDavUrl:String=if(source.protocol==Protocol.WEBDAV)source.webDavAddress else "https://",val allowHttp:Boolean=!source.secure)
data class SourceAccess(val source:Source,val password:String="",val remember:Boolean=false,val path:String="")
private data class ReplayRequest(val entry:HistoryEntry,val fromStart:Boolean,val token:HistoryToken)
private class ReplaySourceChanged:java.io.IOException("来源身份已变更，请从来源重新选择影片")
private class ReplayFileMissing:java.io.IOException("原文件不存在或不可读取，请在来源中重新选择")
private fun cloudFailureMessage(e:Exception):String = when(e) {
    is AliyunApiFailure,is AliyunUnauthorized,is ReplaySourceChanged,is ReplayFileMissing -> e.message ?: "阿里云盘读取失败，请重试"
    else -> "连接或读取失败，请核对网络、账户和目录权限（${e.javaClass.simpleName}）"
}

class LibraryViewModel internal constructor(app:Application,private val sourceConnector:Connector):AndroidViewModel(app) {
    constructor(app:Application):this(app,AppConnectors())
    private val store=SourceStore(app)
    val organization=SourceOrganization(app)
    var organizationRevision by mutableIntStateOf(0);private set
    var shareChoices by mutableStateOf<List<String>?>(null);private set
    var selectedGroup by mutableStateOf("all")
    var autoDiscoveryEnabled by mutableStateOf(app.getSharedPreferences("discovery",0).getBoolean("auto",false));private set
    var nearbyPermissionGranted by mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.NEARBY_WIFI_DEVICES)==android.content.pm.PackageManager.PERMISSION_GRANTED);private set
    private var discoveryEligible=false
    private var autoActive=false
    private var networkCallback:android.net.ConnectivityManager.NetworkCallback?=null
    private var lastNetwork:String?=null
    private val historyStore=HistoryStore(app)
    var sources by mutableStateOf(store.load());private set
    var pinnedIds by mutableStateOf(store.pinnedIds());private set
    var selectedSourceId by mutableStateOf<String?>(null);private set
    var access by mutableStateOf<SourceAccess?>(null);private set
    var openingSource by mutableStateOf<Source?>(null);private set
    var privateRequest by mutableStateOf<PrivateSourceRequest?>(null);private set
    private var discoveryAllowed=false
    private var discoveryEpoch=0L
    var history by mutableStateOf(emptyList<HistoryEntry>());private set
    var candidates by mutableStateOf(emptyList<Candidate>());private set
    var discoveryMessage by mutableStateOf("发现当前局域网中的 SMB、SFTP / SSH 和 WebDAV 服务");private set
    var scanning by mutableStateOf(false);private set
    var source by mutableStateOf<Source?>(null);private set
    var path by mutableStateOf("");private set
    private var currentFolderName="来源根目录"
    var entries by mutableStateOf(emptyList<Entry>());private set
    var busy by mutableStateOf(false);private set
    var preparingKey by mutableStateOf<String?>(null);private set
    var discoveryPanelOpen by mutableStateOf(false)
    var settingsSourceId by mutableStateOf<String?>(null); private set
    var rootSelectionId by mutableStateOf<String?>(null); private set
    private var noticeSerial = 0L
    internal var notice by mutableStateOf<SourceNotice?>(null); private set
    private var errorValue by mutableStateOf<String?>(null)
    var error: String?
        get() = errorValue
        set(value) {
            errorValue = value
            if (value != null) notice = SourceNotice(++noticeSerial, value, true)
            else if (notice?.isError == true) notice = null
        }
    internal fun notifySuccess(text: String) { errorValue = null; notice = SourceNotice(++noticeSerial, text, false) }
    internal fun dismissNotice(id: Long) {
        if (notice?.id != id) return
        if (notice?.isError == true) errorValue = null
        notice = null
    }
    fun switchDraftProtocol(protocol: Protocol) {
        val current = draft ?: return
        if (current.source.protocol == protocol || protocol == Protocol.ALIYUN) return
        stopDiscovery(); cancelOperation(); error = null; discoveryPanelOpen = false
        draft = current.copy(source = current.source.copy(protocol = protocol,
            root = if (protocol == Protocol.SMB) "" else "/", initialPath = "", trustedKey = null, trustedAuthority = null),
            port = when (protocol) { Protocol.SMB -> "445"; Protocol.SFTP -> "22"; else -> "443" }, browsePath = null)
    }
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
    private val playChannel=Channel<PlayTarget>(Channel.BUFFERED,onUndeliveredElement={it.release()})
    val plays=playChannel.receiveAsFlow()
    init { refreshHistory()
        val inventory=StationInventory(app)
        history.filter { h->h.media.sourceId?.let{id->sources.any{it.id==id&& (h.media.location==null||mediaLocation(it)==h.media.location)}}
            ?: h.media.localUri?.let{runCatching{app.checkUriPermission(Uri.parse(it),android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED}.getOrDefault(false)} ?: false
        }.asReversed().forEach{inventory.observe(listOf(it.media),it.updatedAt)}
    }

    fun refreshHistory(){runCatching{historyStore.entries()}.onSuccess{history=it}.onFailure{error=it.message}}
    fun removeHistory(entry:HistoryEntry){if(selectedHistoryKey==entry.media.key)selectedHistoryKey=null;if(pendingReplay?.entry?.media?.key==entry.media.key)dismissDraft();runCatching{historyStore.remove(entry.media.key);MediaThumbnails.remove(getApplication(),entry.media);MediaPosters.remove(getApplication(),entry.media);store.forgetPosition(entry.media.key)}.onFailure{error=it.message};refreshHistory()}
    fun clearHistory(){if(pendingReplay!=null)dismissDraft();historyStore.clear();selectedHistoryKey=null;MediaThumbnails.clear(getApplication());MediaPosters.clear(getApplication());OnlineSubtitles.clear(getApplication());store.clearPositions();refreshHistory()}
    fun setDiscoveryEnabled(enabled:Boolean){discoveryEligible=enabled;refreshDiscoveryPermission()}
    fun refreshDiscoveryPermission(){
        nearbyPermissionGranted=androidx.core.content.ContextCompat.checkSelfPermission(getApplication(),android.Manifest.permission.NEARBY_WIFI_DEVICES)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(discoveryEligible&&nearbyPermissionGranted)beginAutoDiscovery()else endAutoDiscovery()
    }
    private fun discoveryReady():Boolean {
        nearbyPermissionGranted=androidx.core.content.ContextCompat.checkSelfPermission(getApplication(),android.Manifest.permission.NEARBY_WIFI_DEVICES)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!nearbyPermissionGranted){discoveryAllowed=false;discoveryMessage="请先允许附近设备访问";return false}
        val cm=getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val caps=cm.activeNetwork?.let{cm.getNetworkCapabilities(it)}
        val local=caps!=null&&!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)&&(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
        if(!local){discoveryAllowed=false;discoveryMessage="请连接 Wi-Fi 或以太网后发现";return false}
        discoveryAllowed=true;return true
    }
    fun discover(supplement:Boolean=false){
        stopDiscovery();if(!discoveryReady())return;val epoch=discoveryEpoch
        candidates=emptyList();scanning=true;discoveryMessage="正在发现附近服务…"
        discovery=Discovery(getApplication(),{if(discoveryAllowed&&epoch==discoveryEpoch)onCandidate(it)},{if(discoveryAllowed&&epoch==discoveryEpoch)discoveryMessage=it}).also{it.start()}
        discoveryJob=viewModelScope.launch{delay(10_000);stopDiscovery();discoveryMessage="发现结束；未广播的服务器可手动添加";if(supplement&&autoActive&&autoDiscoveryEnabled)probe()}
    }
    private fun onCandidate(c:Candidate){candidates=(candidates.filterNot{it.id==c.id}+c).sortedBy{it.name.lowercase()}}
    fun stopDiscovery(){discoveryEpoch++;discoveryJob?.cancel();discoveryJob=null;discovery?.close();discovery=null;probe?.close();probe=null;scanning=false}
    fun probe(){
        stopDiscovery();if(!discoveryReady())return;val epoch=discoveryEpoch;scanning=true;discoveryMessage="补充发现中 · 仅当前局域网 22/445 端口"
        probe=LocalProbe(getApplication(),{if(discoveryAllowed&&epoch==discoveryEpoch)onCandidate(it)},
            {if(discoveryAllowed&&epoch==discoveryEpoch){discoveryMessage=it;scanning=false}}).also{it.start()}
    }
    fun togglePin(saved:Source){
        if(!store.togglePinned(saved.id)){error="最多置顶 3 个来源，请先取消一个置顶";return}
        pinnedIds=store.pinnedIds();sources=store.load();notifySuccess(if(saved.id in pinnedIds)"已置顶来源"else"已取消置顶")
    }
    fun removeSource(saved:Source){
        if(selectedSourceId==saved.id)selectedSourceId=null
        if(saved.protocol==Protocol.ALIYUN)AliyunCloud.accountRemoved(saved.id)
        if(pendingReplay?.entry?.media?.sourceId==saved.id)cancelOperation()
        if(source?.id==saved.id||openingSource?.id==saved.id)closeSource()
        if(draft?.source?.id==saved.id)dismissDraft()
        if(access?.source?.id==saved.id)dismissAccess()
        if(privateRequest?.source?.id==saved.id)privateRequest=null
        StationInventory(getApplication()).removeSource(saved.id);organization.removeSource(saved.id);organizationRevision++;store.delete(saved.id);pinnedIds=store.pinnedIds();sources=store.load();notifySuccess("已移除来源")
    }
    fun privateAccess(saved:Source){
        stopDiscovery();cancelOperation();draft=null;access=null;openingSource=null;privateRequest=PrivateSourceRequest(saved)
    }
    fun completePrivateAccess(id:String){val request=privateRequest?.takeIf{it.id==id} ?: return;privateRequest=null;open(request.source)}
    fun cancelPrivateAccess(id:String,message:String?=null){if(privateRequest?.id!=id)return;privateRequest=null;if(message!=null)error=message}
    fun open(saved:Source,location:String=saved.initialPath,selectRoot:Boolean=false){
        selectedSourceId=saved.id
        rootSelectionId=if(selectRoot)saved.id else null
        stopDiscovery();cancelOperation();privateRequest=null;pendingReplay=null;draft=null;access=null;openingSource=saved
        val request=generation
        val memoryPassword=credentials?.password?.takeIf{source?.id==saved.id}
        source=null;credentials=null;path="";entries=emptyList()
        viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){memoryPassword ?: store.password(saved.id)}
                if(saved.protocol==Protocol.ALIYUN && pass==null){openingSource=null;preparingKey=null;error="阿里云盘授权已失效，请在来源的配置按钮重新授权";return@launch}
            if(request!=generation)return@launch
            if(pass!=null||saved.username.isBlank())load(saved,Credentials(pass ?: ""),location,false,false)
            else{openingSource=null;access=SourceAccess(saved,path=location)}
        }catch(e:Exception){if(request==generation){openingSource=null;if(saved.protocol!=Protocol.ALIYUN)access=SourceAccess(saved,path=location);error=if(saved.protocol==Protocol.ALIYUN)"网盘凭据无法读取，请重新授权"else"无法读取保存的密码，请重新认证"}}}
    }
    fun cloudConnected(saved:Source){sources=store.load();val current=sources.firstOrNull{it.id==saved.id} ?: return;selectedGroup="all";error=null;draft=null;open(current)}
    fun cloudReauthorize(){viewModelScope.launch{try{cloudConnected(AliyunCloud.authorize())}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "网盘授权失败"}}}
    fun updateAccess(password:String?=null,remember:Boolean?=null){access=access?.let{it.copy(password=password ?: it.password,remember=remember ?: it.remember)}}
    fun connectAccess(){val current=access ?: return;openingSource=current.source;load(current.source,Credentials(current.password),current.path,current.remember,true)}
    fun dismissAccess(){cancelOperation();pendingReplay=null;trust=null;access=null;openingSource=null;rootSelectionId=null;error=null}
    fun edit(saved:Source){
        if(saved.protocol==Protocol.ALIYUN){ cloudReauthorize(); return }
        stopDiscovery();cancelOperation();privateRequest=null;pendingReplay=null;access=null;openingSource=null;draft=Draft(saved)
        viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){store.password(saved.id)}
            if(draft?.source?.id==saved.id&&draft?.password.isNullOrEmpty())draft=draft?.copy(password=pass ?: "")
        }catch(e:Exception){error=e.message}}
    }
    fun add(candidate:Candidate?=null){
        discoveryPanelOpen=false
        stopDiscovery();cancelOperation();privateRequest=null;pendingReplay=null;access=null;openingSource=null
        val protocol=candidate?.protocol ?: Protocol.SMB
        draft=Draft(Source(label=candidate?.name ?: "",protocol=protocol,host=candidate?.host ?: "",port=candidate?.port ?: if(protocol==Protocol.SMB)445 else 22,root=candidate?.root?:if(protocol==Protocol.SFTP)"/"else"",secure=candidate?.secure?:true),allowHttp=false)
    }
    fun connectDraft(){
        val d=draft?:return
        try {
            var s=if(d.source.protocol==Protocol.WEBDAV){
                val parsed=d.source.withWebDavAddress(d.webDavUrl);require(parsed.secure||d.allowHttp){"HTTP 不加密，请先确认允许明文连接"};parsed.copy(label=parsed.label.trim().ifEmpty{parsed.host})
            }else d.source.copy(host=d.source.host.trim().removeSurrounding("[","]"),label=d.source.label.trim().ifEmpty{d.source.host.trim()},port=d.port.toIntOrNull()?:throw IllegalArgumentException("端口应为数字"),root=d.source.root.trim())
            if(s.protocol==Protocol.SMB && (s.host.contains("://")||s.host.startsWith("\\")||s.host.startsWith("//")||s.host.contains('/'))){
                val address=SmbAddress.parse(s.host)
                s=s.copy(host=address.host,port=if(address.port!=445||d.port=="445")address.port else s.port,root=address.share.ifEmpty{s.root},initialPath=address.path,label=d.source.label.trim().ifEmpty{address.host})
                draft=d.copy(source=s,port=s.port.toString())
            }
            if(s.protocol==Protocol.SMB && s.root.isBlank()) { enumerateShares(s,Credentials(d.password));return }
            s.validate();if(s.protocol==Protocol.SFTP)require(s.username.isNotBlank()){"请填写 SFTP 用户名"}
            val parent=pendingReplay?.takeIf{it.entry.media.sourceId==s.id}?.entry?.media?.path?.substringBeforeLast('/',"") ?: d.browsePath ?: s.initialPath
            load(s,Credentials(d.password),parent,d.remember,true)
        } catch(e:IllegalArgumentException){error=e.message}
    }
    private fun enumerateShares(s:Source,c:Credentials){
        cancelOperation();val id=generation;val token=Cancellation();cancellation=token;busy=true;error=null
        job=viewModelScope.launch { try {
            val names=withContext(Dispatchers.IO){SmbShares.list(s,c,token)}
            if(id==generation){shareChoices=names;if(names.isEmpty())error="服务器未返回共享，请手动填写共享名"}
        }catch(e:CancellationException){throw e}catch(e:Exception){if(id==generation)error="无法列出共享，请核对账户或在高级选项填写共享名（${e.javaClass.simpleName}）"}
        finally{withContext(NonCancellable+Dispatchers.IO){token.close()};if(id==generation){busy=false;cancellation=null}} }
    }
    fun chooseShare(name:String){draft=draft?.let{it.copy(source=it.source.copy(root=name))};shareChoices=null;connectDraft()}
    fun closeShares(){shareChoices=null}
    fun trustKey(){val t=trust ?: return
        draft=draft?.let{it.copy(source=it.source.copy(trustedKey=t.fingerprint,trustedAuthority=t.source.authority))}
        if(draft!=null){trust=null;connectDraft()}else{
            access=access?.let{it.copy(source=it.source.copy(trustedKey=t.fingerprint,trustedAuthority=t.source.authority))}
            trust=null;connectAccess()
        }
    }
    fun rejectKey(){trust=null;error="已拒绝该服务器，未发送密码认证"}
    fun browse(newPath:String){val s=source ?: return;val c=credentials ?: return;load(s,c,newPath,false,false)}
    private fun load(s:Source,c:Credentials,newPath:String,remember:Boolean,save:Boolean){
        if(pendingReplay?.entry?.media?.sourceId==s.id){prepareReplay(s,c,remember,save);return}
        val folderName=if(newPath.isEmpty())"来源根目录"else
            entries.firstOrNull{source?.id==s.id&&it.directory&&it.path==newPath}?.name
                ?: if(newPath==s.initialPath)store.startingFolderName(s)
                else if(s.protocol==Protocol.ALIYUN)AliyunCloud.folderName(s.id,newPath.substringAfterLast('/'))else newPath.substringAfterLast('/')
        stopDiscovery();cancelOperation();val id=generation;val token=Cancellation();cancellation=token;busy=true;error=null
        job=viewModelScope.launch{
            try{
                val result=withContext(Dispatchers.IO){sourceConnector.connect(s,c,token).use{session->
                    val list=session.list(newPath);token.check()
                    if(save)store.save(s,c.password,remember){id==generation}
                    list
                }}
                if(id!=generation)return@launch
                source=s;openingSource=null;credentials=c;path=newPath;currentFolderName=folderName;entries=result;draft=null;access=null;sources=store.load()
                StationInventory(getApplication()).reconcileSource(s)
                val registered=StationInventory(getApplication()).observe(result.filter{!it.directory&&it.isVideo}.map { e->MediaRef("${s.id}:${e.path}",e.name,s.label,s.id,e.path,sizeBytes=e.size,location=mediaLocation(s)) })
                if(save)notifySuccess("来源已保存") else if(registered>0&&StationPreferences(getApplication()).greetings)notifySuccess("阿汐：这批影像登记好了，片架给你留了位置。")
            }catch(e:CancellationException){throw e}
            catch(e:HostKeyRequired){if(id==generation){trust=e;if(draft==null)access=SourceAccess(s,c.password,store.hasPassword(s.id),newPath)}}
            catch(e:Exception){if(id==generation)error=when {
                s.protocol==Protocol.ALIYUN -> cloudFailureMessage(e)
                s.protocol!=Protocol.ALIYUN && e is IllegalArgumentException -> e.message
                else -> "连接或读取失败，请核对网络、账户和目录权限（${e.javaClass.simpleName}）"
            }}
            finally{if(id==generation){busy=false;cancellation=null;openingSource=null}}
        }
    }
    fun cancelOperation(keepReplay:Boolean=false){
        generation++;job?.cancel();job=null
        val old=cancellation;cancellation=null;busy=false;preparingKey=null
        if(!keepReplay)pendingReplay=null
        if(old!=null)CoroutineScope(Dispatchers.IO).launch{old.close()}
    }
    fun closeSource(){cancelOperation();access=null;openingSource=null;source=null;path="";entries=emptyList();credentials=null;pendingReplay=null;rootSelectionId=null;error=null}
    fun currentDirectoryBookmark():SourceBookmark?=source?.let{SourceBookmark(it.id,path,if(path.isEmpty())it.label else currentFolderName)}
    fun up(){if(path.isEmpty()||rootSelectionId==null&&path==source?.initialPath)closeSource()else browse(path.substringBeforeLast('/',""))}
    private fun remoteTarget(s:Source,c:Credentials,entry:Entry,listing:List<Entry>,fromStart:Boolean,requestedToken:HistoryToken?=null):PlayTarget? {
        val ref=MediaRef("${s.id}:${entry.path}",entry.name,s.label,s.id,entry.path,sizeBytes=entry.size,location=mediaLocation(s))
        val token=requestedToken ?: historyStore.token(ref.key)
        if(token!=historyStore.token(ref.key))return null
        val resume=if(fromStart)0L else runCatching{historyStore.resumePosition(ref,store.position(ref.key))}.getOrDefault(0L)
        val videos=listing.filter{!it.directory&&it.isVideo}
        val siblings=PlaybackRequests.window(videos,entry)
        val queue=mutableListOf<QueuedMedia>()
        try {
            for(e in siblings){
                val item=MediaRef("${s.id}:${e.path}",e.name,s.label,s.id,e.path,sizeBytes=e.size,location=mediaLocation(s))
                queue+=QueuedMedia(PlaybackRegistry.add(s,c,e.path,e.name),item,if(e.path==entry.path)token else historyStore.token(item.key))
            }
            val selected=queue.first{it.media.path==entry.path}
            return PlayTarget(selected.uri,entry.name,resume,ref,token,queue=queue,truncated=videos.size>siblings.size)
        }catch(e:Exception){queue.forEach{PlaybackRegistry.remove(it.uri)};throw e}
    }
    fun play(entry:Entry,fromStart:Boolean=false,requestedToken:HistoryToken?=null){
        val s=source ?: return;val c=credentials ?: return
        runCatching{
            val target=remoteTarget(s,c,entry,entries,fromStart,requestedToken) ?: return
            viewModelScope.launch{try{playChannel.send(target)}catch(e:CancellationException){target.release();throw e}}
        }.onFailure{error=it.message}
    }
    private fun prepareReplay(s:Source,c:Credentials,remember:Boolean=false,save:Boolean=false){
        val replay=pendingReplay ?: return
        cancelOperation(keepReplay=true)
        val id=generation;val token=Cancellation();cancellation=token;preparingKey=replay.entry.media.key;error=null
        job=viewModelScope.launch{
            var prepared:PlayTarget?=null
            try {
                withContext(Dispatchers.IO){
                    if(replay.entry.media.location!=null&&replay.entry.media.location!=mediaLocation(s))throw ReplaySourceChanged()
                    val parent=replay.entry.media.path?.substringBeforeLast('/',"") ?: ""
                    val listing=sourceConnector.connect(s,c,token).use{it.list(parent)}
                    token.check()
                    val file=listing.firstOrNull{!it.directory&&it.isVideo&&it.path==replay.entry.media.path}
                        ?: throw ReplayFileMissing()
                    if(save)store.save(s,c.password,remember){id==generation}
                    prepared=remoteTarget(s,c,file,listing,replay.fromStart,replay.token)
                }
                if(id!=generation||replay.token!=historyStore.token(replay.entry.media.key))return@launch
                prepared?.let{playChannel.send(it);prepared=null}
                pendingReplay=null;access=null;openingSource=null
                if(save)sources=store.load()
            }catch(e:CancellationException){throw e}
            catch(e:HostKeyRequired){if(id==generation){trust=e;access=SourceAccess(s,c.password,store.hasPassword(s.id),replay.entry.media.path?.substringBeforeLast('/',"") ?: "")}}
            catch(e:Exception){if(id==generation){error=if(s.protocol==Protocol.ALIYUN)cloudFailureMessage(e)else e.message ?: "无法准备播放，请重试"}}
            finally{
                prepared?.release()
                withContext(NonCancellable+Dispatchers.IO){token.close()}
                if(id==generation){preparingKey=null;cancellation=null;openingSource=null}
            }
        }
    }
    var selectedHistoryKey by mutableStateOf<String?>(null);private set
    fun resume(entry:HistoryEntry,fromStart:Boolean=false){
        selectedHistoryKey=entry.media.key
        if(preparingKey==entry.media.key)return
        error=null
        val ref=entry.media
        if(ref.localUri!=null){playLocal(Uri.parse(ref.localUri),fromStart);return}
        val saved=sources.firstOrNull{it.id==ref.sourceId}
        if(saved==null){error="这个来源已移除，请重新添加后选择影片";return}
        if(ref.location!=null&&ref.location!=mediaLocation(saved)){error="来源身份已变更，请从来源重新选择影片";return}
        stopDiscovery();cancelOperation();draft=null;access=null;pendingReplay=ReplayRequest(entry,fromStart,historyStore.token(ref.key))
        val request=generation;preparingKey=ref.key
        val memoryPassword=credentials?.password?.takeIf{source?.id==saved.id}
        job=viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){memoryPassword ?: store.password(saved.id)}
                if(saved.protocol==Protocol.ALIYUN && pass==null){openingSource=null;preparingKey=null;error="阿里云盘授权已失效，请在来源的配置按钮重新授权";return@launch}
            if(request!=generation)return@launch
            if(pass!=null||saved.username.isBlank())prepareReplay(saved,Credentials(pass ?: ""))
            else {preparingKey=null;access=SourceAccess(saved,path=ref.path?.substringBeforeLast('/',"") ?: "")}
        }catch(e:CancellationException){throw e}
        catch(e:Exception){if(request==generation){preparingKey=null;error=if(saved.protocol==Protocol.ALIYUN)"网盘凭据无法读取，请在来源配置重新授权"else"无法读取保存的凭据，请重新输入";if(saved.protocol!=Protocol.ALIYUN)access=SourceAccess(saved,path=ref.path?.substringBeforeLast('/',"") ?: "")}}}
    }
    fun playLocal(uri:Uri,fromStart:Boolean=false,siblings:List<LocalEntry> = emptyList()){
        cancelOperation();pendingReplay=null;val request=generation;preparingKey=uri.toString()
        val requestedToken=historyStore.token(uri.toString())
        job=viewModelScope.launch{try{
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
            if(request==generation&&requestedToken==historyStore.token(ref.key)){
                val videos=siblings.filter{!it.directory&&it.video}
                val selected=videos.firstOrNull{it.uri==uri}
                val window=if(selected!=null)PlaybackRequests.window(videos,selected)else emptyList()
                val queue=window.map{e->
                    val item=if(e.uri==uri)ref else MediaRef(e.uri.toString(),e.name,"本机文件",localUri=e.uri.toString(),sizeBytes=e.size)
                    QueuedMedia(e.uri,item,if(e.uri==uri)requestedToken else historyStore.token(item.key))
                }
                playChannel.send(PlayTarget(uri,ref.title,resume,ref,requestedToken,queue=queue,truncated=videos.size>window.size&&window.isNotEmpty()))
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(request==generation)error="本机文件无法读取，请通过“打开本机视频”重新选择"}finally{if(request==generation)preparingKey=null}}
    }
    fun dismissDraft(){discoveryPanelOpen=false;stopDiscovery();shareChoices=null;cancelOperation();pendingReplay=null;trust=null;draft=null;error=null}
    fun deleteDraft(){discoveryPanelOpen=false;stopDiscovery();draft?.source?.id?.let{organization.removeSource(it);organizationRevision++};cancelOperation();trust=null;pendingReplay=null;draft?.source?.id?.let{StationInventory(getApplication()).removeSource(it);store.delete(it)};draft=null;sources=store.load();pinnedIds=store.pinnedIds();notifySuccess("已移除来源")}
    fun putBookmark(item:SourceBookmark){
        error=null
        val before=organization.bookmarks().firstOrNull{it.key==item.key}
        runCatching{organization.put(item);organizationRevision++}.onSuccess{
            notifySuccess(when{item.favorite!= (before?.favorite ?: false)->if(item.favorite)"已收藏"else"已取消收藏";else->"快捷入口已保存"})
        }.onFailure{error=it.message ?: "快捷入口未保存，请重试"}
    }
    fun toggleFavorite(item:SourceBookmark){
        val current=organization.bookmarks().firstOrNull{it.key==item.key} ?: item.copy(favorite=false)
        putBookmark(current.copy(favorite=!current.favorite))
    }
    fun showSourceSettings(saved:Source){settingsSourceId=saved.id}
    fun dismissSourceSettings(){settingsSourceId=null}
    fun startingFolderName(saved:Source)=store.startingFolderName(saved)
    fun updateSourcePresentation(saved:Source,label:String,initialPath:String=saved.initialPath,folderName:String=startingFolderName(saved),announce:Boolean=true):Boolean {
        return runCatching{
            val current=store.load().firstOrNull{it.id==saved.id} ?: throw IllegalArgumentException("这个来源已移除")
            val clean=Paths.clean(initialPath)
            store.updatePresentation(current.id,label,clean,folderName)
            organization.moveSourceEntry(current.id,current.initialPath,clean,label.trim());organizationRevision++
            sources=store.load()
            if(source?.id==current.id)source=sources.first{it.id==current.id}
            if(announce)notifySuccess("来源设置已保存")
        }.onFailure{error=it.message ?: "来源设置未保存，请重试"}.isSuccess
    }
    fun beginRootSelection(saved:Source){settingsSourceId=null;open(saved,"",selectRoot=true)}
    fun useCurrentFolder(){
        val current=source?.takeIf{it.id==rootSelectionId} ?: return
        if(busy||openingSource!=null)return
        val name=currentFolderName
        if(updateSourcePresentation(current,current.label,path,name,announce=false)){closeSource();notifySuccess("默认目录已设置")}
    }
    fun removeBookmark(key:String){organization.remove(key);organizationRevision++;notifySuccess("已移除快捷入口")}
    fun saveGroup(name:String,id:String?=null){error=null;runCatching{organization.group(name,id);organizationRevision++}.onSuccess{notifySuccess("已保存")}.onFailure{error=it.message}}
    fun deleteGroup(id:String){organization.deleteGroup(id);organizationRevision++;if(selectedGroup==id)selectedGroup="all";notifySuccess("已删除分组")}
    fun openLocation(item:SourceBookmark){sources.firstOrNull{it.id==item.sourceId}?.let{open(it,item.path)}}
    fun setAutoDiscovery(value:Boolean){autoDiscoveryEnabled=value;getApplication<Application>().getSharedPreferences("discovery",0).edit().putBoolean("auto",value).apply();if(!value)stopDiscovery()else if(autoActive)discover(true)}
    fun beginAutoDiscovery(){
        if(autoActive)return;autoActive=true;discoveryAllowed=true
        val app=getApplication<Application>()
        val cm=app.getSystemService(android.net.ConnectivityManager::class.java)
        val callback=object:android.net.ConnectivityManager.NetworkCallback(){
            override fun onCapabilitiesChanged(network:android.net.Network,caps:android.net.NetworkCapabilities){
                val local=(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))&&!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                android.os.Handler(android.os.Looper.getMainLooper()).post{
                    val key=network.toString()
                    if(autoActive&&autoDiscoveryEnabled&&local&&lastNetwork!=key){lastNetwork=key;discover(true)}else if(autoActive&&!local){lastNetwork=null;stopDiscovery();candidates=emptyList();discoveryMessage="当前网络不进行局域网探测"}
                }
            }
            override fun onLost(network:android.net.Network){android.os.Handler(android.os.Looper.getMainLooper()).post{if(autoActive&&lastNetwork==network.toString()){lastNetwork=null;stopDiscovery();candidates=emptyList();discoveryMessage="网络已断开"}}}
        }
        networkCallback=callback
        runCatching{cm.registerDefaultNetworkCallback(callback)}.onFailure{discoveryMessage="无法观察网络，可手动发现"}
    }
    fun endAutoDiscovery(){autoActive=false;discoveryAllowed=false;lastNetwork=null;networkCallback?.let{runCatching{getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it)}};networkCallback=null;stopDiscovery()}
    override fun onCleared(){endAutoDiscovery();playChannel.cancel();stopDiscovery();cancelOperation();super.onCleared()}
}
