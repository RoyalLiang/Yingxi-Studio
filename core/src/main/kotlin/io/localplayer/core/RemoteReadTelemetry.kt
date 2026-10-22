package io.localplayer.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/** Primitive, bounded counters only. Snapshot never waits for the transport's read lock. */
class RemoteReadTelemetry {
    enum class Stage { URL, HEADERS, BODY }
    data class Snapshot(val stage:Stage?,val currentMs:Long,val urlRequests:Long,val rangeRequests:Long,
        val sourceBytes:Long,val urlMs:Long,val responseMs:Long,val bodyMs:Long,val httpStatus:Int,
        val downloadRightsNotice:Boolean=false,val signedTrafficLimitBitsPerSecond:Long?=null) {
        /** Active socket wait counts too; a stalled read must not leave a stale high rate. */
        val bodyBytesPerSecond:Long? get() {
            val time=bodyMs+if(stage==Stage.BODY)currentMs else 0
            return if(time<1000||sourceBytes<=0)null else (sourceBytes.toDouble()*1000/time).toLong()
        }
    }
    private data class Active(val stage:Stage,val started:Long)
    private val active=AtomicReference<Active?>()
    private val elapsed=AtomicLongArray(Stage.entries.size)
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
    fun rangeRequest(){ranges.incrementAndGet()}
    fun received(count:Int){if(count>0)bytes.addAndGet(count.toLong())}
    fun response(code:Int){status.set(code.toLong())}
    fun <T> measure(stage:Stage,action:()->T):T {
        val operation=Active(stage,System.nanoTime());active.set(operation)
        try{return action()}finally{
            elapsed.addAndGet(stage.ordinal,(System.nanoTime()-operation.started).coerceAtLeast(0))
            active.compareAndSet(operation,null)
        }
    }
    fun snapshot():Snapshot {
        val current=active.get()
        return Snapshot(current?.stage,current?.let{(System.nanoTime()-it.started).coerceAtLeast(0)/1_000_000}?:0,
            urls.get(),ranges.get(),bytes.get(),elapsed.get(Stage.URL.ordinal)/1_000_000,
            elapsed.get(Stage.HEADERS.ordinal)/1_000_000,elapsed.get(Stage.BODY.ordinal)/1_000_000,status.get().toInt(),rightsNotice.get(),signedLimit.get().takeIf{it>0})
    }
}
