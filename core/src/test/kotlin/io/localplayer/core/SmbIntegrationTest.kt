package io.localplayer.core

import kotlin.test.*

class SmbIntegrationTest {
    @Test fun highLatencySequentialReadKeepsPlaybackThroughput(){
        val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
        org.junit.Assume.assumeTrue("Explicit loopback fixture required",port!=null)
        LatencyProxy(port!!).use{proxy->
            val source=Source(label="SMB latency",protocol=Protocol.SMB,host="127.0.0.1",port=proxy.port,root="media",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
            SmbConnector().connect(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD")))).use{session->
                proxy.delayMs=60
                val before=proxy.receivedBytes.get()
                ReadAheadFile(session.open("影片/large.mp4")).use{file->
                    assertEquals(8,file.readAt(0,ByteArray(8),0,8));Thread.sleep(200)
                    val prefixBytes=proxy.receivedBytes.get()-before;println("SMB_PREFIX bytes=$prefixBytes")
                    assertTrue(prefixBytes<140000,"A tiny extractor header must not fetch a full playback window: $prefixBytes")
                    val bytes=ByteArray(2*1024*1024);var position=0;var calls=0
                    val start=System.nanoTime()
                    while(position<bytes.size){val n=file.readAt(position.toLong(),bytes,position,minOf(8192,bytes.size-position));assertTrue(n>0);position+=n;calls++}
                    val elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
                    println("SMB_LATENCY bytes=$position elapsedMs=$elapsed calls=$calls rttMs=120")
                    assertContentEquals(ByteArray(bytes.size),bytes)
                    assertTrue(elapsed<2000,"Sequential 2 MiB took ${elapsed}ms")
                    val marker="large-offset-marker".toByteArray();val actual=ByteArray(marker.size)
                    assertEquals(actual.size,file.readAt(file.size-actual.size,actual,0,actual.size));assertContentEquals(marker,actual)
                }
            }
        }
    }
    @Test fun prefetchSeeksPreserveBytesAndCancellationUnblocks(){
        val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
        org.junit.Assume.assumeTrue("Explicit loopback fixture required",port!=null)
        val expected=ByteArray(4*1024*1024+8192){(it%251).toByte()}
        LatencyProxy(port!!).use{proxy->
            val source=Source(label="SMB cancellation",protocol=Protocol.SMB,host="127.0.0.1",port=proxy.port,root="media",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
            val token=Cancellation()
            val session=SmbConnector().connect(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD"))),token)
            val file=ReadAheadFile(session.open("read-window.bin"))
            val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
            try{
                val output=ByteArray(8192)
                for(at in listOf(0,70000,250000,expected.size-8192,4,150000,0,900000)){
                    val n=file.readAt(at.toLong(),output,0,output.size);assertTrue(n>0)
                    assertContentEquals(expected.copyOfRange(at,at+n),output.copyOf(n))
                }
                repeat(8){
                    val racing=ReadAheadFile(session.open("read-window.bin"))
                    val read=pool.submit<Int>{racing.readAt(0,ByteArray(8),0,8)}
                    racing.close()
                    try{assertTrue(read.get(2,java.util.concurrent.TimeUnit.SECONDS)>0)}catch(e:java.util.concurrent.ExecutionException){assertTrue(e.cause is java.io.IOException,"Concurrent close must remain an I/O cancellation: ${e.cause}")}
                }
                proxy.delayMs=1500
                val task=pool.submit<Int>{file.readAt(3000000,output,0,output.size)}
                Thread.sleep(100);val started=System.nanoTime();token.close()
                assertFailsWith<java.util.concurrent.ExecutionException>{task.get(2,java.util.concurrent.TimeUnit.SECONDS)}
                val elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);println("SMB_CANCEL elapsedMs=$elapsed");assertTrue(elapsed<1500,"Cancellation must not wait for delayed prefetch: $elapsed")
            }finally{token.close();runCatching{file.close()};runCatching{session.close()};pool.shutdownNow()}
        }
    }
    @Test fun authenticatedShareEnumeration(){
  val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
  org.junit.Assume.assumeTrue("Explicit loopback fixture required",port!=null)
  val source=Source(label="Shares",protocol=Protocol.SMB,host="127.0.0.1",port=port!!,root="",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
  Cancellation().use { cancellation ->
   assertTrue(SmbShares.list(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD"))),cancellation).any { it.equals("media",true) })
  }
 }
 @Test fun idlePlaybackConnectionCanSeekAgain(){
        val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
        org.junit.Assume.assumeTrue("Set PLAYER_SMB_FIXTURE_PORT for the loopback fixture",port!=null)
        val source=Source(label="SMB idle fixture",protocol=Protocol.SMB,host="127.0.0.1",port=port!!,root="media",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
        SmbConnector().connect(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD")){"Explicit loopback fixture password required"})).use{session ->
            session.open("影片/large.mp4").use{assertEquals(4,it.readAt(0,ByteArray(4),0,4))}
            Thread.sleep(10_000) // Playback can buffer or pause longer than a request deadline.
            session.open("影片/large.mp4").use{file ->
                val marker="large-offset-marker".toByteArray();val actual=ByteArray(marker.size)
                assertEquals(actual.size,file.readAt(file.size-actual.size,actual,0,actual.size))
                assertContentEquals(marker,actual)
            }
        }
    }
    @Test fun realSmbAuthenticationBrowsingAndLargeOffset(){
        val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
        org.junit.Assume.assumeTrue("Set PLAYER_SMB_FIXTURE_PORT for the isolated loopback server",port!=null)
        val source=Source(label="SMB loopback",protocol=Protocol.SMB,host="127.0.0.1",port=port!!,root="media",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
        val connector=SmbConnector()
        connector.connect(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD")){"Explicit loopback fixture password required"})).use{session ->
            assertTrue(session.list("").any{it.name=="影片"&&it.directory})
            val entry=session.list("影片").first{it.name=="large.mp4"}
            val marker="large-offset-marker".toByteArray()
            assertTrue(entry.size>(1L shl 32))
            session.open(entry.path).use{file ->
                val bytes=ByteArray(marker.size)
                assertEquals(marker.size,file.readAt(file.size-marker.size,bytes,0,bytes.size));assertContentEquals(marker,bytes)
                assertEquals(-1,file.readAt(file.size,bytes,0,bytes.size))
                assertEquals(4,file.readAt(0,bytes,0,4));assertContentEquals(ByteArray(4),bytes.copyOf(4))
            }
        }
        assertFails{connector.connect(source,Credentials("wrong-fixture-password"))}
    }
}
