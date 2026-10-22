package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Real service, extractors, codecs and pixels across a fixed-latency connection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkReadWindowPlaybackTest{
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
 @Test fun smbAvcKeepsRealFramesOnHighLatencyLink()=play(Protocol.SMB,false)
 @Test fun sftpAvcKeepsRealFramesOnHighLatencyLink()=play(Protocol.SFTP,false)
 @Test fun smbMain10KeepsRealFramesOnHighLatencyLink()=play(Protocol.SMB,true)
 @Test fun sftpMain10KeepsRealFramesOnHighLatencyLink()=play(Protocol.SFTP,true)
 private fun findSurface(view:View):SurfaceView?{
  if(view is SurfaceView)return view
  if(view is ViewGroup)for(i in 0 until view.childCount)findSurface(view.getChildAt(i))?.let{return it}
  return null
 }
 private fun picture(surface:SurfaceView):Long{
  val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);val done=CountDownLatch(1);var status=-1
  PixelCopy.request(surface,image,{status=it;done.countDown()},Handler(Looper.getMainLooper()))
  assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
  val pixels=IntArray(160*90);image.getPixels(pixels,0,160,0,0,160,90);image.recycle()
  assertTrue("Decoded pattern must contain real colors",pixels.toSet().size>24)
  return pixels.fold(17L){hash,pixel->hash*31+pixel}
 }
 private fun play(protocol:Protocol,hevc:Boolean){
  val app=ui.activity.application
  val sample=java.io.File(app.cacheDir,if(hevc)"hevc-main10-1080p.mp4"else"continuity-avc1080.mp4");assertTrue(sample.isFile)
  val prefs=EnrichmentPreferences(app);val posters=prefs.posters;val subtitles=prefs.subtitles;prefs.posters=false;prefs.subtitles=false
  try{PlaybackLatencyProxy(if(protocol==Protocol.SMB)TestFixtures.smbPort else TestFixtures.sftpPort).use{proxy->
   var source=Source(label="受控读取",protocol=protocol,host="127.0.0.1",port=proxy.port,root=if(protocol==Protocol.SMB)"media"else"/",username=TestFixtures.username)
   val secret=Credentials(TestFixtures.password)
   if(protocol==Protocol.SFTP){
    val fingerprint=try{SftpConnector().connect(source,secret).close();error("Expected host key verification")}catch(e:HostKeyRequired){e.fingerprint}
    source=source.copy(trustedKey=fingerprint,trustedAuthority=source.authority)
   }
   proxy.delayMs=60
   val path="r8-smoke/"+if(hevc)"r8-hevc.mp4"else"r8-avc.mp4"
   val ref=MediaRef(source.id+":"+path,path,source.label,source.id,path,sizeBytes=sample.length(),location=mediaLocation(source))
   val uri=PlaybackRegistry.add(source,secret,path,ref.title);val target=PlayTarget(uri,ref.title,0,ref)
   var scenario:ActivityScenario<PlaybackActivity>?=null;var controller:MediaController?=null
   val started=android.os.SystemClock.elapsedRealtime()
   try{
    main{PlaybackMetrics.reset()};scenario=ActivityScenario.launch(PlaybackActivity.intent(app,target))
    val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
    val player=future.get(10,TimeUnit.SECONDS);controller=player
    ui.waitUntil(20000){main{player.playerError!=null||PlaybackMetrics.snapshot().firstFrameMs!=null}}
    val firstMs=android.os.SystemClock.elapsedRealtime()-started
    main{assertNull(player.playerError);assertEquals(1920,player.videoSize.width)}
    println("NETWORK_FIRST_FRAME protocol=$protocol hevc=$hevc wallMs=$firstMs metrics=${PlaybackMetrics.snapshot()}")
    assertTrue("First frame must not wait for a large prefetch window",firstMs<8000)
    ui.waitUntil(20000){main{player.playerError!=null||(player.isPlaying&&player.currentPosition>=250)}}
    main{assertNull(player.playerError)}
    println("NETWORK_PLAYING protocol=$protocol hevc=$hevc wallMs=${android.os.SystemClock.elapsedRealtime()-started}")
    lateinit var surface:SurfaceView;scenario.onActivity{surface=requireNotNull(findSurface(it.window.decorView))}
    val first=picture(surface);Thread.sleep(650);val second=picture(surface)
    println(main{"NETWORK_PICTURE protocol=$protocol hevc=$hevc position=${player.currentPosition} state=${player.playbackState} bufferedMs=${player.totalBufferedDuration} metrics=${PlaybackMetrics.snapshot()}"})
    assertNotEquals("Decoded picture must progress",first,second)
    ui.waitUntil(20000){main{player.playerError!=null||player.currentPosition>=if(hevc)3500 else 5500}}
    main{assertNull(player.playerError)}
    println("NETWORK_CONTINUITY protocol=$protocol hevc=$hevc metrics=${PlaybackMetrics.snapshot()}")
    assertEquals("Playback should not repeatedly buffer when the link has enough capacity",0,PlaybackMetrics.snapshot().rebufferCount)
    main{player.seekTo(1000)}
    ui.waitUntil(15000){main{player.playerError!=null||(player.currentPosition>=2000&&player.currentPosition<3400)}}
    main{assertNull(player.playerError)};picture(surface)
   }finally{
    main{controller?.let{it.stop();it.clearMediaItems();it.release()}}
    scenario?.close();PlaybackRequests.remove(target.playId);PlaybackRegistry.remove(uri);HistoryStore(app).remove(ref.key)
   }
  }}finally{prefs.posters=posters;prefs.subtitles=subtitles}
 }
}

private class PlaybackLatencyProxy(private val upstream:Int):java.io.Closeable{
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
  val remote=Socket("10.0.2.2",upstream);client.tcpNoDelay=true;remote.tcpNoDelay=true;sockets.add(client);sockets.add(remote)
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
