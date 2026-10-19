package io.localplayer.core

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded window avoids a network round trip for every small extractor read. */
class ReadAheadFile(private val delegate:RemoteFile,capacity:Int=256*1024):RemoteFile {
    private val cache=ByteArray(capacity.also{require(it>0)})
    private var start=-1L
    private var count=0
    private val closed=AtomicBoolean(false)
    override val size get()=delegate.size

    @Synchronized override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
        require(position>=0&&offset>=0&&length>=0&&offset<=buffer.size&&length<=buffer.size-offset)
        checkOpen()
        if(length==0)return 0
        if(position>=size)return -1
        if(start<0||position<start||position-start>=count){
            count=0;start=position
            val request=minOf(cache.size.toLong(),size-position).toInt()
            val n=delegate.readAt(position,cache,0,request)
            checkOpen()
            if(n<=0)return n
            if(n>request)throw java.io.IOException("远程读取超出请求范围")
            count=n
        }
        checkOpen()
        val from=(position-start).toInt()
        val n=minOf(length,count-from)
        cache.copyInto(buffer,offset,from,from+n)
        return n
    }
    private fun checkOpen(){if(closed.get()||Thread.currentThread().isInterrupted)throw InterruptedIOException("读取已取消")}
    override fun close(){if(closed.compareAndSet(false,true))delegate.close()}
}
