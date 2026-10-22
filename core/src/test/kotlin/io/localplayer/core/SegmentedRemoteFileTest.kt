package io.localplayer.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SegmentedRemoteFileTest {
    private fun bytes(at: Long, count: Int) = ByteArray(count) { ((at+it)%251).toByte() }
    private fun remote(size: Long, parent: Cancellation, workers: Int = 3, cache: SegmentCache? = null,
                       open: (Long, Long, Cancellation) -> RemoteFile): RemoteFile =
        SegmentedRemoteFile(size,parent,AliyunReadOptions(workers,cache),"account-file-hash",RemoteReadTelemetry(),open=open)
    private fun source(size: Long, cancellation: Cancellation) = object: RemoteFile {
        override val size=size
        override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
            cancellation.check()
            val n=minOf(length.toLong(),size-position).toInt()
            bytes(position,n).copyInto(buffer,offset);return n
        }
        override fun close() {}
    }
    @Test fun preservesOriginalBytesAcrossWindowSizesSparseSeeksAndLargeOffsets() {
        val size=5L*1024*1024*1024+321
        remote(size,Cancellation()){_,_,c->source(size,c)}.use { file ->
            for (at in listOf(0L,1024*1024-20L,9L*1024*1024,66L*1024*1024,(1L shl 32)+77,size-39,0)) {
                val expected=minOf(400L,size-at).toInt();val out=ByteArray(expected);var done=0
                while(done<expected){val n=file.readAt(at+done,out,done,expected-done);assertTrue(n>0);done+=n}
                assertContentEquals(bytes(at,expected),out)
            }
            assertEquals(-1,file.readAt(size,ByteArray(1),0,1))
        }
    }
    @Test fun deliversFirstBytesWhileRestOfSegmentIsBlocked() {
        val release=CountDownLatch(1);val pool=Executors.newSingleThreadExecutor();val parent=Cancellation()
        val file=remote(1024*1024L,parent,1){_,_,c->object:RemoteFile {
            override val size=1024*1024L
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                c.check();if(position>0){release.await();c.check()}
                val n=minOf(8192,length);bytes(position,n).copyInto(buffer,offset);return n
            }
            override fun close(){release.countDown()}
        }}
        try {
            val out=ByteArray(8)
            assertEquals(8,pool.submit<Int>{file.readAt(0,out,0,8)}.get(2,TimeUnit.SECONDS))
            assertContentEquals(bytes(0,8),out)
        } finally {file.close();release.countDown();pool.shutdownNow()}
    }
    @Test fun retriesMissingSuffixWithoutDuplicatingOrSkippingBytes() {
        val starts=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val size=64*1024L;val opens=AtomicInteger()
        remote(size,Cancellation(),1){start,_,c->
            starts.add(start);val first=opens.incrementAndGet()==1
            object:RemoteFile {
                override val size=size
                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                    c.check();if(first&&position>=8192)throw IOException("fixture disconnect")
                    val n=minOf(8192L,length.toLong(),size-position).toInt();bytes(position,n).copyInto(buffer,offset);return n
                }
                override fun close(){}
            }
        }.use { file ->
            val out=ByteArray(size.toInt());var done=0
            while(done<out.size){val n=file.readAt(done.toLong(),out,done,out.size-done);assertTrue(n>0);done+=n}
            assertContentEquals(bytes(0,out.size),out);assertEquals(listOf(0L,8192L),starts)
        }
    }
    @Test fun exhaustedFailuresDoNotSkipAheadOrRetryForever() {
        val calls=AtomicInteger()
        remote(1024*1024L,Cancellation(),1){start,_,_->assertEquals(0L,start);calls.incrementAndGet();throw IOException("fixture")}.use { file ->
            assertFailsWith<IOException>{file.readAt(0,ByteArray(8),0,8)}
            assertEquals(3,calls.get())
        }
    }
    @Test fun parentCancellationUnblocksConsumerAndClosesTransport() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val parent=Cancellation();val pool=Executors.newSingleThreadExecutor()
        val file=remote(1024*1024L,parent,1){_,_,c->object:RemoteFile {
            override val size=1024*1024L
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {entered.countDown();release.await();c.check();return -1}
            override fun close(){release.countDown()}
        }}
        try {
            val read=pool.submit<Boolean>{try{file.readAt(0,ByteArray(1),0,1);false}catch(_:java.io.InterruptedIOException){true}}
            assertTrue(entered.await(2,TimeUnit.SECONDS));parent.close();assertTrue(read.get(2,TimeUnit.SECONDS))
        }finally{file.close();release.countDown();pool.shutdownNow()}
    }
    @Test fun completedSegmentsSurviveAReaderRestartAndCorruptCacheIsRejected() {
        val directory=kotlin.io.path.createTempDirectory("segment-contract-").toFile()
        try {
            val cache=SegmentCache(directory);val size=32*1024L
            remote(size,Cancellation(),1,cache){_,_,c->source(size,c)}.use { file ->
                val out=ByteArray(size.toInt());var done=0
                while(done<out.size)done+=file.readAt(done.toLong(),out,done,out.size-done)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                while(cache.bytes()==0L&&System.nanoTime()<deadline)Thread.sleep(5)
                assertTrue(cache.bytes()>0)
            }
            remote(size,Cancellation(),1,cache){_,_,_->error("must read completed cached bytes")}.use { file ->
                val out=ByteArray(8);assertEquals(8,file.readAt(61,out,0,8));assertContentEquals(bytes(61,8),out)
            }
            directory.listFiles()!!.single().writeBytes(ByteArray(size.toInt()+32))
            assertNull(cache.get("account-file-hash",0,size.toInt()))
        }finally{directory.deleteRecursively()}
    }
}
