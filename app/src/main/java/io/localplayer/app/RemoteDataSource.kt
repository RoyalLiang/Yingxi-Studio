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

class PlaybackLease(val source: Source, val credentials: Credentials, val path: String, private val connector:Connector=Protocols()) : java.io.Closeable {
    private val cancellation=Cancellation()
    private var session: RemoteSession?=null
    private var closed=false
    val resumeKey get()="${source.id}:$path"
    @Synchronized fun open(): RemoteFile {
        if(closed) throw IOException("播放已结束")
        val connected=session ?: connector.connect(source,credentials,cancellation).also { session=it }
        try { return connected.open(path) }
        catch(e:Exception) {
            cancellation.check()
            if(Thread.currentThread().isInterrupted)throw e
            runCatching { connected.close() };session=null
            // Read-only reopen, once per open call. Reconnect repeats the same
            // source authentication and SFTP host-key verification.
            val renewed=connector.connect(source,credentials,cancellation).also { session=it }
            return renewed.open(path)
        }
    }
    override fun close() {
        // Signal/close the transport before taking the lock used by an in-flight connect.
        cancellation.close()
        synchronized(this) { closed=true;runCatching { session?.close() };session=null }
    }
}

object PlaybackRegistry {
    private val leases=ConcurrentHashMap<String,PlaybackLease>()
    private val closer=Executors.newSingleThreadExecutor()
    fun add(source: Source, credentials: Credentials, path: String, name: String): Uri {
        val token=UUID.randomUUID().toString();leases[token]=PlaybackLease(source,credentials,path)
        return Uri.Builder().scheme("localplayer").authority(token).appendPath(name).build()
    }
    fun get(uri: Uri): PlaybackLease = leases[uri.authority] ?: throw IOException("播放上下文已过期，请从来源重新打开")
    fun remove(uri: Uri?) { if(uri?.scheme=="localplayer") leases.remove(uri.authority)?.let { lease -> closer.execute { lease.close() } } }
    fun clear() { leases.keys.toList().forEach { token -> leases.remove(token)?.let { closer.execute { it.close() } } } }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteDataSource : BaseDataSource(true) {
    @Volatile private var file: RemoteFile?=null
    @Volatile private var closed=true
    private var uri: Uri?=null
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
        try {
            val opened=ReadAheadFile(PlaybackRegistry.get(dataSpec.uri).open());file=opened
            if(closed) { opened.close();throw InterruptedIOException("读取已取消") }
            if(position>opened.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            val available=opened.size-position
            remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong()) available else minOf(available,dataSpec.length)
            started=true;transferStarted(dataSpec);trace("opened offset=$position remaining=$remaining took=${android.os.SystemClock.elapsedRealtime()-openedAt}ms");return remaining
        } catch(e: Exception) { runCatching { file?.close() };file=null;closed=true;throw if(e is IOException)e else IOException("无法打开远程文件",e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if(length==0)return 0
        if(closed)throw InterruptedIOException("读取已取消")
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val readAt=android.os.SystemClock.elapsedRealtime()
        val n=file?.readAt(position,buffer,offset,minOf(length.toLong(),remaining).toInt()) ?: throw IOException("文件未打开")
        val elapsed=android.os.SystemClock.elapsedRealtime()-readAt
        if(transferred==0L||elapsed>100)trace("read offset=$position bytes=$n took=${elapsed}ms")
        if(closed)throw InterruptedIOException("读取已取消")
        if(n<0)throw java.io.EOFException("远程文件提前结束，文件可能已改变")
        if(n==0)throw IOException("远程服务返回空读取")
        position+=n;remaining-=n;transferred+=n;bytesTransferred(n);return n
    }
    override fun close() {
        closed=true
        trace("close offset=$position transferred=$transferred age=${android.os.SystemClock.elapsedRealtime()-openedAt}ms")
        try { file?.close() } finally { file=null;uri=null;if(started){started=false;transferEnded()} }
    }
    class Factory : DataSource.Factory { override fun createDataSource(): DataSource=RemoteDataSource() }
}
