package io.localplayer.app

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.localplayer.core.Cancellation
import io.localplayer.core.Credentials
import io.localplayer.core.Connector
import io.localplayer.core.Protocols
import io.localplayer.core.RemoteFile
import io.localplayer.core.RemoteSession
import io.localplayer.core.ReadAheadFile
import io.localplayer.core.Source
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class PlaybackLease internal constructor(val source: Source, val credentials: Credentials, val path: String, private val connector:Connector=AppConnectors(),private val prefetch:Boolean=true,internal val adaptive:CloudAdaptiveState=CloudAdaptiveState(),private val authority:(()->Unit)?=null,internal val mediaId:String?=null) : java.io.Closeable {
    private val cancellation=Cancellation()
    private var session: RemoteSession?=null
    private var closed=false
    @Volatile private var revoked=false
    private val revocationHandlers=java.util.concurrent.CopyOnWriteArraySet<()->Unit>()
    internal fun onRevoked(handler:()->Unit):java.io.Closeable {revocationHandlers.add(handler);if(revoked)PlaybackRegistry.closeAction(handler);return java.io.Closeable{revocationHandlers.remove(handler)}}
    internal fun revoke(){revoked=true}
    internal fun checkCurrent(){if(revoked)throw IOException("信源身份或根目录已变化，请重新选择影片");authority?.invoke()}
    val resumeKey get()="${source.id}:$path"
    internal fun freshTransport(prefetch:Boolean=this.prefetch,connector:Connector=this.connector):PlaybackLease {checkCurrent();return PlaybackLease(source,credentials,path,connector,prefetch,adaptive,authority,mediaId)}
    internal fun replacementTransport()=PlaybackLease(source,credentials,path,connector,prefetch,adaptive,authority,mediaId)
    @Synchronized fun open(position:Long=0): RemoteFile {
        checkCurrent()
        if(closed) throw IOException("播放已结束")
        val connected=session ?: connector.connect(source,credentials,cancellation).also { session=it }
        try { return checked(if(prefetch)connected.open(path,position)else connected.open(path)) }
        catch(e:Exception) {
            cancellation.check();checkCurrent()
            if(Thread.currentThread().isInterrupted)throw e
            runCatching { connected.close() };session=null
            // Read-only reopen, once per open call. Reconnect repeats the same
            // source authentication and SFTP host-key verification.
            val renewed=connector.connect(source,credentials,cancellation).also { session=it }
            return checked(if(prefetch)renewed.open(path,position)else renewed.open(path))
        }
    }
    private fun checked(file:RemoteFile):RemoteFile {try{checkCurrent();return file}catch(e:Exception){file.close();throw e}}
    override fun close() {
        revoked=true
        revocationHandlers.toList().forEach{runCatching{it()}};revocationHandlers.clear()
        // Signal/close the transport before taking the lock used by an in-flight connect.
        cancellation.close()
        synchronized(this) { closed=true;runCatching { session?.close() };session=null }
    }
}

object PlaybackRegistry {
    private val leases=ConcurrentHashMap<String,PlaybackLease>()
    private val closer=Executors.newSingleThreadExecutor()
    private val invalidations=java.util.concurrent.CopyOnWriteArraySet<(String?,String?)->Unit>()
    internal fun observeInvalidation(change:(String?,String?)->Unit):()->Unit {invalidations.add(change);return{invalidations.remove(change)}}
    internal fun add(source: Source, credentials: Credentials, path: String, name: String, connector:Connector=AppConnectors(),context:android.content.Context?=null,bindingEpoch:Long?=null,media:MediaRef?=null): Uri {
        fun register():Uri {
            val authority:((()->Unit)?)=context?.let{app->val epoch=bindingEpoch?:SourceBindingEpochs.current(app,source);{SourceBindingEpochs.verifyLease(app,source,epoch);media?.let{MediaDataMigration.session(app).checkLocator(it)};Unit}}
            authority?.invoke()
            require(io.localplayer.core.SourceRoot(source.initialPath).contains(path)){"影片在来源根目录之外"}
            val token=UUID.randomUUID().toString();leases[token]=PlaybackLease(source,credentials,path,connector,authority=authority,mediaId=media?.mediaId)
            return Uri.Builder().scheme("localplayer").authority(token).appendPath(name).build()
        }
        return if(context==null)register()else synchronized(MediaDataMigration.session(context).gate){register()}
    }
    internal fun invalidateLocal(){invalidations.forEach{runCatching{it(null,null)}}}
    fun invalidateSource(sourceId:String){
        leases.keys.toList().forEach{key->leases.computeIfPresent(key){_,lease->if(lease.source.id==sourceId){lease.revoke();closer.execute{lease.close()};null}else lease}}
        invalidations.forEach{runCatching{it(sourceId,null)}}
    }
    internal fun invalidateMedia(mediaId:String){
        leases.keys.toList().forEach{key->leases.computeIfPresent(key){_,lease->if(lease.mediaId==mediaId){lease.revoke();closer.execute{lease.close()};null}else lease}}
        invalidations.forEach{runCatching{it(null,mediaId)}}
    }
    fun get(uri: Uri): PlaybackLease = (leases[uri.authority] ?: throw IOException("播放上下文已过期，请从信源重新打开")).also{it.checkCurrent()}
    internal fun closeAction(action:()->Unit){closer.execute{runCatching(action)}}
    internal fun closeAsync(lease:PlaybackLease){closer.execute{lease.close()}}
    internal fun playback(uri:Uri):PlaybackLease=get(uri).freshTransport()
    // Artwork uses its own transport and cancellation; it must never close the player's lease.
    fun preview(uri:Uri):PlaybackLease { val active=get(uri); return active.freshTransport(prefetch=false) }
    fun remove(uri: Uri?) { if(uri?.scheme=="localplayer") leases.remove(uri.authority)?.let { lease -> closer.execute { lease.close() } } }
    // Preserve the opaque registration for previous/next, but close its old transport.
    // Replacement is atomic: a rapid jump back gets a fresh lease, never one closing later.
    fun releaseConnection(uri:Uri?){
        if(uri?.isPlayerNetworkUri()!=true)return
        var previous:PlaybackLease?=null
        leases.computeIfPresent(uri.authority!!){_,lease->
            previous=lease;lease.replacementTransport()
        }
        previous?.let{lease->closer.execute{lease.close()}}
    }
    fun clear() { leases.keys.toList().forEach { token -> leases.remove(token)?.let { closer.execute { it.close() } } } }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteDataSource : BaseDataSource(true) {
    @Volatile private var file: RemoteFile?=null
    @Volatile private var closed=true
    private var uri: Uri?=null
    private var lease:PlaybackLease?=null
    private var position=0L
    private var remaining=0L
    private var started=false
    private var transferred=0L
    private var openedAt=0L
    private fun trace(message:String){
        if(Log.isLoggable(TAG,Log.DEBUG)){
            Log.d(TAG,message)
        }
    }
    private companion object { const val TAG="YingxiSource" }
    override fun getUri()=uri
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec);closed=false;uri=dataSpec.uri;position=dataSpec.position;transferred=0;openedAt=android.os.SystemClock.elapsedRealtime()
        trace("open offset=$position")
        val openNanos=System.nanoTime()
        val probe=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.OPEN,runCatching{PlaybackRegistry.get(dataSpec.uri).source.protocol.name}.getOrNull())
        var probeError:Throwable?=null
        try {
            val active=PlaybackRegistry.get(dataSpec.uri);lease=active
            val opened=ReadAheadFile(active.open(dataSpec.position));file=opened;active.checkCurrent();PlaybackIoProbe.attach(probe,opened.readTelemetry)
            if(closed) { opened.close();throw InterruptedIOException("读取已取消") }
            if(position>opened.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            val available=opened.size-position
            remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong()) available else minOf(available,dataSpec.length)
            started=true;transferStarted(dataSpec);trace("opened offset=$position remaining=$remaining took=${android.os.SystemClock.elapsedRealtime()-openedAt}ms");return remaining
        } catch(e: Exception) { probeError=e;runCatching { file?.close() };file=null;closed=true;throw if(e is IOException)e else IOException("无法打开远程文件",e) } finally { PlaybackIoProbe.end(probe,probeError);PlaybackMetrics.opened(System.nanoTime()-openNanos) }

    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if(length==0)return 0
        if(closed)throw InterruptedIOException("读取已取消")
        lease?.checkCurrent()
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val readAt=android.os.SystemClock.elapsedRealtime()
        val readNanos=System.nanoTime()
        val probe=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.READ)
        PlaybackIoProbe.attach(probe,file?.readTelemetry)
        var probeError:Throwable?=null
        try {
            val n=file?.readAt(position,buffer,offset,minOf(length.toLong(),remaining).toInt()) ?: throw IOException("文件未打开")
            lease?.checkCurrent()
            if(n>0)PlaybackMetrics.read(n,System.nanoTime()-readNanos)
            val elapsed=android.os.SystemClock.elapsedRealtime()-readAt
            if(transferred==0L||elapsed>100)trace("read offset=$position bytes=$n took=${elapsed}ms")
            if(closed)throw InterruptedIOException("读取已取消")
            if(n<0)throw java.io.EOFException("远程文件提前结束，文件可能已改变")
            if(n==0)throw IOException("远程服务返回空读取")
            PlaybackIoProbe.progress(probe,n)
            position+=n;remaining-=n;transferred+=n;bytesTransferred(n);return n
        } catch(e:Exception){probeError=e;throw e} finally {PlaybackIoProbe.end(probe,probeError)}
    }
    override fun close() {
        closed=true
        trace("close offset=$position transferred=$transferred age=${android.os.SystemClock.elapsedRealtime()-openedAt}ms")
        try { file?.close() } finally { file=null;lease=null;uri=null;if(started){started=false;transferEnded()} }
    }
    class Factory : DataSource.Factory { override fun createDataSource(): DataSource=RemoteDataSource() }
}
