package io.localplayer.core

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class SftpLatencyTest {
 @Test fun sequentialPlaybackReadKeepsThroughputOnHighLatencyLink(){
  val root=Files.createTempDirectory("sftp-latency")
  val content=ByteArray(2*1024*1024){(it%251).toByte()}
  Files.write(root.resolve("sample.bin"),content)
  val password=java.util.UUID.randomUUID().toString()
  val server=SshServer.setUpDefaultServer().apply{
   host="127.0.0.1";port=0;keyPairProvider=SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
   passwordAuthenticator=org.apache.sshd.server.auth.password.PasswordAuthenticator{u,p,_->u=="reader"&&p==password}
   subsystemFactories=listOf(SftpSubsystemFactory.Builder().build());fileSystemFactory=VirtualFileSystemFactory(root)
   SftpModuleProperties.MAX_READDATA_PACKET_LENGTH.set(this,32768)
  }
  server.start()
  try{
   val direct=Source(label="latency fixture",protocol=Protocol.SFTP,host="127.0.0.1",port=server.port,root="/",username="reader")
   val fingerprint=try{SftpConnector().connect(direct,Credentials(password)).close();error("Host key must be verified")}catch(e:HostKeyRequired){e.fingerprint}
   LatencyProxy(server.port).use{proxy->
    val unknown=direct.copy(port=proxy.port)
    val source=unknown.copy(trustedKey=fingerprint,trustedAuthority=unknown.authority)
    SftpConnector().connect(source,Credentials(password)).use{session->
     proxy.delayMs=60 // 120 ms round trip; no bandwidth throttling.
     val before=proxy.receivedBytes.get()
     ReadAheadFile(session.open("sample.bin")).use{file->
      assertEquals(8,file.readAt(0,ByteArray(8),0,8));Thread.sleep(200)
      val prefixBytes=proxy.receivedBytes.get()-before
      println("SFTP_PREFIX bytes=$prefixBytes")
      assertTrue(prefixBytes<100000,"A tiny extractor header must not fetch a full playback window: $prefixBytes")
      val actual=ByteArray(content.size);var position=0;var calls=0
      val started=System.nanoTime()
      while(position<actual.size){val n=file.readAt(position.toLong(),actual,position,minOf(8192,actual.size-position));assertTrue(n>0);position+=n;calls++}
      val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
      println("SFTP_LATENCY bytes=$position elapsedMs=$elapsed calls=$calls rttMs=120")
      assertContentEquals(content,actual)
      assertTrue(elapsed<2000,"Sequential 2 MiB took ${elapsed}ms; request serialization cannot sustain playback")
      val tail=ByteArray(127);assertEquals(127,file.readAt(file.size-127,tail,0,127));assertContentEquals(content.takeLast(127).toByteArray(),tail)
     }
    }
   }
  }finally{server.stop(true);root.toFile().deleteRecursively()}
 }
}

/** Adds fixed latency to each direction without serial sleeps per packet. */
internal class LatencyProxy(private val upstream:Int):java.io.Closeable{
 @Volatile var delayMs=0L
 val receivedBytes=java.util.concurrent.atomic.AtomicLong()
 private val closed=AtomicBoolean(false)
 private val listener=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
 val port get()=listener.localPort
 private val readers=Executors.newCachedThreadPool()
 private val timers=java.util.Collections.synchronizedList(mutableListOf<ScheduledExecutorService>())
 private val sockets=java.util.Collections.synchronizedList(mutableListOf<Socket>())
 init{readers.execute{while(!closed.get()){
  val client=try{listener.accept()}catch(e:java.io.IOException){break}
  val remote=Socket("127.0.0.1",upstream);client.tcpNoDelay=true;remote.tcpNoDelay=true;sockets.add(client);sockets.add(remote)
  forward(client,remote,false);forward(remote,client,true)
 }}}
 private fun forward(from:Socket,to:Socket,downstream:Boolean){
  val timer=Executors.newSingleThreadScheduledExecutor();timers.add(timer)
  readers.execute{try{
   val bytes=ByteArray(65536)
   while(!closed.get()){
    val n=from.getInputStream().read(bytes);if(n<0)break
    if(downstream)receivedBytes.addAndGet(n.toLong())
    val copy=bytes.copyOf(n)
    timer.schedule({runCatching{to.getOutputStream().write(copy);to.getOutputStream().flush()}},delayMs,TimeUnit.MILLISECONDS)
   }
  }catch(e:Exception){if(!closed.get()&&e !is java.io.IOException)throw e}}
 }
 override fun close(){
  if(!closed.compareAndSet(false,true))return
  listener.close();synchronized(sockets){sockets.forEach{runCatching{it.close()}}};readers.shutdownNow()
  synchronized(timers){timers.forEach{it.shutdownNow()}}
 }
}
