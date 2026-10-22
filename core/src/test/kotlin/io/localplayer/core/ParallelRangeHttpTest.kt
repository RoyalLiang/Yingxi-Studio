package io.localplayer.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ParallelRangeHttpTest {
    @Test fun concurrentBoundedRangesImproveConnectionThrottledFixtureWithoutChangingBytes() {
        MockWebServer().use { server ->
            val data=ByteArray(4*1024*1024){(it%251).toByte()}
            val ranges=java.util.Collections.synchronizedList(mutableListOf<Pair<Int,Int>>())
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                assertEquals("identity",request.getHeader("Accept-Encoding"));assertNull(request.getHeader("Authorization"))
                val match=Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(request.getHeader("Range")!!)!!
                val start=match.groupValues[1].toInt();val end=match.groupValues[2].toIntOrNull()?:data.lastIndex
                ranges.add(start to end)
                return MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data,start,end-start+1)).throttleBody(64*1024,25,TimeUnit.MILLISECONDS)
            }}
            server.start();val client=OkHttpClient()
            fun transfer(workers:Int):Long {
                val parent=Cancellation();val stats=RemoteReadTelemetry();val started=System.nanoTime()
                SegmentedRemoteFile(data.size.toLong(),parent,AliyunReadOptions(workers),"fixture",stats){_,end,c->
                    AliyunRangeFile(data.size.toLong(),c,client,readTelemetry=stats,endExclusive=end){server.url("/original").toString()}
                }.use { file ->
                    val output=ByteArray(data.size);var at=0
                    while(at<output.size){val n=file.readAt(at.toLong(),output,at,minOf(8192,output.size-at));assertTrue(n>0);at+=n}
                    assertContentEquals(data,output)
                }
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
            }
            val single=transfer(1);ranges.clear();val parallel=transfer(3)
            println("CONTROLLED_CONNECTION_CAP singleMs=$single parallelMs=$parallel ratio=${single.toDouble()/parallel}")
            assertTrue(parallel<single*0.9,"Parallel fixture: single=$single parallel=$parallel")
            assertTrue(ranges.size>=4);assertTrue(ranges.all{it.second-it.first+1<=1024*1024})
            client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
        }
    }
    @Test fun telemetryCountsOverlappingBodyWaitAsWallTimeInsteadOfSummingThreads() {
        val stats=RemoteReadTelemetry();val ready=java.util.concurrent.CountDownLatch(3);val release=java.util.concurrent.CountDownLatch(1)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val jobs=(0..2).map { pool.submit {stats.measure(RemoteReadTelemetry.Stage.BODY){ready.countDown();release.await();stats.received(1024)}} }
            assertTrue(ready.await(2,TimeUnit.SECONDS));Thread.sleep(1100);release.countDown();jobs.forEach{it.get(2,TimeUnit.SECONDS)}
            val snapshot=stats.snapshot();assertTrue(snapshot.bodyMs in 1000..2000,"Parallel wall time ${snapshot.bodyMs}")
            assertTrue(snapshot.bodyBytesPerSecond!!>1500,"Must report aggregate body throughput")
        }finally{release.countDown();pool.shutdownNow()}
    }
}
