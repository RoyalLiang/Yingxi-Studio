package io.localplayer.core

import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.yingxiReadAsync
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded random-access read window. Issuing requests may wait for SMB credits,
 * so it runs off the reader thread and cannot hold up the first available block. */
internal class SmbReadFile(
 private val file:File,override val size:Long,private val cancellation:Cancellation,readSize:Int
):RemoteFile{
 private val blockSize=minOf(65536,readSize.coerceAtLeast(1))
 private val closed=AtomicBoolean(false)
 private val issuer=Executors.newSingleThreadExecutor()
 private val window=AtomicReference<Window?>()
 private var cursor=-1L
 private var bytes=ByteArray(0)
 private var consumed=0
 private val aborter=java.io.Closeable{abort()}
 init{cancellation.own(aborter)}
 private fun abort(){
  if(!closed.compareAndSet(false,true))return
  window.getAndSet(null)?.stop(false);issuer.shutdownNow()
 }
 private data class Pending(val future:Future<SMB2ReadResponse>?,val length:Int,val error:Exception?=null)
 private inner class Window(start:Long){
  val stopped=AtomicBoolean(false)
  val warmed=AtomicBoolean(false)
  private val slots=Semaphore(1)
  fun warm(){if(warmed.compareAndSet(false,true))slots.release(15)}
  val queue=ArrayBlockingQueue<Pending>(16)
  val active=AtomicReference<Future<SMB2ReadResponse>?>()
  val issuing=AtomicReference<Future<SMB2ReadResponse>?>()
  val task=issuer.submit{
   try{
    var at=start
    while(!stopped.get()&&at<size){
     slots.acquire();if(stopped.get())break
     cancellation.check()
     val length=minOf(blockSize.toLong(),size-at).toInt()
     val future=file.yingxiReadAsync(at,length);issuing.set(future)
     if(stopped.get()){future.cancel(true);break}
     queue.put(Pending(future,length));issuing.compareAndSet(future,null);at+=length
    }
   }catch(e:Exception){if(!stopped.get())runCatching{queue.put(Pending(null,0,e))}}
  }
  fun releaseSlot(){slots.release()}
  fun stop(cancelNetwork:Boolean=true){
   if(!stopped.compareAndSet(false,true))return
   task.cancel(true)
   val pending=mutableListOf<Pending>();queue.drainTo(pending)
   val futures=listOfNotNull(issuing.getAndSet(null),active.getAndSet(null))+pending.mapNotNull{it.future}
   // Session cancellation closes the socket next. Sending SMB CANCEL first
   // can itself wait on a delayed transport, so do not perform wire I/O here.
   if(cancelNetwork)futures.forEach{runCatching{it.cancel(true)}}
   // Wake a consumer waiting for a request, including a close racing startup.
   queue.offer(Pending(null,0,InterruptedIOException("读取已关闭")))
  }
 }
 private fun newWindow(position:Long):Window{
  checkOpen()
  return try{Window(position)}catch(e:RejectedExecutionException){checkOpen();throw IOException("SMB 读取已停止",e)}
 }
 private fun checkOpen(){cancellation.check();if(closed.get())throw InterruptedIOException("读取已关闭")}
 @Synchronized override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{
  require(position>=0&&offset>=0&&length>=0&&offset<=buffer.size&&length<=buffer.size-offset)
  checkOpen();if(length==0)return 0;if(position>=size)return -1
  if(position!=cursor){
   window.getAndSet(null)?.stop();bytes=ByteArray(0);consumed=0;cursor=position
   val next=newWindow(position);window.set(next)
   if(closed.get()){next.stop();checkOpen()}
  }
  val current=window.get()?:throw InterruptedIOException("读取已关闭")
  if(consumed>=bytes.size){
   if(bytes.isNotEmpty())current.warm()
   try{
    val request=current.queue.poll(8,TimeUnit.SECONDS)?:throw IOException("SMB 读取等待超时")
    request.error?.let{throw it}
    val future=requireNotNull(request.future);current.active.set(future)
    if(current.stopped.get()){future.cancel(true);checkOpen();throw InterruptedIOException("读取已关闭")}
    val response=try{
     val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
     // SMBJ wraps timed get() timeouts as runtime failures. Poll readiness
     // without consuming the result, checking local cancellation between waits.
     while(!future.isDone){
      checkOpen();if(current.stopped.get())throw InterruptedIOException("读取已关闭")
      if(System.nanoTime()>=deadline)throw TimeoutException()
      Thread.sleep(10)
     }
     checkOpen();future.get(8,TimeUnit.SECONDS)
    }finally{current.active.compareAndSet(future,null)}
    if(response.header.statusCode!=com.hierynomus.mserref.NtStatus.STATUS_SUCCESS.value &&
       response.header.statusCode!=com.hierynomus.mserref.NtStatus.STATUS_END_OF_FILE.value)throw IOException("SMB 读取失败")
    bytes=response.data;consumed=0
    if(current.warmed.get())current.releaseSlot()
    if(bytes.isEmpty())return -1
    if(bytes.size>request.length)throw IOException("SMB 读取超出请求范围")
    if(bytes.size<request.length){
     // Some servers return a short block before EOF. Discard speculative
     // offsets and continue at the actual byte boundary, never skip data.
     current.stop();window.set(newWindow(cursor+bytes.size))
    }
   }catch(e:InterruptedException){Thread.currentThread().interrupt();throw InterruptedIOException("读取已取消").apply{initCause(e)}}
   catch(e:ExecutionException){throw IOException("SMB 读取失败",e.cause)}
   catch(e:CancellationException){throw InterruptedIOException("读取已取消").apply{initCause(e)}}
   catch(e:TimeoutException){throw IOException("SMB 读取等待超时",e)}
   catch(e:Exception){checkOpen();throw if(e is IOException)e else IOException("SMB 读取失败",e)}
  }
  checkOpen()
  val count=minOf(length,bytes.size-consumed)
  bytes.copyInto(buffer,offset,consumed,consumed+count);consumed+=count;cursor+=count
  return count
 }
 override fun close(){
  if(!closed.compareAndSet(false,true))return
  cancellation.release(aborter)
  window.getAndSet(null)?.stop();issuer.shutdownNow();file.close()
 }
}
