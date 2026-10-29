package io.localplayer.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Seekable descriptor: reuse authenticated transports without exposing credentials or a server. */
internal class MpvInput private constructor(val descriptor:ParcelFileDescriptor,private val cleanup:()->Unit,private val readFailure:java.util.concurrent.atomic.AtomicReference<Throwable?> = java.util.concurrent.atomic.AtomicReference()):Closeable {
    private val closed=AtomicBoolean()
    val failure get()=readFailure.get()
    val path get()="fd://${descriptor.fd}"
    override fun close(){if(closed.compareAndSet(false,true)){runCatching{descriptor.close()};cleanup()}}
    companion object {
        private val closer=java.util.concurrent.Executors.newCachedThreadPool()
        fun open(context:Context,uri:Uri):MpvInput {
            if(!uri.isPlayerNetworkUri()){
                val fd=context.contentResolver.openFileDescriptor(uri,"r")?:error("无法打开媒体文件")
                return MpvInput(fd,{})
            }
            val lease=PlaybackRegistry.playback(uri)
            val thread=HandlerThread("yingxi-mpv-source").apply{start()}
            try {
                val started=System.nanoTime()
                val file=io.localplayer.core.ReadAheadFile(lease.open(),512*1024)
                PlaybackMetrics.opened(System.nanoTime()-started)
                val failed=java.util.concurrent.atomic.AtomicReference<Throwable?>()
                val released=AtomicBoolean()
                fun cleanup(){if(released.compareAndSet(false,true))closer.execute{
                    // Cancel a blocked transport even when the descriptor callback is busy.
                    runCatching{lease.close()};runCatching{file.close()};thread.quitSafely()
                }}
                val callback=object:ProxyFileDescriptorCallback(){
                    override fun onGetSize():Long=try{
                        lease.checkCurrent();file.size.also{lease.checkCurrent()}
                    }catch(e:Exception){failed.compareAndSet(null,e);throw ErrnoException("media size",OsConstants.EIO,e)}
                    override fun onRead(offset:Long,size:Int,data:ByteArray):Int {
                        val probe=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.READ,lease.source.protocol.name)
                        val start=System.nanoTime()
                        PlaybackIoProbe.attach(probe,file.readTelemetry)
                        var failure:Throwable?=null
                        try {
                            lease.checkCurrent()
                            if(offset>=file.size)return 0
                            val required=minOf(size.toLong(),file.size-offset).toInt()
                            var count=0
                            // FUSE may zero-fill the rest of a short filesystem read. SFTP can
                            // return smaller packets, so fill this request before replying.
                            while(count<required){
                                lease.checkCurrent()
                                val read=file.readAt(offset+count,data,count,required-count)
                                lease.checkCurrent()
                                if(read<=0)throw java.io.EOFException("远程文件读取中断")
                                count+=read
                                PlaybackIoProbe.progress(probe,read)
                            }
                            PlaybackMetrics.read(count,System.nanoTime()-start)
                            lease.checkCurrent()
                            return count
                        }catch(e:Exception){failure=e;failed.compareAndSet(null,e);throw ErrnoException("media read",OsConstants.EIO,e)}
                        finally{PlaybackIoProbe.end(probe,failure)}
                    }
                    override fun onRelease(){cleanup()}
                }
                val fd=context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,callback,Handler(thread.looper))
                var removeObserver:()->Unit={}
                val input=MpvInput(fd,{removeObserver();cleanup()},failed)
                removeObserver=PlaybackRegistry.observeInvalidation{sourceId,mediaId->
                    if(sourceId==lease.source.id||(mediaId!=null&&mediaId==lease.mediaId))input.close()
                }
                try{lease.checkCurrent()}catch(e:Exception){input.close();throw e}
                return input
            }catch(e:Exception){PlaybackRegistry.closeAsync(lease);thread.quitSafely();throw e}
        }
    }
}
