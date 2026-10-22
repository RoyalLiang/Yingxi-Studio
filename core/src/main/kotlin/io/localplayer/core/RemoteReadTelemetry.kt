package io.localplayer.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Primitive, bounded counters only. Snapshot never waits for the transport's read lock. */
class RemoteReadTelemetry {
    enum class Stage { URL, HEADERS, BODY }
    data class Snapshot(val stage:Stage?,val currentMs:Long,val urlRequests:Long,val rangeRequests:Long,
        val sourceBytes:Long,val urlMs:Long,val responseMs:Long,val bodyMs:Long,val httpStatus:Int,
        val downloadRightsNotice:Boolean=false,val signedTrafficLimitBitsPerSecond:Long?=null,
        val parallelism:Int=1,val cacheBytes:Long=0) {
        /** Active socket wait counts too; a stalled read must not leave a stale high rate. */
        val bodyBytesPerSecond:Long? get() {
            val time=bodyMs+if(stage==Stage.BODY)currentMs else 0
            return if(time<1000||sourceBytes<=0)null else (sourceBytes.toDouble()*1000/time).toLong()
        }
    }
    private val timing=Any()
    private val active=IntArray(Stage.entries.size)
    private val started=LongArray(Stage.entries.size)
    private val elapsed=LongArray(Stage.entries.size)
    private val workers=AtomicLong(1)
    private val cached=AtomicLong()
    private val urls=AtomicLong()
    private val ranges=AtomicLong()
    private val bytes=AtomicLong()
    private val status=AtomicLong()
    private val rightsNotice=AtomicBoolean()
    private val signedLimit=AtomicLong()
    fun downloadAdvisory(notice:Boolean,limitBitsPerSecond:Long?){
        rightsNotice.set(notice);signedLimit.set(limitBitsPerSecond?.takeIf{it>0}?:0)
    }
    fun urlRequest(){urls.incrementAndGet()}
    fun parallelism(count:Int){workers.set(count.toLong())}
    fun cacheHit(count:Int){cached.addAndGet(count.toLong())}
    fun rangeRequest(){ranges.incrementAndGet()}
    fun received(count:Int){if(count>0)bytes.addAndGet(count.toLong())}
    fun response(code:Int){status.set(code.toLong())}
    fun <T> measure(stage:Stage,action:()->T):T {
        val index=stage.ordinal
        synchronized(timing){if(active[index]++==0)started[index]=System.nanoTime()}
        try{return action()}finally{synchronized(timing){
            if(--active[index]==0)elapsed[index]+=(System.nanoTime()-started[index]).coerceAtLeast(0)
        }
        }
    }
    fun snapshot():Snapshot {
        return synchronized(timing){
            val current=Stage.entries.reversed().firstOrNull{active[it.ordinal]>0}
            val currentMs=current?.let{(System.nanoTime()-started[it.ordinal]).coerceAtLeast(0)/1_000_000}?:0
            Snapshot(current,currentMs,urls.get(),ranges.get(),bytes.get(),elapsed[0]/1_000_000,
                elapsed[1]/1_000_000,elapsed[2]/1_000_000,status.get().toInt(),rightsNotice.get(),signedLimit.get().takeIf{it>0},workers.get().toInt(),cached.get())
        }
    }
}
