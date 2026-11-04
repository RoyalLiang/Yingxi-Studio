package io.localplayer.app

import android.view.TextureView
import android.view.ViewGroup
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A FUSE read can make steady progress before its first complete response reaches mpv. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MpvSlowStartupTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync{result=runCatching(block)}
        return result!!.getOrThrow()
    }
    @Test fun progressingRemoteReadCanProduceItsFirstFrameAfterThirtySeconds()=play(false)
    @Test fun stalledOpeningTimesOutAndClosesItsTransport()=play(true)
    private fun play(stalled:Boolean) {
        val app=instrumentation.targetContext
        val sample=betaPlaybackFixture(app)
        val chunks=AtomicInteger()
        val closed=CountDownLatch(1)
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession {
                override fun list(path:String)=emptyList<Entry>()
                override fun open(path:String)=object:RemoteFile {
                    private val file=RandomAccessFile(sample,"r")
                    override val size=sample.length()
                    override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                        cancellation.check()
                        if(stalled)while(true){cancellation.check();Thread.sleep(50)}
                        val drip=chunks.incrementAndGet()<=32
                        if(drip)Thread.sleep(1000)
                        cancellation.check();file.seek(position)
                        return file.read(buffer,offset,if(drip)minOf(length,33)else length)
                    }
                    override fun close(){file.close();closed.countDown()}
                }
                override fun close(){}
            }
        }
        val uri=PlaybackRegistry.add(Source(label="Slow startup fixture",protocol=Protocol.WEBDAV,host="localhost"),Credentials(""),"fixture.mp4","Slow startup fixture",connector)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var engine:MpvPlayer?=null
        try {
            val player=main{MpvPlayer(app,Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())}
            engine=player
            scenario.onActivity{activity->
                val texture=TextureView(activity)
                activity.addContentView(texture,ViewGroup.LayoutParams(-1,-1))
                player.setVideoTextureView(texture)
            }
            val started=android.os.SystemClock.elapsedRealtime()
            main{player.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri));player.prepare();player.play()}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65)
            while(System.nanoTime()<deadline&&!main{player.playerError!=null||player.playbackState==Player.STATE_READY})Thread.sleep(100)
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            if(stalled){
                main{assertNotNull(player.playerError);assertEquals(Player.STATE_IDLE,player.playbackState)}
                assertTrue("Inactivity must terminate before the total startup cap",elapsed<40_000)
                assertTrue("Timeout must cancel and close the current transport",closed.await(3,TimeUnit.SECONDS))
            }else{
                main{
                    assertNull(player.playerError?.errorCodeName,player.playerError)
                    assertEquals(Player.STATE_READY,player.playbackState)
                    assertTrue(player.videoSize.width>0)
                }
                assertTrue("First frame must cross the old thirty-second deadline",elapsed>=30_000)
                assertTrue(chunks.get()>32)
            }
        } finally {
            main{engine?.release()};scenario.close();PlaybackRegistry.remove(uri)
        }
    }
}
