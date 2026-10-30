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
import kotlinx.coroutines.flow.collectLatest

data class Draft(val source:Source=Source(label="",protocol=Protocol.SMB,host=""),val port:String=source.port.toString(),val password:String="",val remember:Boolean=true,
    val browsePath:String?=null,val webDavUrl:String=if(source.protocol==Protocol.WEBDAV&&source.host.isNotBlank())source.webDavAddress else "https://",val allowHttp:Boolean=!source.secure)
data class SourceAccess(val source:Source,val password:String="",val remember:Boolean=false,val path:String="")
private data class ReplayRequest(val entry:HistoryEntry,val fromStart:Boolean,val token:HistoryToken,val positionMs:Long?=null)
private class ReplaySourceChanged:java.io.IOException("信源身份已变更，请从信源重新选择影片")
private class ReplayFileMissing:java.io.IOException("原文件不存在或不可读取，请在信源中重新选择")
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
    private var historyRows by mutableStateOf(emptyList<HistoryEntry>())
    private var historyPolicy by mutableLongStateOf(-1L)
    private var historyRefresh by mutableIntStateOf(0)
    val history:List<HistoryEntry> get()=if(historyPolicy==SealedMedia.version())historyRows else emptyList()
    var candidates by mutableStateOf(emptyList<Candidate>());private set
    var discoveryMessage by mutableStateOf("发现当前局域网中的 SMB、SFTP / SSH 和 WebDAV 服务");private set
    var scanning by mutableStateOf(false);private set
    var source by mutableStateOf<Source?>(null);private set
    var path by mutableStateOf("");private set
    private var currentFolderName="信源根目录"
    private var directoryEntries by mutableStateOf<List<Entry>>(emptyList())
    internal var privateBrowse by mutableStateOf(false);private set
    val entries:List<Entry> get(){val current=source?:return emptyList();val root=SourceRoot(if(rootSelectionId!=null)""else current.initialPath);return if(!root.contains(path))emptyList()else directoryEntries.filter{root.contains(it.path)&&SealedMedia.visible(current,it.path,privateBrowse)}}
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
    internal fun notifyRegistration(){
        if(StationPreferences(getApplication()).greetings){errorValue=null;notice=SourceNotice(++noticeSerial,"这批影像登记好了，片架给你留了位置。",false,true)}
    }
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
    private var preparationLease:java.io.Closeable?=null
    internal var legacyReplay by mutableStateOf<HistoryEntry?>(null);private set
    private var legacyReplayAction:(()->Unit)?=null
    internal fun dismissLegacyReplay(){legacyReplay=null;legacyReplayAction=null}
    internal fun confirmLegacyReplay(){val action=legacyReplayAction;dismissLegacyReplay();action?.invoke()}
    private fun endPreparation(){preparationLease?.close();preparationLease=null}
    private var discovery:Discovery?=null
    private var discoveryJob:Job?=null
    private var probe:LocalProbe?=null
    private var privateLocalSignal:android.os.CancellationSignal?=null
    private var job:Job?=null
    private var cancellation:Cancellation?=null
    @Volatile private var generation=0L
    private val playChannel=Channel<PlayTarget>(Channel.BUFFERED,onUndeliveredElement={it.release()})
    val plays=playChannel.receiveAsFlow()
    init {
        viewModelScope.launch {
            snapshotFlow{SealedMedia.version() to historyRefresh}.collectLatest{(policy,_)->
                try {
                    val next=withContext(Dispatchers.IO){SealedMedia.publicItems(historyStore.entries()){it.media}}
                    SealedMedia.withVersion(policy){historyPolicy=policy;historyRows=next}
                }catch(cancel:CancellationException){throw cancel}
                catch(failure:Exception){if(policy==SealedMedia.version())error=failure.message}
            }
        }
        val inventory=StationInventory(app)
        inventory.restore(runCatching{historyStore.entries()}.getOrDefault(emptyList()).filter { h->h.media.sourceId?.let{id->sources.any{it.id==id&& (h.media.location==null||mediaLocation(it)==h.media.location)}}
            ?: h.media.localUri?.let{runCatching{app.checkUriPermission(Uri.parse(it),android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED}.getOrDefault(false)} ?: false
        })

    }

    fun refreshHistory(){historyRows=emptyList();historyRefresh++}
    fun removeHistory(entry:HistoryEntry){if(selectedHistoryKey==entry.media.key)selectedHistoryKey=null;if(pendingReplay?.entry?.media?.key==entry.media.key)dismissDraft();runCatching{historyStore.remove(entry.media);MediaThumbnails.remove(getApplication(),entry.media);MediaPosters.remove(getApplication(),entry.media);store.forgetPosition(entry.media.key)}.onFailure{error=it.message};refreshHistory()}
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
        if(!store.togglePinned(saved.id)){error="最多置顶 3 个信源，请先取消一个置顶";return}
        pinnedIds=store.pinnedIds();sources=store.load();notifySuccess(if(saved.id in pinnedIds)"已置顶信源"else"已取消置顶")
    }
    fun removeSource(saved:Source){
        if(selectedSourceId==saved.id)selectedSourceId=null
        if(saved.protocol==Protocol.ALIYUN)AliyunCloud.accountRemoved(saved.id)
        if(pendingReplay?.entry?.media?.sourceId==saved.id)cancelOperation()
        if(source?.id==saved.id||openingSource?.id==saved.id)closeSource()
        if(draft?.source?.id==saved.id)dismissDraft()
        if(access?.source?.id==saved.id)dismissAccess()
        if(privateRequest?.source?.id==saved.id)privateRequest=null
        StationInventory(getApplication()).removeSource(saved.id);organization.removeSource(saved.id);organizationRevision++;store.delete(saved.id);pinnedIds=store.pinnedIds();sources=store.load();notifySuccess("已移除信源")
    }
    fun privateAccess(saved:Source){sealRemote(saved,Entry(startingFolderName(saved),saved.initialPath,true,0))}
    internal fun requestPrivate(label:String="封存柜",saved:Source?=null,verified:()->Unit={}){
        stopDiscovery();cancelOperation();draft=null;access=null;openingSource=null
        privateRequest=PrivateSourceRequest(saved,label=label,verified=verified)
    }
    fun completePrivateAccess(id:String){
        val request=privateRequest?.takeIf{it.id==id}?:return
        privateRequest=null
        runCatching{SealedMedia.unlock();request.verified()}.onFailure{error=it.message}
    }
    fun cancelPrivateAccess(id:String,message:String?=null){if(privateRequest?.id!=id)return;privateRequest=null;if(message!=null)error=message}
    internal fun sealMedia(ref:MediaRef){
        requestPrivate("收进封存柜："+ref.title,sources.firstOrNull{it.id==ref.sourceId}){
            if(ref.localUri!=null)sealLocalItem(SealedItem(ref,false))
            else{SealedMedia.seal(SealedItem(ref,false,ignoreCase=sources.firstOrNull{it.id==ref.sourceId}?.protocol==Protocol.SMB));notifySuccess("影像已收进封存柜")}
        }
    }
    internal fun sealRemote(saved:Source,entry:Entry){
        requestPrivate((if(entry.directory)"封存片架："else"封存影像：")+entry.name,saved){
            val ref=MediaRef("${saved.id}:${entry.path}",entry.name,saved.label,saved.id,entry.path,sizeBytes=entry.size.takeIf{!entry.directory&&it>0},location=mediaLocation(saved))
            SealedMedia.seal(SealedItem(ref,entry.directory,ignoreCase=saved.protocol==Protocol.SMB));notifySuccess(if(entry.directory)"片架已收进封存柜"else"影像已收进封存柜")
            if(source?.id==saved.id&&SealedMedia.protects(saved,path))closeSource()
        }
    }
    internal fun sealLocal(entry:LocalEntry,files:LocalFilesViewModel){
        val folder=files.active?:return
        val trail=files.trail+if(entry.directory)listOf(LocalDirectory(entry.uri,entry.name))else emptyList()
        requestPrivate((if(entry.directory)"封存片架："else"封存影像：")+entry.name){
            val ref=MediaRef(entry.uri.toString(),entry.name,folder.name,localUri=entry.uri.toString(),localParentUri=entry.parentUri?.toString(),localAncestors=entry.ancestorUris,sizeBytes=entry.size.takeIf{!entry.directory&&it>0})
            sealLocalItem(SealedItem(ref,entry.directory,folder.tree.toString(),trail))
        }
    }
    private fun sealLocalItem(item:SealedItem){
        val sealGeneration=generation
        val signal=android.os.CancellationSignal();privateLocalSignal=signal;busy=true
        job=viewModelScope.launch{
            try{
                val aliases=withContext(Dispatchers.IO){
                    val uri=Uri.parse(requireNotNull(item.media.localUri))
                    if(item.directory)SealedMedia.checkDirectorySupport(uri,signal)
                    val known=historyRows.map{it.media}+StationInventory(getApplication()).entries().map{it.media}+
                        FilmLibraryStore(getApplication()).use{it.works().flatMap{work->work.films}.map{it.media}}
                    SealedMedia.localAliases(uri,known,item.directory)
                }
                if(!SealedMedia.unlocked||signal.isCanceled||sealGeneration!=generation)return@launch
                SealedMedia.seal(item.copy(aliases=item.aliases+aliases));notifySuccess(if(item.directory)"片架已收进封存柜"else"影像已收进封存柜")
            }catch(e:CancellationException){throw e}catch(e:Exception){if(sealGeneration==generation)error=e.message?:"封存未完成，请重试"}
            finally{if(privateLocalSignal===signal)privateLocalSignal=null;if(sealGeneration==generation)busy=false}
        }
    }
    internal fun openSealed(item:SealedItem){
        if(!SealedMedia.unlocked)return
        if(item.media.sourceId!=null){
            val saved=sources.firstOrNull{it.id==item.media.sourceId}?:run{error="原来源已移除";return}
            if(!SourceRoot(saved.initialPath).contains(item.media.path.orEmpty())){error="此封存记录在当前来源根目录之外，请先调整来源根目录";return}
            if(item.directory){open(saved,item.media.path.orEmpty(),allowPrivate=true)}
            else resume(HistoryEntry(item.media,0,0,0,false),allowPrivate=true)
        }else item.media.localUri?.let{playLocal(Uri.parse(it),privatePlayback=true)}
    }
    internal fun closeSealed(){if(privateBrowse&&!SealedMedia.unlocked)closeSource()}
    fun open(saved:Source,location:String=saved.initialPath,selectRoot:Boolean=false,allowPrivate:Boolean=false){
        privateBrowse=allowPrivate&&SealedMedia.unlocked
        val safeLocation=if(selectRoot)Paths.clean(location)else SourceRoot(saved.initialPath).clamp(location)
        if(!SealedMedia.visible(saved,safeLocation,privateBrowse)){error="请从封存柜验证身份后查看";return}
        selectedSourceId=saved.id
        rootSelectionId=if(selectRoot)saved.id else null
        stopDiscovery();cancelOperation();privateRequest=null;pendingReplay=null;draft=null;access=null;openingSource=saved
        val request=generation
        val memoryPassword=credentials?.password?.takeIf{source?.id==saved.id}
        source=null;credentials=null;path="";directoryEntries=emptyList()
        viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){memoryPassword ?: store.password(saved.id)}
                if(saved.protocol==Protocol.ALIYUN && pass==null){openingSource=null;preparingKey=null;error="阿里云盘授权已失效，请在信源的配置按钮重新授权";pendingReplay=null;endPreparation();return@launch}
            if(request!=generation)return@launch
            if(pass!=null||saved.username.isBlank())load(saved,Credentials(pass ?: ""),safeLocation,false,false)
            else{openingSource=null;access=SourceAccess(saved,path=safeLocation)}
        }catch(e:Exception){if(request==generation){openingSource=null;if(saved.protocol!=Protocol.ALIYUN)access=SourceAccess(saved,path=safeLocation);error=if(saved.protocol==Protocol.ALIYUN)"网盘凭据无法读取，请重新授权"else"无法读取保存的密码，请重新认证"}}}
    }
    fun cloudConnected(saved:Source){val registering=sources.none{it.id==saved.id};sources=store.load();val current=sources.firstOrNull{it.id==saved.id} ?: return;selectedGroup="all";error=null;draft=null;open(current);if(registering)notifyRegistration()}
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
    internal fun addExternal(){
        add(Candidate("","",443,Protocol.WEBDAV,"",true,"/"))
        draft=draft?.copy(webDavUrl="https://")
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
        }catch(e:CancellationException){throw e}catch(e:Exception){if(id==generation)error="无法列出共享，请核对账户或在线路设置填写共享名（${e.javaClass.simpleName}）"}
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
    fun browse(newPath:String,playMovie:Boolean=false){
        val s=source ?: return;val c=credentials ?: return
        if(rootSelectionId==null&&!SourceRoot(s.initialPath).contains(newPath)){error="已到达设置的根目录";return}
        if(!SealedMedia.visible(s,newPath,privateBrowse)){error="请从封存柜验证身份后查看";return}
        load(s,c,Paths.clean(newPath),false,false,playMovie)
    }
    private fun load(s:Source,c:Credentials,newPath:String,remember:Boolean,save:Boolean,playMovie:Boolean=false){
        val operationEpoch=MediaDataMigration.session(getApplication()).catalogEpoch()
        val registering=save&&sources.none{it.id==s.id}
        if(pendingReplay?.entry?.media?.sourceId==s.id){prepareReplay(s,c,remember,save);return}
        val folderName=if(newPath.isEmpty())"信源根目录"else
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
                source=s;openingSource=null;credentials=c;path=newPath;currentFolderName=folderName;directoryEntries=result;draft=null;access=null;sources=store.load()
                StationInventory(getApplication()).reconcileSource(s)
                StationInventory(getApplication()).observe(result.filter{!it.directory&&it.isVideo}.map { e->MediaRef("${s.id}:${e.path}",e.name,s.label,s.id,e.path,sizeBytes=e.size,location=mediaLocation(s),bindingEpoch=SourceBindingEpochs.current(getApplication(),s),posterPathHint=posterHint(s,e)) },operationEpoch=operationEpoch)
                if(registering)notifyRegistration() else if(save)notifySuccess("信源已保存")
                if(playMovie)io.localplayer.core.singleMovieEntry(folderName,entries)?.let{play(it)}
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
        privateLocalSignal?.cancel();privateLocalSignal=null
        generation++;job?.cancel();job=null
        val old=cancellation;cancellation=null;busy=false;preparingKey=null
        if(!keepReplay){pendingReplay=null;endPreparation();dismissLegacyReplay()}
        if(old!=null)CoroutineScope(Dispatchers.IO).launch{old.close()}
    }
    fun closeSource(){privateBrowse=false;cancelOperation();access=null;openingSource=null;source=null;path="";directoryEntries=emptyList();credentials=null;pendingReplay=null;rootSelectionId=null;error=null}
    fun currentDirectoryBookmark():SourceBookmark?=source?.let{SourceBookmark(it.id,path,if(path.isEmpty())it.label else currentFolderName)}
    fun up(){val s=source?:return;val parent=SourceRoot(if(rootSelectionId!=null)""else s.initialPath).parent(path);if(parent==null)closeSource()else browse(parent)}
    private fun posterHint(s:Source,e:Entry):String? {
        if(source?.id!=s.id||e.path.substringBeforeLast('/',"")!=path)return null
        return listOf(currentFolderName,e.name).joinToString("/")
    }
    private fun remoteTarget(s:Source,c:Credentials,entry:Entry,listing:List<Entry>,fromStart:Boolean,requestedToken:HistoryToken?=null,positionMs:Long?=null):PlayTarget? {
        require(SourceRoot(s.initialPath).contains(entry.path)){"这部影片在设置的根目录之外，请重新选择"}
        val ref=MediaRef("${s.id}:${entry.path}",entry.name,s.label,s.id,entry.path,sizeBytes=entry.size,location=mediaLocation(s),bindingEpoch=SourceBindingEpochs.current(getApplication(),s),posterPathHint=posterHint(s,entry)).let(historyStore::reference)
        MediaDataMigration.playbackIntent(getApplication())
        val token=requestedToken ?: historyStore.token(ref)
        require(token==historyStore.token(ref)){"影片记录已变化，请重新选择后播放"}
        val resume=positionMs?.coerceAtLeast(0) ?: if(fromStart)0L else runCatching{historyStore.resumePosition(ref,store.position(ref.key))}.getOrDefault(0L)
        if(!SealedMedia.visible(ref,privateBrowse)){error="请从封存柜验证身份后观看";return null}
        val videos=listing.filter{!it.directory&&it.isVideo&&SourceRoot(s.initialPath).contains(it.path)&&SealedMedia.visible(s,it.path,privateBrowse)}
        val siblings=PlaybackRequests.window(videos,entry)
        val queue=mutableListOf<QueuedMedia>()
        try {
            for(e in siblings){
                val item=MediaRef("${s.id}:${e.path}",e.name,s.label,s.id,e.path,sizeBytes=e.size,location=mediaLocation(s),bindingEpoch=SourceBindingEpochs.current(getApplication(),s),posterPathHint=posterHint(s,e)).let(historyStore::reference)
                queue+=QueuedMedia(PlaybackRegistry.add(s,c,e.path,e.name,context=getApplication(),bindingEpoch=item.bindingEpoch,media=item),item,if(e.path==entry.path)token else historyStore.token(item))
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
            var preparedToken=replay.token
            try {
                withContext(Dispatchers.IO){
                    if(!SourceBindingEpochs.matches(getApplication(),s,replay.entry.media))throw ReplaySourceChanged()
                    val parent=replay.entry.media.path?.substringBeforeLast('/',"") ?: ""
                    require(SourceRoot(s.initialPath).contains(parent)){"这部影片已在设置的根目录之外，请从来源重新选择"}
                    val listing=sourceConnector.connect(s,c,token).use{it.list(parent)}
                    token.check()
                    val file=listing.firstOrNull{!it.directory&&it.isVideo&&it.path==replay.entry.media.path}
                        ?: throw ReplayFileMissing()
                    if(replay.entry.media.sizeBytes?.let{it!=file.size}==true)throw java.io.IOException("影片文件已变化，请重新选择版本后再使用札记")
                    if(save)store.save(s,c.password,remember){id==generation}
                    val original=replay.entry.media
                    if((original.location==null||original.sizeBytes==null)&&original.mediaId!=null){
                        val resolved=MediaRef("${s.id}:${file.path}",file.name,s.label,s.id,file.path,sizeBytes=file.size,location=mediaLocation(s),bindingEpoch=SourceBindingEpochs.current(getApplication(),s))
                        FilmLibraryStore(getApplication()).use{preparedToken=requireNotNull(it.rebindFilm(original.mediaId,resolved,true,replay.token))}
                    }
                    prepared=remoteTarget(s,c,file,listing,replay.fromStart,preparedToken,replay.positionMs)
                }
                if(id!=generation)return@launch
                require(prepared!=null&&prepared?.historyToken==preparedToken&&prepared?.let{historyStore.token(it.media)==preparedToken}==true){"影片记录或访问状态已变化，请重新选择后播放"}
                prepared?.let{playChannel.send(it);prepared=null}
                pendingReplay=null;endPreparation();access=null;openingSource=null
                if(save)sources=store.load()
            }catch(e:CancellationException){throw e}
            catch(e:HostKeyRequired){if(id==generation){trust=e;access=SourceAccess(s,c.password,store.hasPassword(s.id),replay.entry.media.path?.substringBeforeLast('/',"") ?: "")}}
            catch(e:Exception){if(id==generation){pendingReplay=null;endPreparation();error=if(s.protocol==Protocol.ALIYUN)cloudFailureMessage(e)else e.message ?: "无法准备播放，请重试"}}
            finally{
                prepared?.release()
                withContext(NonCancellable+Dispatchers.IO){token.close()}
                if(id==generation){preparingKey=null;cancellation=null;openingSource=null}
            }
        }
    }
    var selectedHistoryKey by mutableStateOf<String?>(null);private set
    fun resume(entry:HistoryEntry,fromStart:Boolean=false,allowPrivate:Boolean=false,positionMs:Long?=null,confirmedLegacy:Boolean=false){
        if(SealedMedia.protects(entry.media)&&!(allowPrivate&&SealedMedia.unlocked)){error="请从封存柜验证身份后观看";return}
        privateBrowse=allowPrivate&&SealedMedia.unlocked
        selectedHistoryKey=entry.media.key
        if(preparingKey==entry.media.key)return
        error=null
        val ref=entry.media
        if(!confirmedLegacy&&(ref.sizeBytes==null||(ref.localUri==null&&ref.location==null))){
            legacyReplay=entry;legacyReplayAction={resume(entry,fromStart,allowPrivate,positionMs,true)};return
        }
        if(ref.localUri!=null){playLocal(Uri.parse(ref.localUri),fromStart,parentUri=ref.localParentUri?.let(Uri::parse),privatePlayback=allowPrivate,positionMs=positionMs,expectedMedia=ref);return}
        val saved=sources.firstOrNull{it.id==ref.sourceId}
        if(saved==null){error="这个信源已移除，请重新添加后选择影片";return}
        if(!SourceBindingEpochs.matches(getApplication(),saved,ref)){error="信源身份已变更，请从信源重新选择影片";return}
        stopDiscovery();cancelOperation();preparationLease=MediaDataMigration.preparation(getApplication());val identified=try{historyStore.reference(ref)}catch(_:Exception){endPreparation();error="片档暂时无法写入，请保留原记录后重试";return};draft=null;access=null;pendingReplay=ReplayRequest(entry.copy(media=identified),fromStart,historyStore.token(identified),positionMs)
        val request=generation;preparingKey=ref.key
        val memoryPassword=credentials?.password?.takeIf{source?.id==saved.id}
        job=viewModelScope.launch{try{
            val pass=withContext(Dispatchers.IO){memoryPassword ?: store.password(saved.id)}
                if(saved.protocol==Protocol.ALIYUN && pass==null){openingSource=null;preparingKey=null;error="阿里云盘授权已失效，请在信源的配置按钮重新授权";pendingReplay=null;endPreparation();return@launch}
            if(request!=generation)return@launch
            if(pass!=null||saved.username.isBlank())prepareReplay(saved,Credentials(pass ?: ""))
            else {preparingKey=null;access=SourceAccess(saved,path=ref.path?.substringBeforeLast('/',"") ?: "")}
        }catch(e:CancellationException){throw e}
        catch(e:Exception){if(request==generation){preparingKey=null;error=if(saved.protocol==Protocol.ALIYUN)"网盘凭据无法读取，请在信源配置重新授权"else"无法读取保存的凭据，请重新输入";if(saved.protocol!=Protocol.ALIYUN)access=SourceAccess(saved,path=ref.path?.substringBeforeLast('/',"") ?: "")else{pendingReplay=null;endPreparation()}}}}
    }
    internal fun endCatalogScreening(){
        cancelOperation()
        while(true){val pending=playChannel.tryReceive().getOrNull()?:break;pending.release()}
        getApplication<Application>().stopService(android.content.Intent(getApplication(),PlaybackService::class.java))
    }
    internal fun catalogCredentials(saved:Source):Credentials? {
        val password=credentials?.password?.takeIf{source?.id==saved.id}?:store.password(saved.id)
        return if(password!=null||saved.username.isBlank())Credentials(password?:"") else null
    }
    internal fun playCatalog(films:List<LibraryFilm>){
        cancelOperation();val request=generation;preparationLease=MediaDataMigration.preparation(getApplication());val lease=preparationLease
        val snapshot=films.filter{SealedMedia.visible(it.media)}.take(PlaybackRequests.MAX_ITEMS)
        preparingKey="catalog-playlist"
        job=viewModelScope.launch {
            val queue=mutableListOf<QueuedMedia>();var sent=false
            try{
                withContext(Dispatchers.IO){
                    val passwords=HashMap<String,Credentials?>()
                    for(film in snapshot){
                        ensureActive();val ref=film.media
                        if(!SealedMedia.visible(ref))continue
                        val saved=ref.sourceId?.let{id->store.load().firstOrNull{it.id==id}}
                        val password=saved?.let{passwords.getOrPut(it.id){catalogCredentials(it)}}
                        if(LibraryRepair.inspect(getApplication(),ref,password,sourceConnector)!=FilmAccess.READY)continue
                        ensureActive();MediaDataMigration.session(getApplication()).checkLocator(ref)
                        val uri=if(ref.localUri!=null)Uri.parse(ref.localUri)else PlaybackRegistry.add(requireNotNull(saved),password?:Credentials(""),requireNotNull(ref.path),ref.title,sourceConnector,getApplication(),ref.bindingEpoch,ref)
                        queue+=QueuedMedia(uri,ref,historyStore.token(ref))
                    }
                }
                if(request!=generation)return@launch
                require(queue.isNotEmpty()){ "本单暂时没有可放映的影片，请到检修台核对位置与授权" }
                val first=queue.first();val resume=historyStore.resumePosition(first.media,0)
                val target=PlayTarget(first.uri,first.media.title,resume,first.media,first.token,queue=queue.toList(),truncated=films.size>snapshot.size)
                try{playChannel.send(target);sent=true}catch(e:Exception){target.release();throw e}
                val skipped=snapshot.size-queue.size
                if(skipped>0)notifySuccess("已跳过 $skipped 份暂不可访问的影片")
            }catch(e:CancellationException){throw e}catch(e:Exception){if(request==generation)error=e.message?:"放映单暂时无法打开"}
            finally{if(!sent)queue.forEach{PlaybackRegistry.remove(it.uri)};lease?.close();if(preparationLease===lease)preparationLease=null;if(request==generation)preparingKey=null}
        }
    }
    fun playLocal(uri:Uri,fromStart:Boolean=false,siblings:List<LocalEntry> = emptyList(),parentUri:Uri?=null,privatePlayback:Boolean=false,positionMs:Long?=null,expectedMedia:MediaRef?=null){
        cancelOperation();preparationLease=MediaDataMigration.preparation(getApplication());val lease=preparationLease;pendingReplay=null;val request=generation;preparingKey=uri.toString()
        val original=try{expectedMedia?.let(historyStore::reference)}catch(_:Exception){endPreparation();preparingKey=null;error="片档暂时无法写入，请保留原记录后重试";return}
        val localEpoch=LocalBindingEpochs.current(getApplication(),uri)
        val requestedFence=historyStore.token(uri.toString())
        var currentToken=original?.let(historyStore::token)
        job=viewModelScope.launch{try{
            val ref=withContext(Dispatchers.IO){
                require(!SealedMedia.protectsLocalUri(uri)||(privatePlayback&&SealedMedia.unlocked)){"请从封存柜验证身份后观看"}
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
                if(positionMs!=null&&original?.sizeBytes!=null&&size!=null&&original.sizeBytes!=size)throw java.io.IOException("影片文件已变化，请重新选择版本后再使用札记")
                val resolved=MediaRef(uri.toString(),title,"本机文件",localUri=uri.toString(),sizeBytes=size,localParentUri=(parentUri?:siblings.firstOrNull{it.uri==uri}?.parentUri)?.toString(),localAncestors=siblings.firstOrNull{it.uri==uri}?.ancestorUris?:emptyList(),bindingEpoch=localEpoch)
                if(original?.sizeBytes==null&&original?.mediaId!=null)FilmLibraryStore(getApplication()).use{currentToken=requireNotNull(it.rebindFilm(original.mediaId,resolved,true,currentToken))}
                historyStore.reference(resolved)
            }
            val resume=positionMs?.coerceAtLeast(0) ?: if(fromStart)0L else runCatching{historyStore.resumePosition(ref,store.position(ref.key))}.getOrDefault(0L)
            if(request==generation&&requestedFence==historyStore.token(uri.toString())&&(currentToken==null||currentToken==historyStore.token(ref))){
                val requestedToken=historyStore.token(ref)
                val videos=siblings.filter{!it.directory&&it.video&&SealedMedia.visible(it,privatePlayback)}
                val selected=videos.firstOrNull{it.uri==uri}
                val window=if(selected!=null)PlaybackRequests.window(videos,selected)else emptyList()
                val queue=window.map{e->
                    val item=if(e.uri==uri)ref else MediaRef(e.uri.toString(),e.name,"本机文件",localUri=e.uri.toString(),sizeBytes=e.size,localParentUri=e.parentUri?.toString(),localAncestors=e.ancestorUris,bindingEpoch=e.bindingEpoch)
                    QueuedMedia(e.uri,item,if(e.uri==uri)requestedToken else historyStore.token(item))
                }
                playChannel.send(PlayTarget(uri,ref.title,resume,ref,requestedToken,queue=queue,truncated=videos.size>window.size&&window.isNotEmpty()))
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(request==generation)error="本机文件无法读取，请通过“打开本机视频”重新选择"}finally{lease?.close();if(preparationLease===lease)preparationLease=null;if(request==generation)preparingKey=null}}
    }
    fun dismissDraft(){discoveryPanelOpen=false;stopDiscovery();shareChoices=null;cancelOperation();pendingReplay=null;trust=null;draft=null;error=null}
    fun deleteDraft(){discoveryPanelOpen=false;stopDiscovery();draft?.source?.id?.let{organization.removeSource(it);organizationRevision++};cancelOperation();trust=null;pendingReplay=null;draft?.source?.id?.let{StationInventory(getApplication()).removeSource(it);store.delete(it)};draft=null;sources=store.load();pinnedIds=store.pinnedIds();notifySuccess("已移除信源")}
    fun putBookmark(item:SourceBookmark){
        error=null
        val before=organization.bookmarks().firstOrNull{it.key==item.key}
        runCatching{organization.put(item);organizationRevision++}.onSuccess{
            notifySuccess(when{item.favorite!= (before?.favorite ?: false)->if(item.favorite)"已标记路标"else"已取消路标标记";else->"路标已保存"})
        }.onFailure{error=it.message ?: "路标未保存，请重试"}
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
            val current=store.load().firstOrNull{it.id==saved.id} ?: throw IllegalArgumentException("这个信源已移除")
            val clean=Paths.clean(initialPath)
            store.updatePresentation(current.id,label,clean,folderName)
            organization.moveSourceEntry(current.id,current.initialPath,clean,label.trim());organizationRevision++
            sources=store.load()
            if(source?.id==current.id){
                val updated=sources.first{it.id==current.id};source=updated
                if(rootSelectionId==null&&!SourceRoot(updated.initialPath).contains(path))open(updated)
            }
            if(announce)notifySuccess("连接设置已保存")
        }.onFailure{error=it.message ?: "连接设置未保存，请重试"}.isSuccess
    }
    fun beginRootSelection(saved:Source){settingsSourceId=null;open(saved,"",selectRoot=true)}
    fun useCurrentFolder(){
        val current=source?.takeIf{it.id==rootSelectionId} ?: return
        if(busy||openingSource!=null)return
        val name=currentFolderName
        if(updateSourcePresentation(current,current.label,path,name,announce=false)){closeSource();notifySuccess("根目录已设置")}
    }
    fun removeBookmark(key:String){organization.remove(key);organizationRevision++;notifySuccess("已移除路标")}
    fun saveGroup(name:String,id:String?=null){error=null;runCatching{organization.group(name,id);organizationRevision++}.onSuccess{notifySuccess("已保存")}.onFailure{error=it.message}}
    fun deleteGroup(id:String){organization.deleteGroup(id);organizationRevision++;if(selectedGroup==id)selectedGroup="all";notifySuccess("已删除分册")}
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
