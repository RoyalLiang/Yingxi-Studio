package io.localplayer.app

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MpvInputPlaybackLeaseTest {
    @Test fun originalPlaybackUsesItsOwnPositionAwareTransportAndPreservesRegisteredConnector() {
        val originals=AtomicInteger();val closed=AtomicInteger()
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation):RemoteSession {
                val ended=AtomicBoolean()
                return object:RemoteSession {
                    override fun list(path:String)=emptyList<Entry>()
                    override fun open(path:String):RemoteFile=error("Original playback must retain position-aware source reads")
                    override fun open(path:String,position:Long):RemoteFile {
                        originals.incrementAndGet()
                        return object:RemoteFile {
                            override val size=(1L shl 32)+262144
                            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                                cancellation.check();check(!ended.get())
                                val count=minOf(length.toLong(),size-position).toInt()
                                repeat(count){buffer[offset+it]=((position+it)%251).toByte()};return count
                            }
                            override fun close(){}
                        }
                    }
                    override fun close(){if(ended.compareAndSet(false,true))closed.incrementAndGet()}
                }
            }
        }
        val uri=PlaybackRegistry.add(Source(label="fixture",protocol=Protocol.WEBDAV,host="127.0.0.1",port=9,secure=false),Credentials(""),"fixture.mp4","fixture.mp4",connector)
        val active=PlaybackRegistry.get(uri)
        val original=active.open()
        try {
            MpvInput.open(InstrumentationRegistry.getInstrumentation().targetContext,uri).use { input ->
                for(position in listOf(0L,(1L shl 32)+7,65536L,65536L+4096)) {
                    val bytes=ByteArray(4096)
                    assertEquals(bytes.size,Os.pread(input.descriptor.fileDescriptor,bytes,0,bytes.size,position))
                    assertArrayEquals(ByteArray(bytes.size){((position+it)%251).toByte()},bytes)
                }
            }
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(closed.get()==0&&System.nanoTime()<until)Thread.sleep(10)
            assertEquals("MPV owns one independent transport",1,closed.get())
            assertEquals(2,originals.get())
            assertEquals("The registered playlist lease must remain readable",1,original.readAt(0,ByteArray(1),0,1))
        } finally {original.close();PlaybackRegistry.remove(uri)}
    }
}
