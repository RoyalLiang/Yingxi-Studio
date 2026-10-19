package io.localplayer.core

import kotlin.test.*
import java.util.concurrent.*
import java.io.InterruptedIOException

class ReadAheadFileTest {
    @kotlin.test.Test fun sequentialWindowGrowsWithoutOverreadingRandomSeek() {
        val data=ByteArray(8*1024*1024){(it%251).toByte()}
        var calls=0;val requests=mutableListOf<Int>()
        val raw=object:RemoteFile{
            override val size=data.size.toLong()
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {calls++;requests+=length;val n=minOf(length,data.size-position.toInt());if(n<=0)return -1;data.copyInto(buffer,offset,position.toInt(),position.toInt()+n);return n}
            override fun close(){}
        }
        ReadAheadFile(raw).use{file->
            val out=ByteArray(8192);var pos=0L
            while(pos<data.size){val n=file.readAt(pos,out,0,out.size);kotlin.test.assertTrue(n>0);kotlin.test.assertContentEquals(data.copyOfRange(pos.toInt(),pos.toInt()+n),out.copyOf(n));pos+=n}
            kotlin.test.assertTrue(calls<=10,"Sequential read should coalesce <=10 network calls, got $calls")
            file.readAt(12345,out,0,out.size)
            kotlin.test.assertEquals(64*1024,requests.last(),"A discontinuous seek starts with a small window")
        }
    }

    @Test fun tinyReadsAndLargeSeeksPreserveBytesWithBoundedNetworkRequests(){
        var calls=0;var largest=0;var closes=0
        val remote=object:RemoteFile{
            override val size=(1L shl 32)+32768
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{
                calls++;largest=maxOf(largest,length)
                val n=minOf(length.toLong(),4096L,size-position).toInt()
                repeat(n){buffer[offset+it]=((position+it)%251).toByte()};return n
            }
            override fun close(){closes++}
        }
        val file=ReadAheadFile(remote,64*1024)
        file.use{
            val bytes=ByteArray(8)
            for(i in 0 until 3000){val at=i*8L;assertEquals(8,it.readAt(at,bytes,0,8));assertContentEquals(ByteArray(8){j->((at+j)%251).toByte()},bytes)}
            assertTrue(calls<10,"Small extractor reads must share network requests")
            for(at in listOf((1L shl 32)+100,0L,900000L)){
                assertEquals(8,it.readAt(at,bytes,0,8));assertContentEquals(ByteArray(8){j->((at+j)%251).toByte()},bytes)
            }
            assertTrue(largest<=64*1024)
            assertEquals(0,it.readAt(it.size,bytes,0,0));assertEquals(-1,it.readAt(it.size,bytes,0,8))
        }
        file.close();assertEquals(1,closes);assertFailsWith<InterruptedIOException>{file.readAt(0,ByteArray(1),0,1)}
    }

    @Test fun closeCanInterruptAnInFlightWindowFill(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val file=ReadAheadFile(object:RemoteFile{
            override val size=1024L
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{entered.countDown();check(release.await(5,TimeUnit.SECONDS));return length}
            override fun close(){release.countDown()}
        })
        val pool=Executors.newSingleThreadExecutor()
        try{
            val reading=pool.submit<Int>{file.readAt(0,ByteArray(1),0,1)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));file.close()
            val failure=assertFailsWith<ExecutionException>{reading.get(5,TimeUnit.SECONDS)}
            assertTrue(failure.cause is InterruptedIOException)
        }finally{file.close();pool.shutdownNow()}
    }
}
