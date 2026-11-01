package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** Synthetic 4K Main10 HDR MKV, through the real session and authenticated SFTP reads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReportedMkvStartupTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync{result=runCatching(block)}
        return result!!.getOrThrow()
    }
    @Test fun localHdrMkvReachesVideo()=play(false,0)
    @Test fun sftpHdrMkvReachesVideo()=play(true,0)
    @Test fun sftpHdrMkvResumesThroughItsIndex()=play(true,1250)
    private fun surface(view:View):View?{
        if(view is TextureView||view is SurfaceView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)surface(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun assertVisibleFrame(scenario:ActivityScenario<PlaybackActivity>){
        lateinit var output:View
        scenario.onActivity{output=checkNotNull(surface(it.window.decorView))}
        val image=if(output is TextureView)main{checkNotNull((output as TextureView).getBitmap(160,90))}else{
            val bitmap=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888)
            val done=CountDownLatch(1);var status=-1
            PixelCopy.request(output as SurfaceView,bitmap,{status=it;done.countDown()},Handler(Looper.getMainLooper()))
            assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status);bitmap
        }
        try{
            val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            assertTrue("Video surface is blank",pixels.toSet().size>24)
        }finally{image.recycle()}
    }
    private fun play(remote:Boolean,resume:Long){
        val name=InstrumentationRegistry.getArguments().getString("mkv_fixture")?:"4k-hdr.mkv"
        require(name in setOf("4k-hdr.mkv","4k-dv5.mkv"))
        val sample=File(context.cacheDir,name);check(sample.isFile)
        val uri:Uri
        val media:MediaRef
        if(remote){
            val untrusted=Source(label="4K fixture",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
            val credentials=Credentials(TestFixtures.password)
            val fingerprint=try{SftpConnector().connect(untrusted,credentials).close();error("Expected host verification")}catch(e:HostKeyRequired){e.fingerprint}
            val source=untrusted.copy(trustedKey=fingerprint,trustedAuthority=untrusted.authority)
            media=MediaRef(source.id+":"+name,name,source.label,source.id,name,sizeBytes=sample.length(),location=mediaLocation(source))
            uri=PlaybackRegistry.add(source,credentials,name,media.title)
        }else{
            uri=Uri.fromFile(sample);media=MediaRef(uri.toString(),name,"4K fixture",null,uri.toString())
        }
        main{PlaybackMetrics.reset()}
        var controller:MediaController?=null
        val scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(context,PlayTarget(uri,media.title,resume,media)))
        try{
            val player=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}.get(10,TimeUnit.SECONDS)
            controller=player
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
            fun encodedWidth()=player.currentTracks.groups.firstOrNull{it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO&&it.length>0}?.getTrackFormat(0)?.width
            // The official DV sample opens on black; inspect scene pixels after its fade-in.
            while(System.nanoTime()<deadline&&!main{player.playerError!=null||(PlaybackMetrics.snapshot().firstFrameMs!=null&&encodedWidth()==3840&&player.currentPosition>=2000)})Thread.sleep(100)
            main{
                println("MKV_START remote=$remote resume=$resume state=${player.playbackState} metrics=${PlaybackMetrics.snapshot()} io=${PlaybackIoProbe.snapshot()}")
                assertNull(player.playerError)
                assertEquals(3840,encodedWidth())
                assertNotNull("4K MKV never presented a frame",PlaybackMetrics.snapshot().firstFrameMs)
                assertTrue(player.currentPosition>=resume)
                assertEquals("MATROSKA",PlaybackExtractionProbe.snapshot().extractor)
            }
            assertVisibleFrame(scenario)
        }finally{main{controller?.stop();controller?.clearMediaItems();controller?.release()};scenario.close();if(remote)PlaybackRegistry.remove(uri)}
    }
}
