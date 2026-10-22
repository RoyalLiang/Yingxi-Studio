package io.localplayer.app

import android.os.SystemClock

/** Current playback I/O only. Never retains URIs, paths, credentials or exception messages. */
internal object PlaybackIoProbe {
    enum class Operation { OPEN, READ }
    data class Snapshot(val protocol:String, val operation:Operation?, val waitingMs:Long,
        val sinceProgressMs:Long?, val failures:Int, val lastFailure:String?)
    private data class Pending(val operation:Operation,val started:Long)
    private val pending=linkedMapOf<Long,Pending>()
    private val transports=mutableListOf<io.localplayer.core.RemoteReadTelemetry>()
    private var serial=0L
    private var protocol="UNKNOWN"
    private var lastProgress:Long?=null
    private var failures=0
    private var lastFailure:String?=null

    @Synchronized fun reset(){pending.clear();transports.clear();protocol="UNKNOWN";lastProgress=null;failures=0;lastFailure=null}
    @Synchronized fun begin(operation:Operation,kind:String?=null):Long {
        if(kind!=null)protocol=kind.takeIf{it in setOf("SMB","SFTP","WEBDAV","ALIYUN")}?:"UNKNOWN"
        // Bound diagnostic state even if a broken caller never completes its operation.
        if(pending.size>=16)pending.remove(pending.keys.first())
        val id=++serial;pending[id]=Pending(operation,SystemClock.elapsedRealtime());return id
    }
    @Synchronized fun attach(id:Long,stats:io.localplayer.core.RemoteReadTelemetry?){
        if(stats!=null&&pending.containsKey(id)&&stats !in transports){
            if(transports.size>=16)transports.removeAt(0)
            transports+=stats
        }
    }
    @Synchronized fun transportSnapshots()=transports.map{it.snapshot()}
    @Synchronized fun progress(id:Long,count:Int){if(count>0&&pending.containsKey(id))lastProgress=SystemClock.elapsedRealtime()}
    @Synchronized fun end(id:Long,error:Throwable?=null){
        val operation=pending.remove(id)?:return
        if(error!=null){failures++;lastFailure=operation.operation.name+":"+when(error){
            is java.net.SocketTimeoutException->"TIMEOUT"
            is java.io.InterruptedIOException->"CANCELLED_OR_TIMEOUT"
            is java.io.EOFException->"EARLY_EOF"
            is java.io.IOException->"IO"
            else->"OTHER"
        }}
    }
    @Synchronized fun snapshot():Snapshot {
        val now=SystemClock.elapsedRealtime();val oldest=pending.values.firstOrNull()
        return Snapshot(protocol,oldest?.operation,oldest?.let{(now-it.started).coerceAtLeast(0)}?:0,
            lastProgress?.let{(now-it).coerceAtLeast(0)},failures,lastFailure)
    }
}
