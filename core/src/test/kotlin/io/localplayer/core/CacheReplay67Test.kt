package io.localplayer.core

import java.lang.management.ManagementFactory
import kotlin.test.*

class CacheReplay67Test {
 private val mib=1024*1024
 private val bean get()=(ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).apply{isThreadAllocatedMemoryEnabled=true}
 @Test fun verifiedCacheReadUsesOnePayloadBuffer(){
  val root=kotlin.io.path.createTempDirectory("cache-read67-").toFile()
  try{
   val cache=SegmentCache(root);val data=ByteArray(8*mib){(it%251).toByte()};cache.put("large",0,data)
   cache.put("warm",0,ByteArray(1024));cache.get("warm",0,1024)
   val meter=bean;val id=Thread.currentThread().id;val before=meter.getThreadAllocatedBytes(id)
   val read=cache.get("large",0,data.size)!!;val allocated=meter.getThreadAllocatedBytes(id)-before
   println("CACHE67 direct allocated=$allocated payload=${data.size}")
   assertContentEquals(data,read);assertTrue(allocated<data.size+2*mib,"Cache hit allocated $allocated for ${data.size} bytes")
  }finally{root.deleteRecursively()}
 }
 @Test fun unalignedReplayKeepsAllocationBoundedWithoutNetwork(){
  val root=kotlin.io.path.createTempDirectory("cache-slice67-").toFile();val parent=Cancellation()
  try{
   val cache=SegmentCache(root);val data=ByteArray(8*mib){(it%251).toByte()};cache.put("video",64L*mib,data)
   val meter=bean;val oldIds=Thread.getAllStackTraces().keys.map{it.id}.toSet();val current=Thread.currentThread().id
   val file=SegmentedRemoteFile(128L*mib,parent,AliyunReadOptions(1,cache),"video",RemoteReadTelemetry()){_,_,_->error("A warm replay must not use network")}
   try{
    val target=ByteArray(1024);val before=meter.getThreadAllocatedBytes(current)
    assertEquals(target.size,file.readAt(65L*mib,target,0,target.size));val mainAllocated=meter.getThreadAllocatedBytes(current)-before
    assertContentEquals(data.copyOfRange(mib,mib+target.size),target)
    val worker=Thread.getAllStackTraces().keys.single{it.name=="cloud-segment"&&it.id !in oldIds}
    val deadline=System.nanoTime()+1_000_000_000L
    while(worker.state!=Thread.State.WAITING&&System.nanoTime()<deadline)Thread.sleep(1)
    assertEquals(Thread.State.WAITING,worker.state)
    val allocated=mainAllocated+meter.getThreadAllocatedBytes(worker.id)
    println("CACHE67 unaligned allocated=$allocated payload=${data.size} main=$mainAllocated")
    assertTrue(allocated<18L*mib,"Unaligned cache replay allocated $allocated bytes")
   }finally{file.close()}
  }finally{parent.close();root.deleteRecursively()}
 }
 @Test fun optimizedReadsStillRejectCorruption(){
  val root=kotlin.io.path.createTempDirectory("cache-integrity67-").toFile()
  try{
   val cache=SegmentCache(root);cache.put("video",0,ByteArray(1024){7})
   val file=root.listFiles()!!.single();val bytes=file.readBytes();bytes[bytes.lastIndex]=9;file.writeBytes(bytes)
   assertNull(cache.get("video",0,1024));assertFalse(file.exists())
  }finally{root.deleteRecursively()}
 }
}
